package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.WorldLayout;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkImporterTest {
    @TempDir Path temporary;
    private static final String SOURCE = "server:example.test";
    private static final long CAPTURE = 2_000_000L;

    @Test
    void importsIntoAZeroLengthRegionAndRejectsTruncatedHeaders() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        Path target = region(destination);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[]{1, 2, 3});
        var importer = new ChunkImporter();
        var options = new ChunkImporter.Options(source, destination,
                ChunkImporter.Mode.DEFAULT, ChunkImporter.Policy.EMPTY_ONLY, "auto", false);
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
        writeRegion(source, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE),
                new ChunkPos(1, 0), chunk(1, "minecraft:stone", CAPTURE),
                new ChunkPos(2, 0), chunk(2, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:dirt", 0),
                new ChunkPos(1, 0), chunk(1, "minecraft:air", 0),
                new ChunkPos(3, 0), chunk(3, "minecraft:dirt", 0)));
        Files.writeString(destination.resolve("worldmirror_meta.json"), "{\"metadataSchema\":999}");
        RegionFileStore regions = new RegionFileStore();
        byte[] original = regions.read(region(destination)).get(0).payload();

        ChunkImporter importer = new ChunkImporter();
        var plan = importer.plan(new ChunkImporter.Options(source, destination,
                ChunkImporter.Mode.DEFAULT, ChunkImporter.Policy.EMPTY_ONLY, "auto", false));
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
        Files.writeString(metadata, "{\"format\":\"worldmirror\",\"metadataSchema\":99,"
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
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
                    Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), 1_000L), "world_mirror");
            try (var sql = connection.createStatement()) {
                // Indexes created before replay imports have no registered replay rule.
                sql.executeUpdate("DELETE FROM update_sources WHERE update_source='replay_import'");
            }
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
    void oldMirrorMetadataMustBeMigratedByWorldMirrorBeforeImporting() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeMirrorMetadata(destination);
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        var preview = importer.plan(options);
        Path metadata = destination.resolve("worldmirror_meta.json");
        byte[] legacy = """
                {"format":"worldmirror","metadataSchema":1,"sourceId":"server:example.test","lastSyncTime":0}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(metadata, legacy);

        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                .getMessage().contains("upgrade the mirror metadata in World Mirror"));
        assertArrayEquals(legacy, Files.readAllBytes(metadata));
        assertTrue(Files.notExists(destination.resolve("session.lock")));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(preview, false))
                .getMessage().contains("upgrade the mirror metadata in World Mirror"));
        assertArrayEquals(legacy, Files.readAllBytes(metadata));
        assertTrue(Files.notExists(destination.resolve("session.lock")));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(preview.database()));
        assertTrue(Files.notExists(destination.resolve("backups")));
    }

    @Test
    void graphMetadataRequiresExplicitSourceVerificationAndUsesTheExistingIndexNamespace() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        Path metadata = destination.resolve("worldmirror_meta.json");
        writeGraphMetadata(destination, "mirror:first", SOURCE);
        var importer = new ChunkImporter();
        var unverified = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(unverified))
                .getMessage().contains("--allow-source-mismatch"));

        String indexSource = "server:former.example.test";
        writeGraphMetadata(destination, "mirror:first", indexSource);
        String original = Files.readString(metadata);
        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", true);
        var plan = importer.plan(options);
        assertEquals(indexSource, plan.sourceId());
        assertEquals(original, Files.readString(metadata));
        importer.execute(plan, false);

        var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata.toFile());
        assertEquals(2, saved.path("metadataSchema").intValue());
        assertEquals("mirror:first", saved.path("nodeId").textValue());
        assertEquals("remote:logical-source", saved.path("parentNodeId").textValue());
        assertEquals(CAPTURE, saved.path("lastSyncTime").longValue());
        assertEquals("VOID", saved.path("generation").path("mode").textValue());
        try (var connection = openIndex(destination)) {
            assertEquals(List.of(new ChunkIndexStore.ChunkRecord(0, 0, CAPTURE, "replay_import")),
                    new ChunkIndexStore(connection, indexSource).queryAll("minecraft:overworld"));
            assertTrue(new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").isEmpty());
        }
    }

    @Test
    void rejectsChangedGraphNodeEvenWhenPathIndexAndTerrainStillMatchThePreview() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", SOURCE);
        var importer = new ChunkImporter();
        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", true);
        var plan = importer.plan(options);
        writeGraphMetadata(destination, "mirror:second", SOURCE);

        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                .getMessage().contains("import plan changed"));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(plan.database()));
    }

    @Test
    void refusesTrailingJsonAfterValidMirrorMetadata() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        Path metadata = destination.resolve("worldmirror_meta.json");
        String invalid = Files.readString(metadata) + "{\"unexpected\":true}";
        Files.writeString(metadata, invalid);

        assertThrows(IOException.class, () -> new ChunkImporter().plan(mirrorOptions(source, destination)));
        assertEquals(invalid, Files.readString(metadata));
    }

    @ParameterizedTest
    @ValueSource(strings = {".worldmirror-recovery-required", ".worldmirror-relocation.json",
            ".worldmirror-transaction-test"})
    void requiresRecoveryForAnInterruptedWorldMirrorOperation(String journal) throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeMirrorMetadata(destination);
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        var preview = importer.plan(options);
        Files.createFile(destination.resolve(journal));

        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                .getMessage().contains("requires recovery in World Mirror"));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(preview, false))
                .getMessage().contains("requires recovery in World Mirror"));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(preview.database()));
    }

    @Test
    void rejectsSymbolicIndexDirectoryAndSessionLock() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeMirrorMetadata(destination);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path indexDirectory = destination.resolve("data");
        Path lockTarget = Files.writeString(outside.resolve("lock-target"), "untouched");
        try {
            Files.createSymbolicLink(indexDirectory, outside);
            Files.createSymbolicLink(destination.resolve("session.lock"), lockTarget);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable);
        }

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                .getMessage().contains("not a plain directory"));
        Files.delete(indexDirectory);
        var plan = importer.plan(options);
        assertThrows(IOException.class, () -> importer.execute(plan, false));
        assertEquals("untouched", Files.readString(lockTarget));
        assertTrue(Files.notExists(outside.resolve("world_mirror.sqlite")));
        assertTrue(Files.notExists(region(destination)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "backups", "dimensions/minecraft"})
    void rejectsWindowsDirectoryJunctions(String directory) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).startsWith("windows"));
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeMirrorMetadata(destination);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "untouched");
        Path junction = destination.resolve(directory);
        Files.createDirectories(junction.getParent());
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        var preview = importer.plan(options);

        createJunction(junction, outside);
        try {
            if (!directory.equals("backups")) {
                assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                        .getMessage().contains("not a plain directory"));
            }
            assertTrue(assertThrows(IOException.class, () -> importer.execute(preview))
                    .getMessage().contains("not a plain directory"));
            assertEquals("untouched", Files.readString(sentinel));
            try (var files = Files.list(outside)) {
                assertEquals(List.of(sentinel), files.toList());
            }
            assertTrue(Files.notExists(region(destination)));
        } finally {
            Files.delete(junction);
        }
    }

    private static void createJunction(Path junction, Path target) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction "
                        + "-Path $env:WM_TEST_JUNCTION -Target $env:WM_TEST_JUNCTION_TARGET | Out-Null");
        builder.environment().put("WM_TEST_JUNCTION", junction.toString());
        builder.environment().put("WM_TEST_JUNCTION_TARGET", target.toString());
        var process = builder.redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out creating a test junction");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Could not create a test junction: "
                    + new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset()));
        }
    }

    @Test
    void explicitUnindexedReplacementPreservesNewerAndHigherPriorityIndexedChunks() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        writeRegion(source, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE),
                new ChunkPos(1, 0), chunk(1, "minecraft:stone", CAPTURE),
                new ChunkPos(2, 0), chunk(2, "minecraft:stone", CAPTURE),
                new ChunkPos(3, 0), chunk(3, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:dirt", CAPTURE + 1),
                new ChunkPos(1, 0), chunk(1, "minecraft:dirt", CAPTURE + 1),
                new ChunkPos(2, 0), chunk(2, "minecraft:dirt", CAPTURE - 1),
                new ChunkPos(3, 0), chunk(3, "minecraft:dirt", CAPTURE - 1)));
        String dimension = "minecraft:overworld";
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates(dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(1, 0), CAPTURE + 1), "world_mirror");
            index.recordUpdates(dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(2, 0), CAPTURE - 1), "player");
            index.recordUpdates(dimension, Map.of(new ChunkIndexStore.ChunkCoordinate(3, 0), CAPTURE - 1), "unregistered");
        }
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertEquals(0, importer.plan(options).chunksToWrite());
        assertThrows(IOException.class, () -> importer.plan(new ChunkImporter.Options(source, destination,
                ChunkImporter.Mode.MIRROR, ChunkImporter.Policy.EMPTY_ONLY, "auto", true), true));
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
            assertTrue(index.queryAll(dimension).contains(new ChunkIndexStore.ChunkRecord(0, 0, CAPTURE, "replay_import")));
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
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
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
            sql.executeUpdate("UPDATE update_sources SET priority=10, apply_to_dimension='minecraft:the_nether' "
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
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
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
    void failedIndexUpdateRestoresTheEmptyChunkRepresentedByThePreviousRow(boolean keepBackup) throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:air", CAPTURE + 1)));
        writeMirrorMetadata(destination);
        byte[] original = Files.readAllBytes(region(destination));
        try (var connection = openIndex(destination); var sql = connection.createStatement()) {
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
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
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".toolkit-region-original-")));
        }
    }

    @Test
    void planRejectsDimensionAliasesAndNoncanonicalRegionNames() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        AnalysisExportMetadata.create("26.2", 4903, WorldLayout.NAMESPACED, SOURCE,
                "world-test", "1", CAPTURE, CAPTURE, 1000, List.of("test.mcpr"),
                Map.of("minecraft:overworld", 1, "minecraft:overworld/", 1)).write(source);
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options)).getMessage()
                .contains("same region directory"));

        source();
        Files.move(region(source), region(source).resolveSibling("r.00.0.mca"));
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options)).getMessage()
                .contains("noncanonical region filename"));
    }

    private static ChunkImporter.Options mirrorOptions(Path source, Path destination) {
        return new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", true);
    }

    private static void writeMirrorMetadata(Path destination) throws IOException {
        writeGraphMetadata(destination, "mirror:test", SOURCE);
    }

    private static void writeGraphMetadata(Path destination, String nodeId, String indexSource) throws IOException {
        Files.writeString(destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"%s","parentNodeId":"remote:logical-source",
                 "kind":"MIRROR","role":"SYNC","sourceId":"%s","lastSyncTime":0,
                 "generation":{"mode":"VOID","revision":1,"assetRevision":3,"dataVersion":4903,"cleanupRevision":1}}
                """.formatted(nodeId, indexSource));
    }

    private static Connection openIndex(Path destination) throws IOException, SQLException {
        Path database = destination.resolve("data/world_mirror.sqlite");
        Files.createDirectories(database.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        ChunkIndexStore.initialize(connection);
        return connection;
    }

    private Path source() throws IOException {
        Path source = temporary.resolve("source");
        AnalysisExportMetadata.create("26.2", 4903, WorldLayout.NAMESPACED, SOURCE,
                "world-test", "1", CAPTURE, CAPTURE, 1000, List.of("test.mcpr"),
                Map.of("minecraft:overworld", 3)).write(source);
        return source;
    }

    private Path destination() throws IOException {
        Path destination = temporary.resolve("destination");
        Files.createDirectories(destination);
        Files.copy(temporary.resolve("source/level.dat"), destination.resolve("level.dat"));
        return destination;
    }

    private Path region(Path world) {
        return world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
    }

    private void writeRegion(Path world, Map<ChunkPos, NbtValue.CompoundValue> chunks) throws IOException {
        new RegionFileWriter().write(region(world), chunks);
    }

    private NbtValue.CompoundValue chunk(int x, String block, long capture) {
        NbtValue.CompoundValue palette = NbtValue.compound(Map.of("Name", NbtValue.stringValue(block)));
        NbtValue.CompoundValue states = NbtValue.compound(Map.of("palette",
                NbtValue.list(NbtTagId.COMPOUND, List.of(palette))));
        NbtValue.CompoundValue biomes = NbtValue.compound(Map.of("palette",
                NbtValue.list(NbtTagId.STRING, List.of(NbtValue.stringValue("minecraft:plains")))));
        NbtValue.CompoundValue section = NbtValue.compound(Map.of(
                "Y", NbtValue.byteValue(0), "block_states", states, "biomes", biomes));
        return NbtValue.compound(Map.of(
                "DataVersion", NbtValue.intValue(4903),
                "xPos", NbtValue.intValue(x), "zPos", NbtValue.intValue(0),
                "sections", NbtValue.list(NbtTagId.COMPOUND, List.of(section)),
                "block_entities", NbtValue.list(NbtTagId.COMPOUND, List.of()),
                "ReplayRecovered", NbtValue.compound(Map.of("CaptureTimestampMillis",
                        NbtValue.longValue(capture)))));
    }

    private String blockName(NbtValue.CompoundValue chunk) {
        var section = (NbtValue.CompoundValue) ((NbtValue.ListValue) chunk.values().get("sections")).values().getFirst();
        var states = (NbtValue.CompoundValue) section.values().get("block_states");
        var palette = (NbtValue.ListValue) states.values().get("palette");
        return ((NbtValue.StringValue) ((NbtValue.CompoundValue) palette.values().getFirst())
                .values().get("Name")).value();
    }
}
