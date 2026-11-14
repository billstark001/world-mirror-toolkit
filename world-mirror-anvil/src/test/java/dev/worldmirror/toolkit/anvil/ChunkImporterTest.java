package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.worldmirror.toolkit.core.ChunkPos;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkImporterTest extends ChunkImportFixture {
    @Test
    void previewReadsCommittedWalWithoutTouchingTheSave() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:dirt", 0)));
        Path database = destination.resolve("data/world_mirror.sqlite");
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        Path shm = database.resolveSibling(database.getFileName() + "-shm");
        try (var connection = openIndex(destination); var statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates("minecraft:overworld",
                    Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), CAPTURE + 1),
                    ChunkIndexStore.REPLAY_IMPORT);
            byte[] databaseBefore = Files.readAllBytes(database);
            byte[] walBefore = Files.readAllBytes(wal);
            byte[] shmBefore = Files.readAllBytes(shm);
            assertTrue(walBefore.length > 0);
            var plan = new ChunkImporter().plan(mirrorOptions(source, destination), true);
            assertEquals(1, plan.preserved());
            assertEquals(0, plan.chunksToWrite());
            assertArrayEquals(databaseBefore, Files.readAllBytes(database));
            assertArrayEquals(walBefore, Files.readAllBytes(wal));
            assertArrayEquals(shmBefore, Files.readAllBytes(shm));
        }
    }

    @Test
    void importsIntoAZeroLengthRegionAndRejectsTruncatedHeaders() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        Path target = region(destination);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1, 2, 3});
        var importer = new ChunkImporter();
        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.DEFAULT,
                ChunkImporter.Policy.EMPTY_ONLY, "auto", false);
        assertThrows(IOException.class, () -> importer.plan(options));

        Files.write(target, new byte[0]);
        var preview = importer.plan(options);
        assertEquals(1, preview.missing());
        importer.execute(preview, false);
        assertArrayEquals(new RegionFileStore().read(region(source)).get(0).payload(),
                new RegionFileStore().read(target).get(0).payload());
    }

    @Test
    void vanillaModePreservesNonemptyAndReplacesEmptyEvenWithBiomes() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE), new ChunkPos(1, 0),
                        chunk(1, "minecraft:stone", CAPTURE), new ChunkPos(2, 0),
                        chunk(2, "minecraft:stone", CAPTURE)));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:dirt", 0), new ChunkPos(1, 0),
                        chunk(1, "minecraft:air", 0), new ChunkPos(3, 0), chunk(3, "minecraft:dirt", 0)));
        Files.writeString(destination.resolve("worldmirror_meta.json"), "{\"metadataSchema\":999}");
        RegionFileStore regions = new RegionFileStore();
        byte[] original = regions.read(region(destination)).get(0).payload();

        ChunkImporter importer = new ChunkImporter();
        var plan = importer.plan(new ChunkImporter.Options(source, destination, ChunkImporter.Mode.DEFAULT,
                ChunkImporter.Policy.EMPTY_ONLY, "auto", false));
        assertEquals(1, plan.missing());
        assertEquals(1, plan.emptyReplaced());
        assertEquals(0, plan.newerReplaced());
        assertEquals(1, plan.preserved());
        Path backup = importer.execute(plan);
        assertTrue(Files.isDirectory(backup));
        var slots = regions.read(region(destination));
        assertArrayEquals(original, slots.get(0).payload());
        assertEquals(4, slots.size());
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(1))));
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(2))));
    }

    @Test
    void mirrorModeUsesIndexedTimestampAndRejectsUnknownSchema() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:dirt", 0)));
        Path metadata = destination.resolve("worldmirror_meta.json");
        Files.writeString(metadata,
                "{\"format\":\"worldmirror\",\"metadataSchema\":99,"
                        + "\"sourceId\":\"" + SOURCE + "\"}");
        ChunkImporter importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertThrows(IOException.class, () -> importer.plan(options));
        writeMirrorMetadata(destination);
        var unknown = importer.plan(options);
        assertEquals(0, unknown.newerReplaced());
        assertEquals(1, unknown.preserved());
        Path database = destination.resolve("data/world_mirror.sqlite");
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            ChunkIndexStore.initialize(connection);
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), 1_000L), "world_mirror");
            try (var sql = connection.createStatement()) {
                // Indexes created before replay imports have no registered replay rule.
                sql.executeUpdate("DELETE FROM update_sources WHERE update_source='replay_import'");
            }
        }
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                        .getMessage()
                        .contains("upgrade the chunk index"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertFalse(new ChunkIndexStore(connection, SOURCE)
                            .hasRegisteredSource(ChunkIndexStore.REPLAY_IMPORT));
            ChunkIndexStore.initialize(connection);
        }
        var indexed = importer.plan(options);
        assertEquals(1, indexed.newerReplaced());
        importer.execute(indexed);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            var updates = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld");
            assertEquals(1, updates.size());
            assertEquals(CAPTURE, updates.getFirst().updateTime());
            assertEquals("replay_import", updates.getFirst().updateSource());
        }
        assertEquals(0, importer.plan(options).chunksToWrite());
    }

    @Test
    void explicitUnindexedReplacementPreservesNewerAndHigherPriorityIndexedChunks() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        writeRegion(source,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE), new ChunkPos(1, 0),
                        chunk(1, "minecraft:stone", CAPTURE), new ChunkPos(2, 0),
                        chunk(2, "minecraft:stone", CAPTURE), new ChunkPos(3, 0),
                        chunk(3, "minecraft:stone", CAPTURE)));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:dirt", CAPTURE + 1), new ChunkPos(1, 0),
                        chunk(1, "minecraft:dirt", CAPTURE + 1), new ChunkPos(2, 0),
                        chunk(2, "minecraft:dirt", CAPTURE - 1), new ChunkPos(3, 0),
                        chunk(3, "minecraft:dirt", CAPTURE - 1)));
        String dimension = "minecraft:overworld";
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates(dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(1, 0), CAPTURE + 1),
                    "world_mirror");
            index.recordUpdates(
                    dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(2, 0), CAPTURE - 1), "player");
            index.recordUpdates(dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(3, 0), CAPTURE - 1),
                    "unregistered");
        }
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertEquals(0, importer.plan(options).chunksToWrite());
        assertThrows(IOException.class,
                ()
                        -> importer.plan(
                                new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                                        ChunkImporter.Policy.EMPTY_ONLY, "auto", true),
                                true));
        var preview = importer.plan(options, true);
        assertEquals(1, preview.chunksToWrite());
        assertEquals(1, preview.unindexedReplaced());
        assertEquals(0, preview.newerReplaced());
        assertEquals(3, preview.preserved());

        var regions = new RegionFileStore();
        var originals = regions.read(region(destination));
        importer.execute(preview, false);
        var written = regions.read(region(destination));
        assertEquals("minecraft:stone", blockName(regions.chunk(written.get(0))));
        for (int slot = 1; slot <= 3; slot++) {
            assertArrayEquals(originals.get(slot).payload(), written.get(slot).payload());
        }
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            assertTrue(index.queryAll(dimension).contains(
                    new ChunkIndexStore.ChunkRecord(0, 0, CAPTURE, "replay_import")));
        }
        assertEquals(0, importer.plan(options, true).chunksToWrite());
    }

    @Test
    void mirrorImportUsesConfiguredPriorityAndChecksThePreviewUnderTheLock() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:dirt", 0)));
        writeMirrorMetadata(destination);
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), CAPTURE - 1), "world_mirror");
        }
        var preview = importer.plan(options);
        assertEquals(1, preview.newerReplaced());
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE update_sources SET priority=20 WHERE update_source='replay_import'");
        }
        byte[] original = Files.readAllBytes(region(destination));
        assertThrows(IOException.class, () -> importer.execute(preview));
        assertFalse(Files.exists(destination.resolve("backups")));
        assertArrayEquals(original, Files.readAllBytes(region(destination)));
        assertEquals(1, importer.plan(options).preserved());

        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            sql.executeUpdate(
                    "UPDATE update_sources SET priority=10, apply_to_dimension='minecraft:the_nether' "
                    + "WHERE update_source='replay_import'");
        }
        assertEquals(1, importer.plan(options).preserved());
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE update_sources SET apply_to_dimension='minecraft:overworld' "
                    + "WHERE update_source='replay_import'");
        }
        importer.execute(importer.plan(options));
        try (var connection = openIndex(destination)) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals("replay_import", row.updateSource());
            assertEquals(CAPTURE, row.updateTime());
        }
    }

    @Test
    void retryVerifiesInstalledBytesAndIndexesThemWithoutASecondRegionWrite() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeMirrorMetadata(destination);
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER fail_import BEFORE INSERT ON chunks "
                    + "BEGIN SELECT RAISE(FAIL, 'test index failure'); END");
        }
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertThrows(IOException.class, () -> importer.execute(importer.plan(options)));
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            assertEquals(0, new ChunkIndexStore(connection, SOURCE).countAll());
            sql.execute("DROP TRIGGER fail_import");
        }
        var modified = Files.getLastModifiedTime(region(destination));
        var retry = importer.plan(options);
        assertEquals(0, retry.chunksToWrite());
        assertEquals(1, retry.chunksToVerifyOnly());
        Path recoveryBackup = importer.execute(retry);
        assertFalse(Files.exists(recoveryBackup.resolve(destination.relativize(region(destination)))));
        assertEquals(modified, Files.getLastModifiedTime(region(destination)));
        try (var connection = openIndex(destination)) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals(CAPTURE, row.updateTime());
            assertEquals("replay_import", row.updateSource());
        }
        assertEquals(0, importer.plan(options).chunksToVerifyOnly());
        writeMirrorMetadata(destination);
        var metadataRetry = importer.plan(options);
        assertEquals(1, metadataRetry.chunksToVerifyOnly());
        importer.execute(metadataRetry);
        assertEquals(modified, Files.getLastModifiedTime(region(destination)));
        assertEquals(0, importer.plan(options).chunksToVerifyOnly());
    }

    @Test
    void fillingAnEmptyChunkRecordsItsActualCaptureAndIsIdempotentForAllAir() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:air", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:air", CAPTURE + 1)));
        writeMirrorMetadata(destination);
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), CAPTURE + 1), "world_mirror");
        }
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        var preview = importer.plan(options);
        assertEquals(1, preview.emptyReplaced());
        importer.execute(preview);
        try (var connection = openIndex(destination)) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals(CAPTURE, row.updateTime());
            assertEquals("replay_import", row.updateSource());
        }
        var retry = importer.plan(options);
        assertEquals(0, retry.chunksToWrite());
        assertEquals(0, retry.chunksToVerifyOnly());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedIndexUpdateRestoresTheEmptyChunkRepresentedByThePreviousRow(boolean keepBackup)
            throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:air", CAPTURE + 1)));
        writeMirrorMetadata(destination);
        byte[] original = Files.readAllBytes(region(destination));
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), CAPTURE + 1), "world_mirror");
            sql.execute("CREATE TRIGGER fail_import BEFORE UPDATE ON chunks "
                    + "BEGIN SELECT RAISE(FAIL, 'test index failure'); END");
        }
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertThrows(IOException.class, () -> importer.execute(importer.plan(options), keepBackup));
        assertArrayEquals(original, Files.readAllBytes(region(destination)));
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals(CAPTURE + 1, row.updateTime());
            assertEquals("world_mirror", row.updateSource());
            sql.execute("DROP TRIGGER fail_import");
        }
        var retry = importer.plan(options);
        assertEquals(1, retry.emptyReplaced());
        importer.execute(retry, keepBackup);
        try (var connection = openIndex(destination)) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals(CAPTURE, row.updateTime());
            assertEquals("replay_import", row.updateSource());
        }
        assertEquals(keepBackup, Files.exists(destination.resolve("backups")));
        try (var entries = Files.list(region(destination).getParent())) {
            assertFalse(entries.anyMatch(
                    path -> path.getFileName().toString().startsWith(".toolkit-region-original-")));
        }
    }
}
