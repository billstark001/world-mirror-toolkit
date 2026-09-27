package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.MirrorSavePaths;
import dev.worldmirror.toolkit.core.WorldLayout;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports analysis chunks into an existing save with explicit vanilla and mirror policies. */
public final class ChunkImporter {
    public enum Mode { DEFAULT, MIRROR }
    public enum Policy { EMPTY_ONLY, TIMESTAMP }

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final DateTimeFormatter BACKUP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RegionFileStore regions = new RegionFileStore();

    public record Options(Path source, Path destination, Mode mode, Policy policy,
            String destinationLayout, boolean allowSourceMismatch) {}
    private record Decision(DimensionKey dimension, Path sourceRegion, Path targetRegion,
            int slot, ChunkPos pos, long captureTime, boolean replacedEmpty) {}
    public record Plan(Options options, AnalysisExportMetadata sourceMetadata,
            WorldSaveVersion destinationVersion, Map<Path, List<Decision>> decisions,
            int missing, int emptyReplaced, int newerReplaced, int preserved,
            Path database, String sourceId) {
        public int chunksToWrite() { return missing + emptyReplaced + newerReplaced; }
        public boolean versionMismatch() {
            return sourceMetadata.dataVersion() != destinationVersion.dataVersion()
                    || !sourceMetadata.minecraftVersion().equals(destinationVersion.minecraftVersion());
        }
        public Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source_version", sourceMetadata.minecraftVersion());
            value.put("destination_version", destinationVersion.minecraftVersion());
            value.put("mode", options.mode().name().toLowerCase(Locale.ROOT));
            value.put("policy", options.policy().name().toLowerCase(Locale.ROOT));
            value.put("version_mismatch", versionMismatch());
            value.put("missing", missing);
            value.put("empty_replaced", emptyReplaced);
            value.put("newer_replaced", newerReplaced);
            value.put("preserved", preserved);
            value.put("regions_to_write", decisions.size());
            return value;
        }
    }

    public Plan plan(Options options) throws IOException {
        Path source = options.source().toAbsolutePath().normalize();
        Path destination = options.destination().toAbsolutePath().normalize();
        if (source.equals(destination) || source.startsWith(destination) || destination.startsWith(source)) {
            throw new IOException("source and destination must be separate save trees");
        }
        AnalysisExportMetadata export = AnalysisExportMetadata.read(source);
        WorldSaveVersion sourceVersion = WorldSaveVersion.read(source);
        if (sourceVersion.dataVersion() != export.dataVersion()) {
            throw new IOException("source level.dat and export manifest DataVersion disagree");
        }
        WorldSaveVersion targetVersion = WorldSaveVersion.read(destination);
        WorldLayout sourceLayout = WorldLayout.parse(export.layout(), export.minecraftVersion());
        WorldLayout targetLayout = WorldLayout.parse(options.destinationLayout(), targetVersion.minecraftVersion());
        MirrorSavePaths mirrorPaths = MirrorSavePaths.at(destination);
        String sourceId = null;
        Path database = mirrorPaths.chunkIndex();
        if (options.mode() == Mode.MIRROR) {
            ObjectNode metadata = mirrorMetadata(mirrorPaths.metadata());
            sourceId = metadata.path("sourceId").asText();
            if (sourceId.isBlank()) throw new IOException("mirror metadata lacks sourceId");
            if (!options.allowSourceMismatch() && !sourceId.equalsIgnoreCase(export.sourceKey())) {
                throw new IOException("replay source " + export.sourceKey() + " differs from mirror " + sourceId
                        + "; use --allow-source-mismatch only after verifying the target");
            }
        } else if (options.policy() == Policy.TIMESTAMP) {
            throw new IOException("timestamp policy requires --mode mirror and a chunk index");
        }

        Connection connection = null;
        try {
            if (options.mode() == Mode.MIRROR && Files.exists(database)) {
                SQLiteConfig readOnly = new SQLiteConfig();
                readOnly.setReadOnly(true);
                connection = DriverManager.getConnection("jdbc:sqlite:" + database,
                        readOnly.toProperties());
            }
            Map<Path, List<Decision>> decisions = new LinkedHashMap<>();
            int missing = 0, empty = 0, newer = 0, preserved = 0;
            for (String dimensionName : new TreeMap<>(export.chunksByDimension()).keySet()) {
                DimensionKey dimension = new DimensionKey(dimensionName);
                Path sourceDir = sourceLayout.dimensionPath(source, dimension).resolve("region");
                Path targetDir = targetLayout.dimensionPath(destination, dimension).resolve("region");
                if (!Files.isDirectory(sourceDir)) throw new IOException("missing source region directory " + sourceDir);
                Map<ChunkPos, ChunkIndexStore.ChunkRecord> index = loadIndex(connection, sourceId, dimensionName);
                try (var files = Files.list(sourceDir)) {
                    for (Path sourceRegion : files.filter(Files::isRegularFile).sorted().toList()) {
                        Matcher name = REGION.matcher(sourceRegion.getFileName().toString());
                        if (!name.matches()) continue;
                        int regionX = Integer.parseInt(name.group(1));
                        int regionZ = Integer.parseInt(name.group(2));
                        Path targetRegion = targetDir.resolve(sourceRegion.getFileName());
                        Map<Integer, RegionFileStore.Slot> incoming = regions.read(sourceRegion);
                        Map<Integer, RegionFileStore.Slot> existing = regions.read(targetRegion);
                        for (var slotEntry : incoming.entrySet()) {
                            int slot = slotEntry.getKey();
                            ChunkPos pos = new ChunkPos(regionX * 32 + slot % 32, regionZ * 32 + slot / 32);
                            ChunkFacts sourceFacts = ChunkFacts.from(regions.chunk(slotEntry.getValue()),
                                    slotEntry.getValue().timestampSeconds());
                            if (sourceFacts.x() != pos.x() || sourceFacts.z() != pos.z()
                                    || sourceFacts.dataVersion() != export.dataVersion()) {
                                throw new IOException("source chunk coordinate/DataVersion mismatch at " + sourceRegion
                                        + " slot " + slot);
                            }
                            if (sourceFacts.captureTimeMillis() <= 0) {
                                throw new IOException("source chunk has no capture timestamp: " + sourceRegion + " slot " + slot);
                            }
                            RegionFileStore.Slot old = existing.get(slot);
                            boolean replacedEmpty = false;
                            if (old == null) {
                                missing++;
                            } else {
                                ChunkFacts oldFacts = ChunkFacts.from(regions.chunk(old), old.timestampSeconds());
                                if (oldFacts.noBlocks()) {
                                    empty++;
                                    replacedEmpty = true;
                                } else if (options.mode() == Mode.MIRROR && options.policy() == Policy.TIMESTAMP
                                        && eligible(index.get(pos), connection, sourceId, dimensionName,
                                                sourceFacts.captureTimeMillis())) {
                                    newer++;
                                } else {
                                    preserved++;
                                    continue;
                                }
                            }
                            decisions.computeIfAbsent(targetRegion, ignored -> new ArrayList<>()).add(
                                    new Decision(dimension, sourceRegion, targetRegion, slot, pos,
                                            sourceFacts.captureTimeMillis(), replacedEmpty));
                        }
                    }
                }
            }
            return new Plan(options, export, targetVersion, decisions, missing, empty, newer, preserved,
                    database, sourceId);
        } catch (SQLException failure) {
            throw new IOException("could not read mirror chunk index", failure);
        } finally {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        }
    }

    private boolean eligible(ChunkIndexStore.ChunkRecord old, Connection connection, String sourceId,
            String dimension, long incomingTime) throws SQLException {
        if (old == null || connection == null) return false; // unknown physical chunk stays untouched
        if (incomingTime <= old.updateTime()) return false;
        Integer oldPriority = null;
        try (PreparedStatement query = connection.prepareStatement("SELECT priority FROM update_sources "
                + "WHERE update_source=? AND (apply_to_source IS NULL OR apply_to_source=?) "
                + "AND (apply_to_dimension IS NULL OR apply_to_dimension=?)")) {
            query.setString(1, old.updateSource());
            query.setString(2, sourceId);
            query.setString(3, dimension);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) oldPriority = rows.getInt(1);
            }
        }
        if (oldPriority == null) return false; // unknown provenance is never an overwrite candidate
        return !ChunkUpdatePolicy.shouldSkip(old.updateTime(), oldPriority, incomingTime, 10);
    }

    private Map<ChunkPos, ChunkIndexStore.ChunkRecord> loadIndex(Connection connection,
            String sourceId, String dimension) throws SQLException {
        Map<ChunkPos, ChunkIndexStore.ChunkRecord> result = new HashMap<>();
        if (connection == null) return result;
        for (var record : new ChunkIndexStore(connection, sourceId).queryAll(dimension)) {
            result.put(new ChunkPos(record.x(), record.z()), record);
        }
        return result;
    }

    private ObjectNode mirrorMetadata(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("mirror metadata is missing: " + file);
        ObjectNode value = (ObjectNode) JSON.readTree(file.toFile());
        if (!MirrorFormat.FORMAT.equals(value.path("format").asText())
                || value.path("metadataSchema").asInt(-1) != MirrorFormat.METADATA_SCHEMA) {
            throw new IOException("unsupported mirror metadata format/schema in " + file);
        }
        return value;
    }

    public Path execute(Plan plan) throws IOException {
        if (plan.chunksToWrite() == 0) return null;
        Path destination = plan.options().destination().toAbsolutePath().normalize();
        Path lockPath = destination.resolve("session.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("destination is in use: " + destination);
            Path backup = destination.resolve("backups").resolve("toolkit-import-" + BACKUP_DATE.format(Instant.now()));
            Files.createDirectories(backup);
            for (Path target : plan.decisions().keySet()) backupIfPresent(destination, backup, target);
            if (plan.options().mode() == Mode.MIRROR) {
                backupIfPresent(destination, backup, plan.database());
                for (String suffix : List.of("-wal", "-shm", "-journal")) {
                    backupIfPresent(destination, backup,
                            plan.database().resolveSibling(plan.database().getFileName() + suffix));
                }
                backupIfPresent(destination, backup, MirrorSavePaths.at(destination).metadata());
            }
            Connection db = null;
            try {
                if (plan.options().mode() == Mode.MIRROR) {
                    Files.createDirectories(plan.database().getParent());
                    db = DriverManager.getConnection("jdbc:sqlite:" + plan.database());
                    ChunkIndexStore.initialize(db);
                    try (Statement sql = db.createStatement()) {
                        sql.execute("INSERT OR IGNORE INTO update_sources "
                                + "(update_source,priority,apply_to_source,apply_to_dimension) "
                                + "VALUES ('replay_import',10,NULL,NULL)");
                    }
                }
                long newestImported = 0;
                for (var regionEntry : plan.decisions().entrySet()) {
                    Path target = regionEntry.getKey();
                    Map<Integer, RegionFileStore.Slot> slots = regions.read(target);
                    Map<Path, Map<Integer, RegionFileStore.Slot>> sourceCache = new HashMap<>();
                    Map<String, Map<ChunkIndexStore.ChunkCoordinate, Long>> times = new HashMap<>();
                    Map<String, java.util.Set<ChunkIndexStore.ChunkCoordinate>> emptyReplacements = new HashMap<>();
                    for (Decision decision : regionEntry.getValue()) {
                        RegionFileStore.Slot incoming = sourceCache.computeIfAbsent(decision.sourceRegion(), path -> {
                            try { return regions.read(path); }
                            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }).get(decision.slot());
                        if (incoming == null) throw new IOException("source region changed: " + decision.sourceRegion());
                        slots.put(decision.slot(), incoming);
                        newestImported = Math.max(newestImported, decision.captureTime());
                        var coordinate = new ChunkIndexStore.ChunkCoordinate(decision.pos().x(), decision.pos().z());
                        times.computeIfAbsent(decision.dimension().value(), ignored -> new HashMap<>())
                                .put(coordinate, decision.captureTime());
                        if (decision.replacedEmpty()) {
                            emptyReplacements.computeIfAbsent(decision.dimension().value(), ignored -> new HashSet<>())
                                    .add(coordinate);
                        }
                    }
                    regions.write(target, slots);
                    if (db != null) {
                        for (var dimensionTimes : times.entrySet()) {
                            ChunkIndexStore index = new ChunkIndexStore(db, plan.sourceId());
                            index.removeUnreadableUpdates(dimensionTimes.getKey(),
                                    emptyReplacements.getOrDefault(dimensionTimes.getKey(), Set.of()));
                            index.recordUpdates(dimensionTimes.getKey(), dimensionTimes.getValue(), "replay_import");
                        }
                    }
                    System.out.println("imported " + target + " chunks=" + regionEntry.getValue().size());
                }
                if (db != null) updateMirrorLastSync(MirrorSavePaths.at(destination).metadata(), newestImported);
            } catch (SQLException | java.io.UncheckedIOException failure) {
                throw new IOException("import failed; restore from " + backup, failure);
            } finally {
                if (db != null) try { db.close(); } catch (SQLException ignored) { }
            }
            return backup;
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("destination is in use: " + destination, busy);
        }
    }

    private void backupIfPresent(Path root, Path backup, Path file) throws IOException {
        if (!Files.exists(file)) return;
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        Path target = backup.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void updateMirrorLastSync(Path metadataPath, long newestImported) throws IOException {
        ObjectNode metadata = mirrorMetadata(metadataPath);
        metadata.put("lastSyncTime", Math.max(metadata.path("lastSyncTime").asLong(0), newestImported));
        Path temporary = Files.createTempFile(metadataPath.getParent(), "worldmirror_meta-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), metadata);
            try {
                Files.move(temporary, metadataPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
