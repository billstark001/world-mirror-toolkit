package dev.worldmirror.toolkit.anvil;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Mode;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Policy;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Options;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Routing;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Plan;
import dev.worldmirror.toolkit.anvil.ChunkImportPlanner.Decision;
import dev.worldmirror.toolkit.anvil.MirrorImportMetadata.Identity;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requirePlainFile;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requireRecoveredSave;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requireIndexFiles;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.regionDirectory;
import static dev.worldmirror.toolkit.anvil.ImportRegion.matches;
import static dev.worldmirror.toolkit.anvil.ImportRegion.sha256;
import static dev.worldmirror.toolkit.anvil.ImportRegion.fingerprint;
import static dev.worldmirror.toolkit.anvil.MirrorImportIndex.indexedRegion;

/** Reads and validates the preview under the same policy used when rechecking it for execution. */
final class ChunkImportPlanner {
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private final RegionFileStore regions = new RegionFileStore();
    private final ImportRegion importRegion = new ImportRegion(regions);

    record Decision(Path sourceRegion, int slot, ChunkUpdate update, ChunkRecord previousIndex,
            String sourceDigest, String targetDigest, boolean replaceIndexClaim, boolean verifyOnly,
            boolean replaceUnindexed, boolean seedReplacement, boolean receivedCapture, boolean seeded,
            String blendEpoch, boolean retainBlend, Identity mirror, ReplaySourceRouting.Evidence routing) {
        Decision withoutBlend() {
            return new Decision(sourceRegion, slot, update, previousIndex, sourceDigest, targetDigest,
                    replaceIndexClaim, verifyOnly, replaceUnindexed, seedReplacement, receivedCapture, seeded,
                    null, false, mirror, routing);
        }
    }
    private record PlanningPolicy(boolean mirror, WritePolicy writePolicy, boolean replaceUnindexed,
            int dataVersion, long lastSyncTime, Identity identity, ReplaySourceRouting.Evidence routing) {}
    private record PlannedRegion(List<Decision> decisions, int missing, int empty, int newer, int preserved) {
    }

    private static final class PlanSummary {
        final Map<Path, List<Decision>> decisions = new LinkedHashMap<>();
        int missing;
        int empty;
        int newer;
        int preserved;

        void add(Path target, PlannedRegion region) {
            if (region == null)
                return;
            if (!region.decisions().isEmpty())
                decisions.put(target, region.decisions());
            missing += region.missing();
            empty += region.empty();
            newer += region.newer();
            preserved += region.preserved();
        }

        void add(PlanSummary dimension) {
            decisions.putAll(dimension.decisions);
            missing += dimension.missing;
            empty += dimension.empty;
            newer += dimension.newer;
            preserved += dimension.preserved;
        }
    }

