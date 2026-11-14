package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.MirrorSavePaths;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkRecord;
import io.github.billstark001.worldmirror.format.ChunkUpdate;
import io.github.billstark001.worldmirror.merge.RegionMerge;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Mode;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Routing;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Plan;
import dev.worldmirror.toolkit.anvil.ChunkImportPlanner.Decision;
import dev.worldmirror.toolkit.anvil.MirrorImportMetadata.Identity;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requirePlainFile;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requirePlainDirectory;
import static dev.worldmirror.toolkit.anvil.ImportRegion.matches;
import static dev.worldmirror.toolkit.anvil.ImportRegion.sha256;
import static dev.worldmirror.toolkit.anvil.ImportRegion.fingerprint;
import static dev.worldmirror.toolkit.anvil.MirrorImportIndex.indexedRegion;

/** Installs a checked plan under the destination lease. */
final class ChunkImportExecutor {
    private record StagedChunk(Decision decision, RegionFileStore.Slot expected) {}

    private static final DateTimeFormatter BACKUP_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final RegionFileStore regions = new RegionFileStore();
    private final ImportRegion importRegion = new ImportRegion(regions);
    private final ChunkImportPlanner planner;

    ChunkImportExecutor(ChunkImportPlanner planner) {
        this.planner = planner;
    }

    Path execute(Plan preview, boolean keepBackup) throws IOException {
        if (preview.decisions().isEmpty())
            return null;
        Path destination = preview.options().destination().toRealPath();
        if (!MirrorSavePaths.at(destination).chunkIndex().equals(preview.database())) {
            throw new IOException(
                    "import destination changed before the save was locked; review a fresh plan");
        }
        Routing routing = Routing.AUTO;
        if (preview.options().mode() == Mode.MIRROR) {
            // Reject stale metadata before creating session.lock. The plan is still rechecked under the lock.
            Decision first = preview.decisions().values().iterator().next().getFirst();
            routing = first.routing().request();
            if (!Objects.equals(first.mirror(),
                        MirrorImportMetadata.read(MirrorSavePaths.at(destination).metadata()).identity())) {
                throw new IOException("import plan changed before the save was locked; review a fresh plan");
            }
        }
        Path lockPath = destination.resolve("session.lock");
        FileChannel graphLease = preview.options().mode() == Mode.MIRROR
                ? ReplaySourceRouting.acquireNativeLease(destination)
                : null;
        try (graphLease; FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                                 StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            if (lock == null)
                throw new IOException("destination is in use: " + destination);
            // A plan authorizes replacing unindexed chunks only when its preview included such writes.
            Plan plan = planner.plan(
                    preview.options(), preview.unindexedReplaced() > 0, routing, destination, true);
            if (!plan.equals(preview)) {
                throw new IOException("import plan changed before the save was locked; review a fresh plan");
            }
            Backup backup = keepBackup ? createBackup(plan, destination) : null;
            try (Connection db = MirrorImportIndex.openWritable(plan)) {
                ChunkIndexStore index = db == null ? null : new ChunkIndexStore(db, plan.sourceId());
                long newestImported = 0;
                for (var regionEntry : plan.decisions().entrySet()) {
                    long imported = installRegion(
                            plan, regionEntry.getKey(), regionEntry.getValue(), backup, db, index);
                    newestImported = Math.max(newestImported, imported);
                }
                if (index != null) {
                    Identity mirror = plan.decisions().values().iterator().next().getFirst().mirror();
                    MirrorImportMetadata.advanceLastSync(
                            MirrorSavePaths.at(destination).metadata(), mirror, newestImported);
                }
            } catch (SQLException | IOException | RuntimeException failure) {
                String detail = backup == null ? "import failed: "
                                               : "import failed; backup at " + backup.path() + ": ";
                throw new IOException(detail + failure.getMessage(), failure);
            }
            return backup == null ? null : backup.path();
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("destination is in use: " + destination, busy);
        }
    }

    private long installRegion(Plan plan, Path target, List<Decision> decisions, Backup backup, Connection db,
            ChunkIndexStore index) throws IOException, SQLException {
        boolean replace = willWrite(decisions);
        try (var original = originalRegion(target, replace, backup)) {
            var result = RegionMerge.writeVerified(()
                                                           -> writeRegion(target, decisions),
                    expected
                    -> verifyRegion(
                            target, expected, plan.sourceMetadata().dataVersion(), replace, original.path()));
            long imported = 0;
            if (index != null) {
                try {
                    imported = commit(index, result.written());
                } catch (SQLException | RuntimeException failure) {
                    recoverFailedCommit(db, plan, target, original.path(), failure);
                    throw failure;
                }
            }
            System.out.println("imported " + target + " chunks=" + result.written().size());
            return imported;
        }
    }

    private record Backup(Path path, Map<Path, Path> regions) {}

