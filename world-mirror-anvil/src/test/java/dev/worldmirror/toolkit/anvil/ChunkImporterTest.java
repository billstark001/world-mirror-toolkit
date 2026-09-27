package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.WorldLayout;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkImporterTest {
    @TempDir Path temporary;
    private static final String SOURCE = "server:example.test";
    private static final long CAPTURE = 2_000_000L;

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
        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        assertThrows(IOException.class, () -> importer.plan(options));
        Files.writeString(metadata, "{\"format\":\"worldmirror\",\"metadataSchema\":1,"
                + "\"sourceId\":\"" + SOURCE + "\",\"lastSyncTime\":0}");
        var unknown = importer.plan(options);
        assertEquals(0, unknown.newerReplaced());
        assertEquals(1, unknown.preserved());
        Path database = destination.resolve("data/world_mirror.sqlite");
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            ChunkIndexStore.initialize(connection);
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
                    Map.of(new ChunkIndexStore.ChunkCoordinate(0, 0), 1_000L), "world_mirror");
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
