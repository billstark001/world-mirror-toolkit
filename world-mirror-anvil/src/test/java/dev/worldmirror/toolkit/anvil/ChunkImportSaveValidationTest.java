package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.WorldLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkImportSaveValidationTest extends ChunkImportFixture {
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
                        .getMessage()
                        .contains("requires recovery in World Mirror"));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(preview, false))
                        .getMessage()
                        .contains("requires recovery in World Mirror"));
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
                        .getMessage()
                        .contains("not a plain directory"));
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
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"));
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
                                .getMessage()
                                .contains("not a plain directory"));
            }
            assertTrue(assertThrows(IOException.class, () -> importer.execute(preview))
                            .getMessage()
                            .contains("not a plain directory"));
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
                    + new String(process.getInputStream().readAllBytes(),
                            java.nio.charset.Charset.defaultCharset()));
        }
    }

    @Test
    void planRejectsDimensionAliasesAndNoncanonicalRegionNames() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        AnalysisExportMetadata
                .create("26.2", 4903, WorldLayout.NAMESPACED, SOURCE, "world-test", "1", CAPTURE, CAPTURE,
                        1000, List.of("test.mcpr"),
                        Map.of("minecraft:overworld", 1, "minecraft:overworld/", 1))
                .write(source);
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                        .getMessage()
                        .contains("same region directory"));

        source();
        Files.move(region(source), region(source).resolveSibling("r.00.0.mca"));
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options))
                        .getMessage()
                        .contains("noncanonical region filename"));
    }
}