    private record OriginalRegion(Path path, boolean temporary) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (temporary)
                Files.deleteIfExists(path);
        }
    }

    private static Backup createBackup(Plan plan, Path destination) throws IOException {
        Path backups = destination.resolve("backups");
        requirePlainDirectory(backups);
        Files.createDirectories(backups);
        Path path = Files.createTempDirectory(
                backups, "toolkit-import-" + BACKUP_DATE.format(Instant.now()) + "-");
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

    private static OriginalRegion originalRegion(Path target, boolean replace, Backup backup)
            throws IOException {
        if (backup != null)
            return new OriginalRegion(backup.regions().get(target), false);
        if (!replace || Files.notExists(target))
            return new OriginalRegion(null, false);
        // Keep only the current region's rollback file until its verified write and index commit finish.
        Path temporary = Files.createTempFile(target.getParent(), ".toolkit-region-original-", ".tmp");
        try {
            Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return new OriginalRegion(temporary, true);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void recoverFailedCommit(
            Connection db, Plan plan, Path target, Path original, Exception failure) {
        try {
            db.close();
        } catch (SQLException close) {
            failure.addSuppressed(close);
            return;
        }
        restoreIfIndexUnchanged(plan, target, original, failure);
    }

    private Map<Integer, StagedChunk> writeRegion(Path target, List<Decision> decisions) throws IOException {
        requirePlainFile(target);
        requirePlainFile(decisions.getFirst().sourceRegion());
        Map<Integer, RegionFileStore.Slot> slots = regions.read(target);
        Map<Integer, RegionFileStore.Slot> source = regions.read(decisions.getFirst().sourceRegion());
        Map<Integer, StagedChunk> staged = new LinkedHashMap<>();
        BitSet auxiliary = null;
        MessageDigest hasher = sha256();
        for (Decision decision : decisions) {
            RegionFileStore.Slot incoming = source.get(decision.slot());
            if (incoming == null || !decision.sourceDigest().equals(fingerprint(hasher, incoming))) {
                throw new IOException(
                        "source region changed: " + decision.sourceRegion() + " slot " + decision.slot());
            }
            if (!Objects.equals(decision.targetDigest(), fingerprint(hasher, slots.get(decision.slot())))) {
                throw new IOException("destination region changed: " + target + " slot " + decision.slot());
            }
            RegionFileStore.Slot expected = incoming;
            if (decision.receivedCapture())
                expected = slots.get(decision.slot());
            if (!decision.verifyOnly() && decision.mirror() != null) {
                if (decision.seedReplacement()) {
                    if (auxiliary == null)
                        auxiliary = importRegion.auxiliarySlots(target);
                    if (auxiliary.get(decision.slot())) {
                        throw new IOException("destination auxiliary chunk data changed: " + target + " slot "
                                + decision.slot());
                    }
                }
                var raw = regions.chunk(incoming);
                try {
                    var prepared = SeedTerrainEvidence.prepareReplay(raw, decision.mirror().nodeId(),
                            decision.update().captureTime(), decision.seedReplacement(),
                            decision.blendEpoch(), decision.seeded() ? decision.update().dimension() : null);
                    if (prepared != raw)
                        expected = regions.withChunk(incoming, prepared);
                } catch (IllegalArgumentException malformed) {
                    throw new IOException(
                            "invalid replay terrain at " + target + " slot " + decision.slot(), malformed);
                }
            }
            if (!decision.verifyOnly())
                slots.put(decision.slot(), expected);
            staged.put(decision.slot(), new StagedChunk(decision, expected));
        }
        if (willWrite(decisions))
            regions.write(target, slots);
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
                        || facts.dataVersion() != dataVersion
                        || facts.captureTimeMillis() != update.captureTime()) {
                    throw new IOException("region read-back has unexpected chunk data: " + target + " slot "
                            + entry.getKey());
                }
            }
            return new RegionMerge.Verification<>(true, expected.keySet(), Set.of());
        } catch (IOException failure) {
            if (replaced) {
                try {
                    regions.restore(target, original);
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
            }
            throw failure;
        }
    }

    private void restoreIfIndexUnchanged(Plan plan, Path target, Path original, Exception failure) {
        List<Decision> decisions = plan.decisions().get(target);
        if (decisions.stream().noneMatch(chunk -> !chunk.verifyOnly() && chunk.previousIndex() != null))
            return;

        // The writer connection is closed before reading committed rows through a separate connection.
        ChunkUpdate first = decisions.getFirst().update();
        try (Connection db = MirrorImportIndex.openReadOnly(plan.database())) {
            var index = new ChunkIndexStore(db, plan.sourceId());
            Map<ChunkCoordinate, ChunkRecord> current = indexedRegion(
                    index, first.dimension(), first.coordinate().x() >> 5, first.coordinate().z() >> 5);
            if (decisions.stream().allMatch(chunk
                        -> Objects.equals(chunk.previousIndex(), current.get(chunk.update().coordinate())))) {
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
            if (decision.replaceIndexClaim())
                replacements.add(update.coordinate());
            newest = Math.max(newest, update.captureTime());
        }
        index.recordUpdates(dimension, times, ChunkIndexStore.REPLAY_IMPORT, replacements);
        return newest;
    }

    private static boolean backupIfPresent(Path root, Path backup, Path file) throws IOException {
        if (Files.notExists(file))
            return false;
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        Path target = backup.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
    }
}
