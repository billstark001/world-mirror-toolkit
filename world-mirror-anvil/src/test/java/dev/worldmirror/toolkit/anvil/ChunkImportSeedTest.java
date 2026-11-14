package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.worldmirror.toolkit.core.ChunkPos;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkImportSeedTest extends ChunkImportFixture {
    @Test
    void emptySeedTerrainRequiresAnUnmodifiedReceiptAndRespectsIndexedPlayerUpdates() throws Exception {
        Path source = source();
        Path destination = destination();
        writeSeededMetadata(destination, false);
        writeRegion(source,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE), new ChunkPos(1, 0),
                        chunk(1, "minecraft:stone", CAPTURE), new ChunkPos(2, 0),
                        chunk(2, "minecraft:stone", CAPTURE)));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0),
                        withSeedReceipt(chunk(0, "minecraft:air", 0), EPOCH, false, "minecraft:air"),
                        new ChunkPos(1, 0),
                        withSeedReceipt(chunk(1, "minecraft:air", 0), EPOCH, true, "minecraft:air"),
                        new ChunkPos(2, 0),
                        withSeedReceipt(chunk(2, "minecraft:air", 0), EPOCH, false, "minecraft:air")));
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates(
                    "minecraft:overworld", Map.of(new ChunkIndexStore.ChunkCoordinate(2, 0), 1L), "player");
        }

        var importer = new ChunkImporter();
        var emptyOnly = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.EMPTY_ONLY, "auto", true);
        var timestamp = importer.plan(mirrorOptions(source, destination));
        assertEquals(1, importer.plan(emptyOnly).chunksToWrite());
        assertEquals(1, timestamp.chunksToWrite());
        importer.execute(timestamp, false);
        var regions = new RegionFileStore();
        var slots = regions.read(region(destination));
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(0))));
        assertEquals("minecraft:air", blockName(regions.chunk(slots.get(1))));
        assertEquals("minecraft:air", blockName(regions.chunk(slots.get(2))));
    }

    @Test
    void timestampImportOnlyReplacesSeedTerrainThatMatchesItsCurrentReceipt() throws Exception {
        Path source = source();
        Path destination = destination();
        String epoch = "16ce57f5-32df-4590-8d9b-1f05e079f044";
        Files.writeString(destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"mirror:test",
                 "parentNodeId":"remote:logical-source","kind":"MIRROR","role":"SYNC","sourceId":"%s",
                 "lastSyncTime":0,"generation":{"mode":"SEEDED","seedEpoch":"%s"}}
                """.formatted(SOURCE, epoch));
        writeRegion(source,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE), new ChunkPos(1, 0),
                        chunk(1, "minecraft:stone", CAPTURE), new ChunkPos(2, 0),
                        chunk(2, "minecraft:stone", CAPTURE), new ChunkPos(3, 0),
                        chunk(3, "minecraft:stone", CAPTURE), new ChunkPos(4, 0),
                        chunk(4, "minecraft:stone", CAPTURE), new ChunkPos(5, 0),
                        chunk(5, "minecraft:stone", CAPTURE)));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0), withSeedReceipt(chunk(0, "minecraft:dirt", 0), epoch),
                        new ChunkPos(1, 0), withSeedReceipt(chunk(1, "minecraft:gold_block", 0), epoch),
                        new ChunkPos(2, 0), chunk(2, "minecraft:dirt", 0), new ChunkPos(3, 0),
                        chunk(3, "minecraft:air", 0), new ChunkPos(4, 0),
                        withSeedReceipt(chunk(4, "minecraft:dirt", 0), epoch), new ChunkPos(5, 0),
                        withSeedReceipt(chunk(5, "minecraft:dirt", 0), epoch)));
        Path entities = region(destination).getParent().resolveSibling("entities").resolve("r.0.0.mca");
        new RegionFileWriter().write(entities, Map.of(new ChunkPos(4, 0), chunk(4, "minecraft:air", 0)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordSeedGeneratedIfMissing("minecraft:overworld",
                            Set.of(new ChunkIndexStore.ChunkCoordinate(0, 0),
                                    new ChunkIndexStore.ChunkCoordinate(1, 0),
                                    new ChunkIndexStore.ChunkCoordinate(2, 0),
                                    new ChunkIndexStore.ChunkCoordinate(3, 0)));
        }

        var importer = new ChunkImporter();
        var emptyOnly = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.EMPTY_ONLY, "auto", true);
        assertEquals(0, importer.plan(emptyOnly).emptyReplaced());
        var plan = importer.plan(mirrorOptions(source, destination), true);
        assertEquals(2, plan.newerReplaced());
        assertEquals(0, plan.emptyReplaced());
        assertEquals(4, plan.preserved());
        importer.execute(plan, false);
        var regions = new RegionFileStore();
        var slots = regions.read(region(destination));
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(0))));
        assertEquals("minecraft:gold_block", blockName(regions.chunk(slots.get(1))));
        assertEquals("minecraft:dirt", blockName(regions.chunk(slots.get(2))));
        assertEquals("minecraft:air", blockName(regions.chunk(slots.get(3))));
        assertEquals("minecraft:dirt", blockName(regions.chunk(slots.get(4))));
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(5))));
        assertTrue(SeedTerrainEvidence.hasCaptureReceipt(regions.chunk(slots.get(0))));
        assertTrue(SeedTerrainEvidence.hasCaptureReceipt(regions.chunk(slots.get(5))));
        importer.execute(importer.plan(emptyOnly), false);
        assertEquals("minecraft:air", blockName(regions.chunk(regions.read(region(destination)).get(3))));
    }

    @Test
    void marksOnlyFullSeededOverworldBordersAndRecoversAStampedWrite() throws Exception {
        Path source = source(Map.of("minecraft:overworld", 10, "minecraft:the_nether", 1));
        Path destination = destination();
        writeSeededMetadata(destination, true);
        var imported = new HashMap<ChunkPos, NbtValue.CompoundValue>();
        for (int z = 0; z < 3; z++) {
            for (int x = 30; x < 33; x++) imported.put(new ChunkPos(x, z), fullChunk(x, z));
        }
        // An already-written slot in the next region also closes the interior gap.
        var occupied = new ChunkPos(32, 1);
        new RegionFileWriter().write(
                region(destination).resolveSibling("r.1.0.mca"), Map.of(occupied, imported.remove(occupied)));
        var nativeBlend = NbtValue.compound(
                Map.of("min_section", NbtValue.intValue(0), "max_section", NbtValue.intValue(16)));
        imported.put(
                new ChunkPos(33, 0), withTag(fullChunk(33, 0), SeedTerrainDigest.BLENDING_DATA, nativeBlend));
        imported.put(new ChunkPos(34, 0), withTag(fullChunk(34, 0), "Status", NbtValue.stringValue("noise")));
        var foreign = NbtValue.compound(Map.of(SeedTerrainDigest.BLENDING_ATTACHMENT,
                NbtValue.stringValue("foreign"), SeedTerrainDigest.ATTACHMENT,
                NbtValue.stringValue("foreign seed"), SeedTerrainDigest.CAPTURE_RECEIPT,
                NbtValue.stringValue("foreign capture"), "example:other", NbtValue.stringValue("keep")));
        imported.put(new ChunkPos(30, 0),
                withTag(withTag(fullChunk(30, 0), SeedTerrainDigest.BLENDING_DATA, nativeBlend),
                        SeedTerrainDigest.ATTACHMENTS, foreign));
        writeRegion(source, filteredRegion(imported, 0));
        new RegionFileWriter().write(region(source).resolveSibling("r.1.0.mca"), filteredRegion(imported, 1));
        String netherRegion = "dimensions/minecraft/the_nether/region/r.0.0.mca";
        new RegionFileWriter().write(
                source.resolve(netherRegion), Map.of(new ChunkPos(0, 0), fullChunk(0, 0)));

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertEquals(imported.size() + 1, importer.plan(options).chunksToWrite());
        importer.execute(importer.plan(options), false);
        var regions = new RegionFileStore();
        var left = regions.read(region(destination));
        var right = regions.read(region(destination).resolveSibling("r.1.0.mca"));
        var border = regions.chunk(left.get(30));
        var blend = (NbtValue.CompoundValue) border.values().get(SeedTerrainDigest.BLENDING_DATA);
        assertEquals(NbtValue.intValue(-4), blend.values().get("min_section"));
        assertEquals(NbtValue.intValue(20), blend.values().get("max_section"));
        var attachments = (NbtValue.CompoundValue) border.values().get(SeedTerrainDigest.ATTACHMENTS);
        assertEquals(
                NbtValue.stringValue(EPOCH), attachments.values().get(SeedTerrainDigest.BLENDING_ATTACHMENT));
        assertEquals(NbtValue.stringValue("keep"), attachments.values().get("example:other"));
        assertFalse(SeedTerrainEvidence.hasReceipt(border));
        assertTrue(SeedTerrainEvidence.hasCaptureReceipt(border));
        assertFalse(regions.chunk(left.get(63)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
        assertEquals(nativeBlend, regions.chunk(right.get(1)).values().get(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(right.get(2)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(regions.read(destination.resolve(netherRegion)).get(0))
                        .values()
                        .containsKey(SeedTerrainDigest.BLENDING_DATA));

        writeSeededMetadata(destination, true);
        byte[] leftBefore = Files.readAllBytes(region(destination));
        var recovery = importer.plan(options);
        assertEquals(0, recovery.chunksToWrite());
        assertEquals(imported.size() + 1, recovery.chunksToVerifyOnly());
        importer.execute(recovery, false);
        assertArrayEquals(leftBefore, Files.readAllBytes(region(destination)));
        assertEquals(0, importer.plan(options).chunksToVerifyOnly());
    }

    @Test
    void replacingACapturedChunkKeepsOnlyItsCurrentDestinationBlendMarker() throws Exception {
        Path source = source();
        Path destination = destination();
        writeSeededMetadata(destination, true);
        var staleEpoch = "8dd98c18-84ad-4e2d-a1d5-af5ba16b02a5";
        var destinationChunks = new HashMap<ChunkPos, NbtValue.CompoundValue>();
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 6; x++) destinationChunks.put(new ChunkPos(x, z), fullChunk(x, z));
        }
        var oldBlend = NbtValue.compound(Map.of("min_section", NbtValue.intValue(-4), "max_section",
                NbtValue.intValue(20), "heights", NbtValue.stringValue("old cache")));
        var current = new ChunkPos(1, 1);
        var stale = new ChunkPos(4, 1);
        destinationChunks.put(current,
                withTag(withTag(fullChunk(1, 1), SeedTerrainDigest.BLENDING_DATA, oldBlend),
                        SeedTerrainDigest.ATTACHMENTS,
                        NbtValue.compound(
                                Map.of(SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue(EPOCH)))));
        destinationChunks.put(stale,
                withTag(withTag(fullChunk(4, 1), SeedTerrainDigest.BLENDING_DATA, oldBlend),
                        SeedTerrainDigest.ATTACHMENTS,
                        NbtValue.compound(Map.of(
                                SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue(staleEpoch)))));
        writeRegion(destination, destinationChunks);
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates("minecraft:overworld",
                    Map.of(new ChunkIndexStore.ChunkCoordinate(1, 1), CAPTURE - 1,
                            new ChunkIndexStore.ChunkCoordinate(4, 1), CAPTURE - 1),
                    ChunkIndexStore.REPLAY_IMPORT);
        }
        writeRegion(source, Map.of(current, fullChunk(1, 1), stale, fullChunk(4, 1)));

        var importer = new ChunkImporter();
        var plan = importer.plan(mirrorOptions(source, destination));
        assertEquals(2, plan.chunksToWrite());
        importer.execute(plan, false);
        var regions = new RegionFileStore();
        var installed = regions.read(region(destination));
        var replacement = regions.chunk(installed.get(33));
        assertTrue(SeedTerrainEvidence.ownsBlend(replacement, EPOCH));
        assertEquals(NbtValue.compound(Map.of(
                             "min_section", NbtValue.intValue(-4), "max_section", NbtValue.intValue(20))),
                replacement.values().get(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(installed.get(36)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
    }

    @Test
    void seededImportCollapsesDuplicateBiomePaletteEntriesWithoutChangingCells() throws Exception {
        Path source = source();
        Path destination = destination();
        writeSeededMetadata(destination, false);
        var original = fullChunk(0, 0);
        var section = (NbtValue.CompoundValue) ((NbtValue.ListValue) original.values().get("sections"))
                              .values()
                              .getFirst();
        var biomeIds = List.<NbtValue>of(NbtValue.stringValue("worldmirror:mirror_overworld"),
                NbtValue.stringValue("minecraft:plains"), NbtValue.stringValue("minecraft:forest"));
        // The first three cells refer to the mirror biome, forest, and an existing plains entry.
        var palette = NbtValue.compound(Map.of("palette", NbtValue.list(NbtTagId.STRING, biomeIds), "data",
                NbtValue.longArray(new long[] {0x18, 0})));
        var captured = withTag(original, "sections",
                NbtValue.list(NbtTagId.COMPOUND, List.of(withTag(section, "biomes", palette))));
        writeRegion(source, Map.of(new ChunkPos(0, 0), captured));

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        importer.execute(importer.plan(options), false);
        var regions = new RegionFileStore();
        var installed = regions.chunk(regions.read(region(destination)).get(0));
        var readSection = (NbtValue.CompoundValue) ((NbtValue.ListValue) installed.values().get("sections"))
                                  .values()
                                  .getFirst();
        var readBiomes = (NbtValue.CompoundValue) readSection.values().get("biomes");
        assertEquals(
                biomeIds.subList(1, 3), ((NbtValue.ListValue) readBiomes.values().get("palette")).values());
        assertArrayEquals(
                new long[] {2}, ((NbtValue.LongArrayValue) readBiomes.values().get("data")).value());
        assertFalse(installed.values().containsKey(SeedTerrainDigest.BLENDING_DATA));
    }

    @Test
    void resumesASeedReplacementAfterMcaCommitWithoutRewritingTerrain() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        var capture = chunk(0, "minecraft:stone", CAPTURE);
        writeRegion(source, Map.of(new ChunkPos(0, 0), capture));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0),
                        SeedTerrainEvidence.prepareReplay(
                                capture, "mirror:test", CAPTURE, true, null, null)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordSeedGeneratedIfMissing(
                            "minecraft:overworld", Set.of(new ChunkIndexStore.ChunkCoordinate(0, 0)));
        }
        byte[] installed = Files.readAllBytes(region(destination));
        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        var retry = importer.plan(options);
        assertEquals(0, retry.chunksToWrite());
        assertEquals(1, retry.chunksToVerifyOnly());

        importer.execute(retry, false);

        assertArrayEquals(installed, Files.readAllBytes(region(destination)));
        try (var connection = openIndex(destination)) {
            var row = new ChunkIndexStore(connection, SOURCE).queryAll("minecraft:overworld").getFirst();
            assertEquals(ChunkIndexStore.REPLAY_IMPORT, row.updateSource());
            assertEquals(CAPTURE, row.updateTime());
        }
        assertEquals(0, importer.plan(options).chunksToVerifyOnly());
    }

    @Test
    void replacesPristineRetainedSeedAcrossSeedChangesButPreservesEditedAndHigherPriorityTerrain()
            throws Exception {
        Path source = source();
        Path destination = destination();
        Path metadata = destination.resolve("worldmirror_meta.json");
        writeMirrorMetadata(destination);
        String voidMetadata = Files.readString(metadata);
        String priorEpoch = "16ce57f5-32df-4590-8d9b-1f05e079f044";
        writeRegion(source,
                Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE), new ChunkPos(1, 0),
                        chunk(1, "minecraft:stone", CAPTURE), new ChunkPos(2, 0),
                        chunk(2, "minecraft:stone", CAPTURE)));
        writeRegion(destination,
                Map.of(new ChunkPos(0, 0), withSeedReceipt(chunk(0, "minecraft:dirt", 0), priorEpoch),
                        new ChunkPos(1, 0), withSeedReceipt(chunk(1, "minecraft:dirt", 0), priorEpoch, true),
                        new ChunkPos(2, 0), withSeedReceipt(chunk(2, "minecraft:dirt", 0), priorEpoch)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE)
                    .recordUpdates("minecraft:overworld",
                            Map.of(new ChunkIndexStore.ChunkCoordinate(2, 0), CAPTURE - 1), "player");
        }

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertEquals(
                1, importer.plan(options).newerReplaced(), "removed seed with ordinary terrain retained");
        Files.writeString(metadata,
                voidMetadata.replace("\"mode\":\"VOID\"",
                        "\"mode\":\"SEEDED\",\"seedEpoch\":\"e85e5d22-fb32-4e25-b59e-0bdd87bb26c2\""));
        var plan = importer.plan(options);
        assertEquals(1, plan.newerReplaced(), "terrain retained under a new seed epoch");
        assertEquals(2, plan.preserved());
        importer.execute(plan, false);
        var regions = new RegionFileStore();
        var slots = regions.read(region(destination));
        assertEquals("minecraft:stone", blockName(regions.chunk(slots.get(0))));
        assertEquals("minecraft:dirt", blockName(regions.chunk(slots.get(1))));
        assertEquals("minecraft:dirt", blockName(regions.chunk(slots.get(2))));
    }

    private static NbtValue.CompoundValue withSeedReceipt(NbtValue.CompoundValue chunk, String epoch) {
        return withSeedReceipt(chunk, epoch, false);
    }

    private static NbtValue.CompoundValue withSeedReceipt(
            NbtValue.CompoundValue chunk, String epoch, boolean edited) {
        return withSeedReceipt(chunk, epoch, edited, "minecraft:dirt");
    }

    private static NbtValue.CompoundValue withSeedReceipt(
            NbtValue.CompoundValue chunk, String epoch, boolean edited, String baselineBlock) {
        String digest = SeedTerrainDigest.digest(Map.of("sections",
                List.of(Map.of("Y", (byte) 0, "block_states",
                        Map.of("palette", List.of(Map.of("Name", baselineBlock))), "biomes",
                        Map.of("palette", List.of("minecraft:plains"))))));
        var fields = new HashMap<>(chunk.values());
        fields.put(SeedTerrainDigest.ATTACHMENTS,
                NbtValue.compound(Map.of(SeedTerrainDigest.ATTACHMENT,
                        NbtValue.compound(Map.of(SeedTerrainDigest.EPOCH, NbtValue.stringValue(epoch),
                                SeedTerrainDigest.SEED_SCHEMA,
                                NbtValue.intValue(SeedTerrainDigest.SEED_SCHEMA_VERSION),
                                SeedTerrainDigest.SEED_EDITED, NbtValue.byteValue(edited),
                                SeedTerrainDigest.DATA_VERSION, NbtValue.intValue(4903),
                                SeedTerrainDigest.BASELINE, NbtValue.stringValue(digest))))));
        return NbtValue.compound(fields);
    }

    private static void writeSeededMetadata(Path destination, boolean blend) throws IOException {
        Files.writeString(destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"mirror:test",
                 "parentNodeId":"remote:logical-source","kind":"MIRROR","role":"SYNC","sourceId":"%s",
                 "lastSyncTime":0,"generation":{"mode":"SEEDED","seedEpoch":"%s","blendTerrain":%s}}
                """.formatted(SOURCE, EPOCH, blend));
    }

    private static Map<ChunkPos, NbtValue.CompoundValue> filteredRegion(
            Map<ChunkPos, NbtValue.CompoundValue> chunks, int regionX) {
        var selected = new HashMap<ChunkPos, NbtValue.CompoundValue>();
        chunks.forEach((position, chunk) -> {
            if (position.x() >> 5 == regionX)
                selected.put(position, chunk);
        });
        return selected;
    }

    private NbtValue.CompoundValue fullChunk(int x, int z) {
        return withTag(withTag(chunk(x, "minecraft:stone", CAPTURE), "zPos", NbtValue.intValue(z)), "Status",
                NbtValue.stringValue("minecraft:full"));
    }

    private static NbtValue.CompoundValue withTag(NbtValue.CompoundValue chunk, String key, NbtValue value) {
        var fields = new HashMap<>(chunk.values());
        fields.put(key, value);
        return NbtValue.compound(fields);
    }
}