    Plan plan(Options options, boolean replaceUnindexed, Routing routing, Path destination,
            boolean saveLocked) throws IOException {
        if (options.mode() != Mode.MIRROR && !Routing.AUTO.equals(routing)) {
            throw new IOException("--world-graph and --replay-source require --mode mirror");
        }
        if (replaceUnindexed && (options.mode() != Mode.MIRROR || options.policy() != Policy.TIMESTAMP)) {
            throw new IOException(
                    "replacing unindexed chunks requires mirror mode with the timestamp policy");
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
        WorldLayout targetLayout =
                WorldLayout.parse(options.destinationLayout(), targetVersion.minecraftVersion());
        MirrorSavePaths mirrorPaths = MirrorSavePaths.at(destination);
        String sourceId = null;
        long lastSyncTime = 0;
        Identity mirror = null;
        ReplaySourceRouting.Evidence sourceRouting = null;
        boolean seeded = false;
        String blendEpoch = null;
        Path database = mirrorPaths.chunkIndex();
        if (options.mode() == Mode.MIRROR) {
            MirrorImportMetadata metadata = MirrorImportMetadata.read(mirrorPaths.metadata());
            mirror = metadata.identity();
            sourceId = mirror.sourceId();
            lastSyncTime = metadata.lastSyncTime();
            seeded = metadata.seeded();
            blendEpoch = metadata.blendEpoch(mirrorPaths.metadata());
            sourceRouting = ReplaySourceRouting.verify(destination, mirror.nodeId(), mirror.parentNodeId(),
                    export.sourceKey(), routing, options.allowSourceMismatch());
            requireIndexFiles(database);
        } else if (options.policy() == Policy.TIMESTAMP) {
            throw new IOException("timestamp policy requires --mode mirror and a chunk index");
        }

        try (var snapshot = MirrorImportIndex.Snapshot.open(
                     options.mode() == Mode.MIRROR ? database : null, saveLocked)) {
            Connection connection = snapshot.connection;
            ChunkIndexStore index = connection == null ? null : new ChunkIndexStore(connection, sourceId);
            WritePolicy writePolicy =
                    options.policy() == Policy.TIMESTAMP ? WritePolicy.REPLACE_OLDER : WritePolicy.FILL_EMPTY;
            if (index != null && writePolicy == WritePolicy.REPLACE_OLDER
                    && (!index.hasRegisteredSource(ChunkIndexStore.REPLAY_IMPORT)
                            || !index.hasRegisteredSource(ChunkIndexStore.SEED_GENERATED))) {
                throw new IOException(
                        "upgrade the chunk index in World Mirror before a timestamp import: " + database);
            }
            var policy = new PlanningPolicy(options.mode() == Mode.MIRROR, writePolicy, replaceUnindexed,
                    export.dataVersion(), lastSyncTime, mirror, sourceRouting);
            PlanSummary summary = new PlanSummary();
            Set<Path> sourceDirectories = new HashSet<>();
            Set<Path> targetDirectories = new HashSet<>();
            MessageDigest hasher = sha256();
            for (String dimensionName : new TreeSet<>(export.chunksByDimension().keySet())) {
                DimensionKey dimension = new DimensionKey(dimensionName);
                Path sourceDir = regionDirectory(source, sourceLayout, dimension);
                Path targetDir = regionDirectory(destination, targetLayout, dimension);
                if (!sourceDirectories.add(sourceDir) || !targetDirectories.add(targetDir)) {
                    throw new IOException(
                            "multiple dimensions resolve to the same region directory: " + dimensionName);
                }
                if (!Files.isDirectory(sourceDir))
                    throw new IOException("missing source region directory " + sourceDir);
                Map<String, Integer> priorities = index != null && writePolicy == WritePolicy.REPLACE_OLDER
                        ? index.sourcePriorities(dimensionName)
                        : Map.of();
                String dimensionBlend = "minecraft:overworld".equals(dimensionName) ? blendEpoch : null;
                PlanSummary dimensionSummary = new PlanSummary();
                try (var files = Files.list(sourceDir)) {
                    for (Path sourceRegion : files.filter(Files::isRegularFile).sorted().toList()) {
                        Path targetRegion = targetDir.resolve(sourceRegion.getFileName());
                        dimensionSummary.add(targetRegion,
                                planRegion(sourceRegion, targetRegion, dimensionName, policy, index,
                                        priorities, hasher, seeded, dimensionBlend));
                    }
                }
                if (dimensionBlend != null)
                    restrictBlendToBorders(targetDir, dimensionSummary.decisions);
                summary.add(dimensionSummary);
            }
            return new Plan(options, export, targetVersion, summary.decisions, summary.missing, summary.empty,
                    summary.newer, summary.preserved, database, sourceId);
        } catch (SQLException failure) {
            throw new IOException("could not read mirror chunk index", failure);
        }
    }

    private PlannedRegion planRegion(Path sourceRegion, Path targetRegion, String dimensionName,
            PlanningPolicy policy, ChunkIndexStore index, Map<String, Integer> priorities,
            MessageDigest hasher, boolean seeded, String blendEpoch) throws IOException, SQLException {
        Matcher name = REGION.matcher(sourceRegion.getFileName().toString());
        if (!name.matches())
            return null;
        int regionX = regionCoordinate(name.group(1), sourceRegion);
        int regionZ = regionCoordinate(name.group(2), sourceRegion);
        requirePlainFile(sourceRegion);
        requirePlainFile(targetRegion);
        List<Decision> decisions = new ArrayList<>();
        int missing = 0, empty = 0, newer = 0, preserved = 0;
        Map<Integer, RegionFileStore.Slot> incoming = regions.read(sourceRegion);
        Map<Integer, RegionFileStore.Slot> existing = regions.read(targetRegion);
        Map<ChunkCoordinate, ChunkRecord> indexed =
                index == null ? Map.of() : indexedRegion(index, dimensionName, regionX, regionZ);
        Set<Integer> auxiliary = null;
        for (var slotEntry : incoming.entrySet()) {
            int slot = slotEntry.getKey();
            var coordinate = new ChunkCoordinate(regionX * 32 + slot % 32, regionZ * 32 + slot / 32);
            RegionFileStore.Slot next = slotEntry.getValue();
            var sourceChunk = regions.chunk(next);
            ChunkFacts sourceFacts = ChunkFacts.from(sourceChunk, next.timestampSeconds());
            if (sourceFacts.x() != coordinate.x() || sourceFacts.z() != coordinate.z()
                    || sourceFacts.dataVersion() != policy.dataVersion()) {
                throw new IOException(
                        "source chunk coordinate/DataVersion mismatch at " + sourceRegion + " slot " + slot);
            }
            if (sourceFacts.captureTimeMillis() <= 0) {
                throw new IOException(
                        "source chunk has no capture timestamp: " + sourceRegion + " slot " + slot);
            }
            var update = new ChunkUpdate(dimensionName, coordinate, sourceFacts.captureTimeMillis(),
                    ChunkIndexStore.REPLAY_IMPORT);
            RegionFileStore.Slot old = existing.get(slot);
            boolean identical = matches(old, next);
            Destination state = Destination.MISSING;
            NbtValue.CompoundValue oldChunk = null;
            if (old != null) {
                oldChunk = identical ? sourceChunk : regions.chunk(old);
                ChunkFacts oldFacts =
                        identical ? sourceFacts : ChunkFacts.from(oldChunk, old.timestampSeconds());
                state = oldFacts.noBlocks() ? Destination.EMPTY : Destination.PRESENT;
            }
            ChunkRecord record = indexed.get(coordinate);
            boolean timestamp = policy.writePolicy() == WritePolicy.REPLACE_OLDER;
            boolean seedRow = record != null && ChunkIndexStore.SEED_GENERATED.equals(record.updateSource());
            boolean alreadyIndexed = record != null && record.updateTime() == update.captureTime()
                    && update.updateSource().equals(record.updateSource());
            if (policy.mirror() && SeedTerrainEvidence.hasCaptureReceipt(oldChunk)
                    && (seedRow || record == null || alreadyIndexed)
                    && (!seedRow || record.updateTime() == ChunkIndexStore.SEED_GENERATED_TIME)
                    && SeedTerrainEvidence.matchesReplayReceipt(oldChunk, policy.identity().nodeId(),
                            update.captureTime(), sourceChunk, seeded ? dimensionName : null)) {
                if (!alreadyIndexed || policy.lastSyncTime() < update.captureTime()) {
                    decisions.add(new Decision(sourceRegion, slot, update, record, fingerprint(hasher, next),
                            fingerprint(hasher, old), false, true, false, false, true, seeded, null, false,
                            policy.identity(), policy.routing()));
                } else {
                    preserved++;
                }
                continue;
            }
            boolean seedTerrain = seedRow || SeedTerrainEvidence.hasReceipt(oldChunk);
            boolean seedReplacement = false;
            IndexStatus status = IndexStatus.UNINDEXED;
            if (policy.mirror() && old != null && timestamp && record == null
                    && SeedTerrainEvidence.hasCaptureReceipt(oldChunk)) {
                preserved++;
                continue;
            }
            if (policy.mirror() && old != null && seedTerrain && (timestamp || state == Destination.EMPTY)) {
                if (auxiliary == null)
                    auxiliary = importRegion.auxiliarySlots(targetRegion);
                if (auxiliary.contains(slot)) {
                    preserved++;
                    continue;
                }
            }
            if (old != null && timestamp && seedTerrain && (seedRow || record == null)) {
                var expectedSeed = new ChunkRecord(coordinate.x(), coordinate.z(),
                        ChunkIndexStore.SEED_GENERATED_TIME, ChunkIndexStore.SEED_GENERATED);
                if (seedRow && record.updateTime() != expectedSeed.updateTime()
                        || !SeedTerrainEvidence.isUnmodified(oldChunk)
                        || ChunkUpdatePolicy.assess(update, expectedSeed, priorities)
                                != IndexStatus.ELIGIBLE) {
                    preserved++;
                    continue;
                }
                seedReplacement = true;
                status = IndexStatus.ELIGIBLE;
            } else if (state == Destination.PRESENT && timestamp) {
                status = ChunkUpdatePolicy.assess(update, record, priorities);
            }
            Action action = ChunkUpdatePolicy.decide(policy.writePolicy(), state, status);
            boolean needsReceipt = alreadyIndexed ? policy.lastSyncTime() < update.captureTime()
                                                  : record == null || action == Action.WRITE;
            if (identical && policy.mirror() && needsReceipt) {
                // Recover an interrupted index or metadata commit without rewriting the MCA.
                String proof = fingerprint(hasher, next);
                decisions.add(new Decision(sourceRegion, slot, update, record, proof, proof,
                        !alreadyIndexed && state != Destination.PRESENT, true, false, false, false, seeded,
                        null, false, policy.identity(), policy.routing()));
                continue;
            }
            boolean overwriteUnindexed = state == Destination.PRESENT && record == null
                    && policy.replaceUnindexed() && !seedTerrain
                    && !SeedTerrainEvidence.hasCaptureReceipt(oldChunk);
            if (identical || (action != Action.WRITE && !overwriteUnindexed)) {
                preserved++;
                continue;
            }
            switch (state) {
                case MISSING -> missing++;
                case EMPTY -> empty++;
                case PRESENT -> {
                    if (!overwriteUnindexed)
                        newer++;
                }
            }
            decisions.add(new Decision(sourceRegion, slot, update, record, fingerprint(hasher, next),
                    fingerprint(hasher, old), state != Destination.PRESENT, false, overwriteUnindexed,
                    seedReplacement || policy.mirror() && old != null && seedTerrain, false, seeded,
                    blendEpoch != null && SeedTerrainEvidence.canBlendReplay(sourceChunk) ? blendEpoch : null,
                    SeedTerrainEvidence.ownsBlend(oldChunk, blendEpoch), policy.identity(),
                    policy.routing()));
        }
        return new PlannedRegion(decisions, missing, empty, newer, preserved);
    }

    private void restrictBlendToBorders(Path directory, Map<Path, List<Decision>> planned) {
        Set<ChunkCoordinate> written = new HashSet<>();
        planned.values().forEach(chunks
                -> chunks.stream()
                        .filter(chunk -> !chunk.verifyOnly())
                        .forEach(chunk -> written.add(chunk.update().coordinate())));
        Map<Path, BitSet> occupancy = new HashMap<>();
        planned.replaceAll((path, chunks)
                                   -> chunks.stream()
                                           .map(chunk -> {
                                               if (chunk.blendEpoch() == null || chunk.retainBlend()
                                                       || bordersHole(directory, chunk.update().coordinate(),
                                                               written, occupancy)) {
                                                   return chunk;
                                               }
                                               return chunk.withoutBlend();
                                           })
                                           .toList());
    }

    private boolean bordersHole(Path directory, ChunkCoordinate coordinate, Set<ChunkCoordinate> written,
            Map<Path, BitSet> occupancy) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0)
                    continue;
                long x = (long) coordinate.x() + dx;
                long z = (long) coordinate.z() + dz;
                if ((int) x != x || (int) z != z || written.contains(new ChunkCoordinate((int) x, (int) z)))
                    continue;
                Path path = directory.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
                BitSet present = importRegion.occupancy(path, occupancy);
                if (present != null && !present.get((int) ((z & 31) * 32 + (x & 31))))
                    return true;
            }
        }
        return false;
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
}
