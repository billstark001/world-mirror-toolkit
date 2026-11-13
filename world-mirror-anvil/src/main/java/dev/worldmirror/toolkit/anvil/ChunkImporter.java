package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.MirrorSavePaths;
import dev.worldmirror.toolkit.core.WorldLayout;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkRecord;
import io.github.billstark001.worldmirror.format.ChunkUpdate;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Action;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.Destination;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.IndexStatus;
import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.WritePolicy;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.legacy.LegacyMirrorMetadata;
import io.github.billstark001.worldmirror.merge.RegionMerge;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
    private static final int GRAPH_METADATA_SCHEMA = 2;
    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    private final RegionFileStore regions = new RegionFileStore();

    public record Options(Path source, Path destination, Mode mode, Policy policy,
            String destinationLayout, boolean allowSourceMismatch) {}
    public record Routing(Path worldGraph, String replaySource) {
        public static final Routing AUTO = new Routing(null, null);
    }
    private record MirrorIdentity(String sourceId, String nodeId, String parentNodeId,
            String kind, String role) {}
    private record MirrorMetadata(ObjectNode document, MirrorIdentity identity, long lastSyncTime) {}
    private record Decision(Path sourceRegion, int slot, ChunkUpdate update, ChunkRecord previousIndex,
            String sourceDigest, String targetDigest, boolean replaceIndexClaim, boolean verifyOnly,
            boolean replaceUnindexed, MirrorIdentity mirror, ReplaySourceRouting.Evidence routing) {}
    private record PlanningPolicy(boolean mirror, WritePolicy writePolicy, boolean replaceUnindexed,
            int dataVersion, long lastSyncTime, MirrorIdentity identity, ReplaySourceRouting.Evidence routing) {}
    private record PlannedRegion(List<Decision> decisions, int missing, int empty, int newer, int preserved) {}
    private record StagedChunk(Decision decision, RegionFileStore.Slot expected) {}
    public record Plan(Options options, AnalysisExportMetadata sourceMetadata,
            WorldSaveVersion destinationVersion, Map<Path, List<Decision>> decisions,
            int missing, int emptyReplaced, int newerReplaced, int preserved,
            Path database, String sourceId) {
        public Plan {
            Map<Path, List<Decision>> copy = new LinkedHashMap<>();
            decisions.forEach((region, chunks) -> copy.put(region, List.copyOf(chunks)));
            decisions = Collections.unmodifiableMap(copy);
        }
        public int chunksToWrite() { return missing + emptyReplaced + newerReplaced + unindexedReplaced(); }
        public int unindexedReplaced() {
            return (int) decisions.values().stream().flatMap(List::stream).filter(Decision::replaceUnindexed).count();
        }
        public long chunksToVerifyOnly() {
            return decisions.values().stream().flatMap(List::stream).filter(Decision::verifyOnly).count();
        }
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
            if (options.mode() == Mode.MIRROR && !decisions.isEmpty()) {
                var routing = decisions.values().iterator().next().getFirst().routing();
                value.put("replay_source", routing.source());
            }
            value.put("version_mismatch", versionMismatch());
            value.put("missing", missing);
            value.put("empty_replaced", emptyReplaced);
            value.put("newer_replaced", newerReplaced);
            value.put("unindexed_replaced", unindexedReplaced());
            value.put("preserved", preserved);
            value.put("verify_only", chunksToVerifyOnly());
            value.put("regions_to_write", (int) decisions.values().stream()
                    .filter(chunks -> chunks.stream().anyMatch(chunk -> !chunk.verifyOnly())).count());
            return value;
        }
    }

    public Plan plan(Options options) throws IOException {
        return plan(options, false);
    }

    public Plan plan(Options options, boolean replaceUnindexed) throws IOException {
        return plan(options, replaceUnindexed, Routing.AUTO);
    }

    public Plan plan(Options options, boolean replaceUnindexed, Routing routing) throws IOException {
        return plan(options, replaceUnindexed, routing, options.destination().toRealPath());
    }

    private Plan plan(Options options, boolean replaceUnindexed, Routing routing, Path destination)
            throws IOException {
        if (options.mode() != Mode.MIRROR && !Routing.AUTO.equals(routing)) {
            throw new IOException("--world-graph and --replay-source require --mode mirror");
        }
        if (replaceUnindexed && (options.mode() != Mode.MIRROR || options.policy() != Policy.TIMESTAMP)) {
            throw new IOException("replacing unindexed chunks requires mirror mode with the timestamp policy");
        }
        Path source = options.source().toRealPath();
        if (source.equals(destination) || source.startsWith(destination) || destination.startsWith(source)) {
            throw new IOException("source and destination must be separate save trees");
        }
        requireRecoveredSave(destination);
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
        long lastSyncTime = 0;
        MirrorIdentity mirror = null;
        ReplaySourceRouting.Evidence sourceRouting = null;
        Path database = mirrorPaths.chunkIndex();
        if (options.mode() == Mode.MIRROR) {
            MirrorMetadata metadata = mirrorMetadata(mirrorPaths.metadata());
            mirror = metadata.identity();
            sourceId = mirror.sourceId();
            lastSyncTime = metadata.lastSyncTime();
            sourceRouting = ReplaySourceRouting.verify(destination, mirror.nodeId(), mirror.parentNodeId(),
                    export.sourceKey(), routing, options.allowSourceMismatch());
            requireIndexFiles(database);
        } else if (options.policy() == Policy.TIMESTAMP) {
            throw new IOException("timestamp policy requires --mode mirror and a chunk index");
        }

        try (Connection connection = options.mode() == Mode.MIRROR && Files.exists(database)
                ? openReadOnlyIndex(database) : null) {
            ChunkIndexStore index = connection == null ? null : new ChunkIndexStore(connection, sourceId);
            WritePolicy writePolicy = options.policy() == Policy.TIMESTAMP
                    ? WritePolicy.REPLACE_OLDER : WritePolicy.FILL_EMPTY;
            var policy = new PlanningPolicy(options.mode() == Mode.MIRROR, writePolicy, replaceUnindexed,
                    export.dataVersion(), lastSyncTime, mirror, sourceRouting);
            Map<Path, List<Decision>> decisions = new LinkedHashMap<>();
            Set<Path> sourceDirectories = new HashSet<>();
            Set<Path> targetDirectories = new HashSet<>();
            MessageDigest hasher = sha256();
            int missing = 0, empty = 0, newer = 0, preserved = 0;
            for (String dimensionName : new TreeSet<>(export.chunksByDimension().keySet())) {
                DimensionKey dimension = new DimensionKey(dimensionName);
                Path sourceDir = regionDirectory(source, sourceLayout, dimension);
                Path targetDir = regionDirectory(destination, targetLayout, dimension);
                if (!sourceDirectories.add(sourceDir) || !targetDirectories.add(targetDir)) {
                    throw new IOException("multiple dimensions resolve to the same region directory: " + dimensionName);
                }
                if (!Files.isDirectory(sourceDir)) throw new IOException("missing source region directory " + sourceDir);
                Map<String, Integer> priorities = index != null && writePolicy == WritePolicy.REPLACE_OLDER
                        ? index.sourcePriorities(dimensionName) : Map.of();
                try (var files = Files.list(sourceDir)) {
                    for (Path sourceRegion : files.filter(Files::isRegularFile).sorted().toList()) {
                        Path targetRegion = targetDir.resolve(sourceRegion.getFileName());
                        PlannedRegion region = planRegion(sourceRegion, targetRegion, dimensionName,
                                policy, index, priorities, hasher);
                        if (region == null) continue;
                        if (!region.decisions().isEmpty()) decisions.put(targetRegion, region.decisions());
                        missing += region.missing();
                        empty += region.empty();
                        newer += region.newer();
                        preserved += region.preserved();
                    }
                }
            }
            return new Plan(options, export, targetVersion, decisions, missing, empty, newer, preserved,
                    database, sourceId);
        } catch (SQLException failure) {
            throw new IOException("could not read mirror chunk index", failure);
        }
    }

    private PlannedRegion planRegion(Path sourceRegion, Path targetRegion, String dimensionName,
            PlanningPolicy policy, ChunkIndexStore index, Map<String, Integer> priorities, MessageDigest hasher)
            throws IOException, SQLException {
        Matcher name = REGION.matcher(sourceRegion.getFileName().toString());
        if (!name.matches()) return null;
        int regionX = regionCoordinate(name.group(1), sourceRegion);
        int regionZ = regionCoordinate(name.group(2), sourceRegion);
        requirePlainFile(sourceRegion);
        requirePlainFile(targetRegion);
        List<Decision> decisions = new ArrayList<>();
        int missing = 0, empty = 0, newer = 0, preserved = 0;
        Map<Integer, RegionFileStore.Slot> incoming = regions.read(sourceRegion);
        Map<Integer, RegionFileStore.Slot> existing = regions.read(targetRegion);
        Map<ChunkCoordinate, ChunkRecord> indexed = index == null ? Map.of()
                : indexedRegion(index, dimensionName, regionX, regionZ);
        for (var slotEntry : incoming.entrySet()) {
            int slot = slotEntry.getKey();
            var coordinate = new ChunkCoordinate(
                    regionX * 32 + slot % 32, regionZ * 32 + slot / 32);
            RegionFileStore.Slot next = slotEntry.getValue();
            ChunkFacts sourceFacts = ChunkFacts.from(regions.chunk(next), next.timestampSeconds());
            if (sourceFacts.x() != coordinate.x() || sourceFacts.z() != coordinate.z()
                    || sourceFacts.dataVersion() != policy.dataVersion()) {
                throw new IOException("source chunk coordinate/DataVersion mismatch at " + sourceRegion
                        + " slot " + slot);
            }
            if (sourceFacts.captureTimeMillis() <= 0) {
                throw new IOException("source chunk has no capture timestamp: " + sourceRegion + " slot " + slot);
            }
            var update = new ChunkUpdate(dimensionName, coordinate, sourceFacts.captureTimeMillis(),
                    ChunkIndexStore.REPLAY_IMPORT);
            RegionFileStore.Slot old = existing.get(slot);
            boolean identical = matches(old, next);
            Destination state = Destination.MISSING;
            if (old != null) {
                ChunkFacts oldFacts = identical ? sourceFacts
                        : ChunkFacts.from(regions.chunk(old), old.timestampSeconds());
                state = oldFacts.noBlocks() ? Destination.EMPTY : Destination.PRESENT;
            }
            ChunkRecord record = indexed.get(coordinate);
            IndexStatus status = state == Destination.PRESENT && policy.writePolicy() == WritePolicy.REPLACE_OLDER
                    ? ChunkUpdatePolicy.assess(update, record, priorities) : IndexStatus.UNINDEXED;
            Action action = ChunkUpdatePolicy.decide(policy.writePolicy(), state, status);
            boolean alreadyIndexed = record != null && record.updateTime() == update.captureTime()
                    && update.updateSource().equals(record.updateSource());
            boolean needsReceipt = alreadyIndexed ? policy.lastSyncTime() < update.captureTime()
                    : record == null || action == Action.WRITE;
            if (identical && policy.mirror() && needsReceipt) {
                // Recover an interrupted index or metadata commit without rewriting the MCA.
                String proof = fingerprint(hasher, next);
                decisions.add(new Decision(sourceRegion, slot, update, record, proof, proof,
                        !alreadyIndexed && state != Destination.PRESENT, true, false, policy.identity(), policy.routing()));
                continue;
            }
            boolean overwriteUnindexed = state == Destination.PRESENT && record == null && policy.replaceUnindexed();
            if (identical || (action != Action.WRITE && !overwriteUnindexed)) {
                preserved++;
                continue;
            }
            switch (state) {
                case MISSING -> missing++;
                case EMPTY -> empty++;
                case PRESENT -> {
                    if (!overwriteUnindexed) newer++;
                }
            }
            decisions.add(new Decision(sourceRegion, slot, update, record, fingerprint(hasher, next),
                    fingerprint(hasher, old), state != Destination.PRESENT, false, overwriteUnindexed, policy.identity(),
                    policy.routing()));
        }
        return new PlannedRegion(decisions, missing, empty, newer, preserved);
    }

    private static Path regionDirectory(Path world, WorldLayout layout, DimensionKey dimension) throws IOException {
        Path path = layout.dimensionPath(world, dimension).resolve("region").normalize();
        if (!path.startsWith(world)) throw new IOException("region directory leaves its save: " + path);
        for (Path part = path; !part.equals(world); part = part.getParent()) {
            requirePlainDirectory(part);
        }
        return path;
    }

    private static int regionCoordinate(String value, Path file) throws IOException {
        try {
            int coordinate = Integer.parseInt(value);
            Math.multiplyExact(coordinate, 32);
            if (!Integer.toString(coordinate).equals(value)) {
                throw new IOException("noncanonical region filename: " + file);
            }
            return coordinate;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("region coordinate outside the chunk index: " + file, invalid);
        }
    }

    private static Map<ChunkCoordinate, ChunkRecord> indexedRegion(ChunkIndexStore index,
            String dimension, int regionX, int regionZ) throws SQLException {
        Map<ChunkCoordinate, ChunkRecord> result = new HashMap<>();
        for (ChunkRecord chunk : index.queryRegion(dimension, regionX, regionZ)) {
            result.put(new ChunkCoordinate(chunk.x(), chunk.z()), chunk);
        }
        return result;
    }

    private static Connection openReadOnlyIndex(Path database) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + database, readOnly.toProperties());
    }

    private static void requireRecoveredSave(Path destination) throws IOException {
        if (!Files.notExists(destination.resolve(".worldmirror-recovery-required"), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(destination.resolve(".worldmirror-relocation.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("destination requires recovery in World Mirror: " + destination);
        }
        try (var journals = Files.newDirectoryStream(destination, ".worldmirror-transaction-*")) {
            if (journals.iterator().hasNext()) {
                throw new IOException("destination requires recovery in World Mirror: " + destination);
            }
        }
    }

    static void requirePlainDirectory(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // NTFS junctions can report both isDirectory and isOther.
            if (!attributes.isDirectory() || attributes.isOther() || attributes.isSymbolicLink()) {
                throw new IOException("not a plain directory: " + path);
            }
        } catch (NoSuchFileException missing) {
            // The import may create a missing directory.
        }
    }

    static void requirePlainFile(Path path) throws IOException {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file: " + path);
        }
    }

    private static void requireIndexFiles(Path database) throws IOException {
        requirePlainDirectory(database.getParent());
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            requirePlainFile(database.resolveSibling(database.getFileName() + suffix));
        }
    }

    private static MirrorMetadata mirrorMetadata(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mirror metadata is missing or is not a regular file: " + file);
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        }
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("mirror metadata exceeds size limit: " + file);
        if (!(JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes) instanceof ObjectNode value)
                || !MirrorFormat.FORMAT.equals(value.path("format").textValue())) {
            throw new IOException("unsupported mirror metadata format/schema in " + file);
        }
        var schema = value.path("metadataSchema");
        if (LegacyMirrorMetadata.requiresMigration(schema)) {
            throw new IOException("upgrade the mirror metadata in World Mirror before importing: " + file);
        }
        if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != GRAPH_METADATA_SCHEMA) {
            throw new IOException("unsupported mirror metadata format/schema in " + file);
        }
        String sourceId = textField(value, "sourceId");
        if (sourceId == null) throw new IOException("mirror metadata lacks sourceId: " + file);
        var sync = value.path("lastSyncTime");
        if (!sync.isIntegralNumber() || !sync.canConvertToLong() || sync.longValue() < 0) {
            throw new IOException("invalid mirror lastSyncTime: " + file);
        }
        String nodeId = textField(value, "nodeId");
        String parent = textField(value, "parentNodeId");
        String kind = textField(value, "kind");
        String role = textField(value, "role");
        boolean mirror = "MIRROR".equals(kind) && typedId(nodeId, "mirror:")
                && List.of("mirror:", "snapshot:", "local:", "remote:", "unresolved:").stream()
                        .anyMatch(prefix -> typedId(parent, prefix))
                && ("SYNC".equals(role) || "SNAPSHOT".equals(role));
        boolean snapshot = "SNAPSHOT".equals(kind) && typedId(nodeId, "snapshot:") && "SNAPSHOT".equals(role)
                && (!value.has("parentNodeId") || value.path("parentNodeId").isNull());
        if (!mirror && !snapshot) throw new IOException("invalid mirror graph identity: " + file);
        return new MirrorMetadata(value, new MirrorIdentity(sourceId, nodeId, parent, kind, role), sync.longValue());
    }

    private static String textField(ObjectNode document, String name) {
        String value = document.path(name).textValue();
        return value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl) ? null : value;
    }

    private static boolean typedId(String id, String prefix) {
        return id != null && id.startsWith(prefix) && !id.substring(prefix.length()).isBlank();
    }

    /** Execute with a retained backup of the affected save files. */
    public Path execute(Plan preview) throws IOException {
        return execute(preview, true);
    }

    /** Execute under the save lock, optionally retaining a backup. */
    public Path execute(Plan preview, boolean keepBackup) throws IOException {
        if (preview.decisions().isEmpty()) return null;
        Path destination = preview.options().destination().toRealPath();
        if (!MirrorSavePaths.at(destination).chunkIndex().equals(preview.database())) {
            throw new IOException("import destination changed before the save was locked; review a fresh plan");
        }
        Routing routing = Routing.AUTO;
        if (preview.options().mode() == Mode.MIRROR) {
            // Reject stale metadata before creating session.lock. The plan is still rechecked under the lock.
            Decision first = preview.decisions().values().iterator().next().getFirst();
            routing = first.routing().request();
            if (!Objects.equals(first.mirror(), mirrorMetadata(MirrorSavePaths.at(destination).metadata()).identity())) {
                throw new IOException("import plan changed before the save was locked; review a fresh plan");
            }
        }
        Path lockPath = destination.resolve("session.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("destination is in use: " + destination);
            // A plan authorizes replacing unindexed chunks only when its preview included such writes.
            Plan plan = plan(preview.options(), preview.unindexedReplaced() > 0, routing, destination);
            if (!plan.equals(preview)) {
                throw new IOException("import plan changed before the save was locked; review a fresh plan");
            }
            Backup backup = keepBackup ? createBackup(plan, destination) : null;
            try (Connection db = openIndex(plan)) {
                ChunkIndexStore index = db == null ? null : new ChunkIndexStore(db, plan.sourceId());
                long newestImported = 0;
                for (var regionEntry : plan.decisions().entrySet()) {
                    Path target = regionEntry.getKey();
                    List<Decision> decisions = regionEntry.getValue();
                    boolean replace = willWrite(decisions);
                    try (var original = originalRegion(target, replace, backup)) {
                        var result = RegionMerge.writeVerified(
                                () -> writeRegion(target, decisions),
                                expected -> verifyRegion(target, expected, plan.sourceMetadata().dataVersion(),
                                        replace, original.path()));
                        if (index != null) {
                            try {
                                newestImported = Math.max(newestImported, commit(index, result.written()));
                            } catch (SQLException | RuntimeException failure) {
                                recoverFailedCommit(db, plan, target, original.path(), failure);
                                throw failure;
                            }
                        }
                        System.out.println("imported " + target + " chunks=" + result.written().size());
                    }
                }
                if (index != null) {
                    MirrorIdentity mirror = plan.decisions().values().iterator().next().getFirst().mirror();
                    updateMirrorLastSync(MirrorSavePaths.at(destination).metadata(), mirror, newestImported);
                }
            } catch (SQLException | IOException | RuntimeException failure) {
                String detail = backup == null ? "import failed: " : "import failed; backup at " + backup.path() + ": ";
                throw new IOException(detail + failure.getMessage(), failure);
            }
            return backup == null ? null : backup.path();
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("destination is in use: " + destination, busy);
        }
    }

    private record Backup(Path path, Map<Path, Path> regions) {}

    private record OriginalRegion(Path path, boolean temporary) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (temporary) Files.deleteIfExists(path);
        }
    }

    private static Backup createBackup(Plan plan, Path destination) throws IOException {
        Path backups = destination.resolve("backups");
        requirePlainDirectory(backups);
        Files.createDirectories(backups);
        Path path = Files.createTempDirectory(backups, "toolkit-import-" + BACKUP_DATE.format(Instant.now()) + "-");
        Map<Path, Path> originals = new HashMap<>();
        for (var entry : plan.decisions().entrySet()) {
            Path region = entry.getKey();
            if (willWrite(entry.getValue()) && backupIfPresent(destination, path, region)) {
                originals.put(region, path.resolve(destination.relativize(region)));
            }
        }
        if (plan.options().mode() == Mode.MIRROR) {
            backupIfPresent(destination, path, plan.database());
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                backupIfPresent(destination, path,
                        plan.database().resolveSibling(plan.database().getFileName() + suffix));
            }
            backupIfPresent(destination, path, MirrorSavePaths.at(destination).metadata());
        }
        return new Backup(path, originals);
    }

    private static OriginalRegion originalRegion(Path target, boolean replace, Backup backup) throws IOException {
        if (backup != null) return new OriginalRegion(backup.regions().get(target), false);
        if (!replace || Files.notExists(target)) return new OriginalRegion(null, false);
        // Keep only the current region's rollback file until its verified write and index commit finish.
        Path temporary = Files.createTempFile(target.getParent(), ".toolkit-region-original-", ".tmp");
        try {
            Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return new OriginalRegion(temporary, true);
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void recoverFailedCommit(Connection db, Plan plan, Path target, Path original, Exception failure) {
        try {
            db.close();
        } catch (SQLException close) {
            failure.addSuppressed(close);
            return;
        }
        restoreIfIndexUnchanged(plan, target, original, failure);
    }

    private Connection openIndex(Plan plan) throws IOException, SQLException {
        if (plan.options().mode() != Mode.MIRROR) return null;
        requireIndexFiles(plan.database());
        Files.createDirectories(plan.database().getParent());
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + plan.database());
        try {
            ChunkIndexStore.initialize(db);
            return db;
        } catch (SQLException | RuntimeException failure) {
            try { db.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private Map<Integer, StagedChunk> writeRegion(Path target, List<Decision> decisions) throws IOException {
        requirePlainFile(target);
        requirePlainFile(decisions.getFirst().sourceRegion());
        Map<Integer, RegionFileStore.Slot> slots = regions.read(target);
        Map<Integer, RegionFileStore.Slot> source = regions.read(decisions.getFirst().sourceRegion());
        Map<Integer, StagedChunk> staged = new LinkedHashMap<>();
        MessageDigest hasher = sha256();
        for (Decision decision : decisions) {
            RegionFileStore.Slot incoming = source.get(decision.slot());
            if (incoming == null || !decision.sourceDigest().equals(fingerprint(hasher, incoming))) {
                throw new IOException("source region changed: " + decision.sourceRegion() + " slot " + decision.slot());
            }
            if (!Objects.equals(decision.targetDigest(), fingerprint(hasher, slots.get(decision.slot())))) {
                throw new IOException("destination region changed: " + target + " slot " + decision.slot());
            }
            if (!decision.verifyOnly()) slots.put(decision.slot(), incoming);
            staged.put(decision.slot(), new StagedChunk(decision, incoming));
        }
        if (willWrite(decisions)) regions.write(target, slots);
        return staged;
    }

    private static boolean willWrite(List<Decision> decisions) {
        return decisions.stream().anyMatch(decision -> !decision.verifyOnly());
    }

    private RegionMerge.Verification<Integer> verifyRegion(Path target, Map<Integer, StagedChunk> expected,
            int dataVersion, boolean replaced, Path original) throws IOException {
        try {
            Map<Integer, RegionFileStore.Slot> slots = regions.read(target);
            for (var entry : expected.entrySet()) {
                RegionFileStore.Slot actual = slots.get(entry.getKey());
                if (!matches(actual, entry.getValue().expected())) {
                    throw new IOException("region read-back differs: " + target + " slot " + entry.getKey());
                }
                ChunkFacts facts = ChunkFacts.from(regions.chunk(actual), actual.timestampSeconds());
                ChunkUpdate update = entry.getValue().decision().update();
                if (facts.x() != update.coordinate().x() || facts.z() != update.coordinate().z()
                        || facts.dataVersion() != dataVersion || facts.captureTimeMillis() != update.captureTime()) {
                    throw new IOException("region read-back has unexpected chunk data: " + target + " slot " + entry.getKey());
                }
            }
            return new RegionMerge.Verification<>(true, expected.keySet(), Set.of());
        } catch (IOException failure) {
            if (replaced) {
                try { regions.restore(target, original); }
                catch (IOException recovery) { failure.addSuppressed(recovery); }
            }
            throw failure;
        }
    }

    private void restoreIfIndexUnchanged(Plan plan, Path target, Path original, Exception failure) {
        List<Decision> decisions = plan.decisions().get(target);
        if (decisions.stream().noneMatch(chunk -> !chunk.verifyOnly() && chunk.previousIndex() != null)) return;

        // The writer connection is closed before reading committed rows through a separate connection.
        ChunkUpdate first = decisions.getFirst().update();
        try (Connection db = openReadOnlyIndex(plan.database())) {
            var index = new ChunkIndexStore(db, plan.sourceId());
            Map<ChunkCoordinate, ChunkRecord> current = indexedRegion(index, first.dimension(),
                    first.coordinate().x() >> 5, first.coordinate().z() >> 5);
            if (decisions.stream().allMatch(chunk -> Objects.equals(
                    chunk.previousIndex(), current.get(chunk.update().coordinate())))) {
                regions.restore(target, original);
            }
        } catch (SQLException | IOException recovery) {
            failure.addSuppressed(recovery);
        }
    }

    private static long commit(ChunkIndexStore index, Map<Integer, StagedChunk> written) throws SQLException {
        String dimension = written.values().iterator().next().decision().update().dimension();
        Map<ChunkCoordinate, Long> times = new HashMap<>();
        Set<ChunkCoordinate> replacements = new HashSet<>();
        long newest = 0;
        for (StagedChunk chunk : written.values()) {
            Decision decision = chunk.decision();
            ChunkUpdate update = decision.update();
            times.put(update.coordinate(), update.captureTime());
            if (decision.replaceIndexClaim()) replacements.add(update.coordinate());
            newest = Math.max(newest, update.captureTime());
        }
        index.recordUpdates(dimension, times, ChunkIndexStore.REPLAY_IMPORT, replacements);
        return newest;
    }

    private static boolean matches(RegionFileStore.Slot actual, RegionFileStore.Slot expected) {
        return actual != null && actual.timestampSeconds() == expected.timestampSeconds()
                && Arrays.equals(actual.payload(), expected.payload());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String fingerprint(MessageDigest hasher, RegionFileStore.Slot slot) {
        if (slot == null) return null;
        int timestamp = slot.timestampSeconds();
        for (int shift = 24; shift >= 0; shift -= 8) hasher.update((byte) (timestamp >>> shift));
        return HexFormat.of().formatHex(hasher.digest(slot.payload()));
    }

    private static boolean backupIfPresent(Path root, Path backup, Path file) throws IOException {
        if (Files.notExists(file)) return false;
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        Path target = backup.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
    }

    private static void updateMirrorLastSync(Path metadataPath, MirrorIdentity expected, long newestImported)
            throws IOException {
        MirrorMetadata read = mirrorMetadata(metadataPath);
        if (!expected.equals(read.identity())) throw new IOException("mirror identity changed during import: " + metadataPath);
        ObjectNode metadata = read.document();
        metadata.put("lastSyncTime", Math.max(read.lastSyncTime(), newestImported));
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
