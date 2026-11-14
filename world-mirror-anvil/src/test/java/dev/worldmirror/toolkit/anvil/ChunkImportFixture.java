package dev.worldmirror.toolkit.anvil;

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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

abstract class ChunkImportFixture {
    @TempDir Path temporary;
    static final String SOURCE = "server:example.test";
    static final long CAPTURE = 2_000_000L;
    static final String EPOCH = "16ce57f5-32df-4590-8d9b-1f05e079f044";

    static ChunkImporter.Options mirrorOptions(Path source, Path destination) {
        return new ChunkImporter.Options(
                source, destination, ChunkImporter.Mode.MIRROR, ChunkImporter.Policy.TIMESTAMP, "auto", true);
    }

    static void writeMirrorMetadata(Path destination) throws IOException {
        writeGraphMetadata(destination, "mirror:test", SOURCE);
    }

    static void writeGraphMetadata(Path destination, String nodeId, String indexSource) throws IOException {
        writeGraphMetadata(destination, nodeId, "remote:logical-source", indexSource);
    }

    static void writeGraphMetadata(Path destination, String nodeId, String parentId, String indexSource)
            throws IOException {
        Files.writeString(
                destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"%s","parentNodeId":"%s",
                 "kind":"MIRROR","role":"SYNC","sourceId":"%s","lastSyncTime":0,
                 "generation":{"mode":"VOID","revision":1,"assetRevision":3,"dataVersion":4903,"cleanupRevision":1}}
                """.formatted(nodeId, parentId, indexSource));
    }

    static Connection openIndex(Path destination) throws IOException, SQLException {
        Path database = destination.resolve("data/world_mirror.sqlite");
        Files.createDirectories(database.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        ChunkIndexStore.initialize(connection);
        return connection;
    }

    Path source() throws IOException {
        return source(Map.of("minecraft:overworld", 3));
    }

    Path source(Map<String, Integer> countByDimension) throws IOException {
        Path source = temporary.resolve("source");
        AnalysisExportMetadata
                .create("26.2", 4903, WorldLayout.NAMESPACED, SOURCE, "world-test", "1", CAPTURE, CAPTURE,
                        1000, List.of("test.mcpr"), countByDimension)
                .write(source);
        return source;
    }

    Path destination() throws IOException {
        Path destination = temporary.resolve("destination");
        Files.createDirectories(destination);
        Files.copy(temporary.resolve("source/level.dat"), destination.resolve("level.dat"));
        return destination;
    }

    Path region(Path world) {
        return world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca");
    }

    void writeRegion(Path world, Map<ChunkPos, NbtValue.CompoundValue> chunks) throws IOException {
        new RegionFileWriter().write(region(world), chunks);
    }

    NbtValue.CompoundValue chunk(int x, String block, long capture) {
        NbtValue.CompoundValue palette = NbtValue.compound(Map.of("Name", NbtValue.stringValue(block)));
        NbtValue.CompoundValue states =
                NbtValue.compound(Map.of("palette", NbtValue.list(NbtTagId.COMPOUND, List.of(palette))));
        NbtValue.CompoundValue biomes = NbtValue.compound(Map.of("palette",
                NbtValue.list(NbtTagId.STRING, List.of(NbtValue.stringValue("minecraft:plains")))));
        NbtValue.CompoundValue section = NbtValue.compound(
                Map.of("Y", NbtValue.byteValue(0), "block_states", states, "biomes", biomes));
        return NbtValue.compound(Map.of("DataVersion", NbtValue.intValue(4903), "xPos", NbtValue.intValue(x),
                "zPos", NbtValue.intValue(0), "sections", NbtValue.list(NbtTagId.COMPOUND, List.of(section)),
                "block_entities", NbtValue.list(NbtTagId.COMPOUND, List.of()), "ReplayRecovered",
                NbtValue.compound(Map.of("CaptureTimestampMillis", NbtValue.longValue(capture)))));
    }

    String blockName(NbtValue.CompoundValue chunk) {
        var section = (NbtValue.CompoundValue) ((NbtValue.ListValue) chunk.values().get("sections"))
                              .values()
                              .getFirst();
        var states = (NbtValue.CompoundValue) section.values().get("block_states");
        var palette = (NbtValue.ListValue) states.values().get("palette");
        return ((NbtValue.StringValue) ((NbtValue.CompoundValue) palette.values().getFirst())
                        .values()
                        .get("Name"))
                .value();
    }
}
