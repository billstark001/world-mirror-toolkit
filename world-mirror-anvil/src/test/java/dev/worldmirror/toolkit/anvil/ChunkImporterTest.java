package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.WorldLayout;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkImporterTest {
    @TempDir Path temporary;
    private static final String SOURCE = "server:example.test";
    private static final long CAPTURE = 2_000_000L;
    private static final String EPOCH = "16ce57f5-32df-4590-8d9b-1f05e079f044";

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
        assertTrue(assertThrows(IOException.class, () -> importer.plan(options)).getMessage()
                .contains("upgrade the chunk index"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertFalse(new ChunkIndexStore(connection, SOURCE).hasRegisteredSource(ChunkIndexStore.REPLAY_IMPORT));
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
    void timestampImportOnlyReplacesSeedTerrainThatMatchesItsCurrentReceipt() throws Exception {
        Path source = source();
        Path destination = destination();
        String epoch = "16ce57f5-32df-4590-8d9b-1f05e079f044";
        Files.writeString(destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"mirror:test",
                 "parentNodeId":"remote:logical-source","kind":"MIRROR","role":"SYNC","sourceId":"%s",
                 "lastSyncTime":0,"generation":{"mode":"SEEDED","seedEpoch":"%s"}}
                """.formatted(SOURCE, epoch));
        writeRegion(source, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE),
                new ChunkPos(1, 0), chunk(1, "minecraft:stone", CAPTURE),
                new ChunkPos(2, 0), chunk(2, "minecraft:stone", CAPTURE),
                new ChunkPos(3, 0), chunk(3, "minecraft:stone", CAPTURE),
                new ChunkPos(4, 0), chunk(4, "minecraft:stone", CAPTURE),
                new ChunkPos(5, 0), chunk(5, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(
                new ChunkPos(0, 0), withSeedReceipt(chunk(0, "minecraft:dirt", 0), epoch),
                new ChunkPos(1, 0), withSeedReceipt(chunk(1, "minecraft:gold_block", 0), epoch),
                new ChunkPos(2, 0), chunk(2, "minecraft:dirt", 0),
                new ChunkPos(3, 0), chunk(3, "minecraft:air", 0),
                new ChunkPos(4, 0), withSeedReceipt(chunk(4, "minecraft:dirt", 0), epoch),
                new ChunkPos(5, 0), withSeedReceipt(chunk(5, "minecraft:dirt", 0), epoch)));
        Path entities = region(destination).getParent().resolveSibling("entities").resolve("r.0.0.mca");
        new RegionFileWriter().write(entities, Map.of(new ChunkPos(4, 0), chunk(4, "minecraft:air", 0)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE).recordSeedGeneratedIfMissing("minecraft:overworld", Set.of(
                    new ChunkIndexStore.ChunkCoordinate(0, 0), new ChunkIndexStore.ChunkCoordinate(1, 0),
                    new ChunkIndexStore.ChunkCoordinate(2, 0), new ChunkIndexStore.ChunkCoordinate(3, 0)));
        }

        var importer = new ChunkImporter();
        var emptyOnly = new ChunkImporter.Options(source, destination,
                ChunkImporter.Mode.MIRROR, ChunkImporter.Policy.EMPTY_ONLY, "auto", true);
        assertEquals(1, importer.plan(emptyOnly).emptyReplaced());
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
        assertEquals("minecraft:stone", blockName(regions.chunk(regions.read(region(destination)).get(3))));
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
        new RegionFileWriter().write(region(destination).resolveSibling("r.1.0.mca"),
                Map.of(occupied, imported.remove(occupied)));
        var nativeBlend = NbtValue.compound(Map.of("min_section", NbtValue.intValue(0),
                "max_section", NbtValue.intValue(16)));
        imported.put(new ChunkPos(33, 0), withTag(fullChunk(33, 0), SeedTerrainDigest.BLENDING_DATA, nativeBlend));
        imported.put(new ChunkPos(34, 0), withTag(fullChunk(34, 0), "Status", NbtValue.stringValue("noise")));
        var foreign = NbtValue.compound(Map.of(
                SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue("foreign"),
                SeedTerrainDigest.ATTACHMENT, NbtValue.stringValue("foreign seed"),
                SeedTerrainDigest.CAPTURE_RECEIPT, NbtValue.stringValue("foreign capture"),
                "example:other", NbtValue.stringValue("keep")));
        imported.put(new ChunkPos(30, 0), withTag(withTag(fullChunk(30, 0),
                SeedTerrainDigest.BLENDING_DATA, nativeBlend), SeedTerrainDigest.ATTACHMENTS, foreign));
        writeRegion(source, filteredRegion(imported, 0));
        new RegionFileWriter().write(region(source).resolveSibling("r.1.0.mca"), filteredRegion(imported, 1));
        String netherRegion = "dimensions/minecraft/the_nether/region/r.0.0.mca";
        new RegionFileWriter().write(source.resolve(netherRegion), Map.of(new ChunkPos(0, 0), fullChunk(0, 0)));

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
        assertEquals(NbtValue.stringValue(EPOCH), attachments.values().get(SeedTerrainDigest.BLENDING_ATTACHMENT));
        assertEquals(NbtValue.stringValue("keep"), attachments.values().get("example:other"));
        assertFalse(SeedTerrainEvidence.hasReceipt(border));
        assertTrue(SeedTerrainEvidence.hasCaptureReceipt(border));
        assertFalse(regions.chunk(left.get(63)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
        assertEquals(nativeBlend, regions.chunk(right.get(1)).values().get(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(right.get(2)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(regions.read(destination.resolve(netherRegion)).get(0))
                .values().containsKey(SeedTerrainDigest.BLENDING_DATA));

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
        var oldBlend = NbtValue.compound(Map.of("min_section", NbtValue.intValue(-4),
                "max_section", NbtValue.intValue(20), "heights", NbtValue.stringValue("old cache")));
        var current = new ChunkPos(1, 1);
        var stale = new ChunkPos(4, 1);
        destinationChunks.put(current, withTag(withTag(fullChunk(1, 1), SeedTerrainDigest.BLENDING_DATA, oldBlend),
                SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(Map.of(
                        SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue(EPOCH)))));
        destinationChunks.put(stale, withTag(withTag(fullChunk(4, 1), SeedTerrainDigest.BLENDING_DATA, oldBlend),
                SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(Map.of(
                        SeedTerrainDigest.BLENDING_ATTACHMENT, NbtValue.stringValue(staleEpoch)))));
        writeRegion(destination, destinationChunks);
        try (var connection = openIndex(destination)) {
            var index = new ChunkIndexStore(connection, SOURCE);
            index.recordUpdates("minecraft:overworld", Map.of(new ChunkIndexStore.ChunkCoordinate(1, 1), CAPTURE - 1,
                    new ChunkIndexStore.ChunkCoordinate(4, 1), CAPTURE - 1), ChunkIndexStore.REPLAY_IMPORT);
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
        assertEquals(NbtValue.compound(Map.of("min_section", NbtValue.intValue(-4),
                "max_section", NbtValue.intValue(20))), replacement.values().get(SeedTerrainDigest.BLENDING_DATA));
        assertFalse(regions.chunk(installed.get(36)).values().containsKey(SeedTerrainDigest.BLENDING_DATA));
    }

    @Test
    void seededImportCollapsesDuplicateBiomePaletteEntriesWithoutChangingCells() throws Exception {
        Path source = source();
        Path destination = destination();
        writeSeededMetadata(destination, false);
        var original = fullChunk(0, 0);
        var section = (NbtValue.CompoundValue) ((NbtValue.ListValue) original.values().get("sections")).values().getFirst();
        var biomeIds = List.<NbtValue>of(NbtValue.stringValue("worldmirror:mirror_overworld"),
                NbtValue.stringValue("minecraft:plains"), NbtValue.stringValue("minecraft:forest"));
        // The first three cells refer to the mirror biome, forest, and an existing plains entry.
        var palette = NbtValue.compound(Map.of("palette", NbtValue.list(NbtTagId.STRING, biomeIds),
                "data", NbtValue.longArray(new long[] {0x18, 0})));
        var captured = withTag(original, "sections", NbtValue.list(NbtTagId.COMPOUND,
                List.of(withTag(section, "biomes", palette))));
        writeRegion(source, Map.of(new ChunkPos(0, 0), captured));

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        importer.execute(importer.plan(options), false);
        var regions = new RegionFileStore();
        var installed = regions.chunk(regions.read(region(destination)).get(0));
        var readSection = (NbtValue.CompoundValue) ((NbtValue.ListValue) installed.values().get("sections")).values().getFirst();
        var readBiomes = (NbtValue.CompoundValue) readSection.values().get("biomes");
        assertEquals(biomeIds.subList(1, 3), ((NbtValue.ListValue) readBiomes.values().get("palette")).values());
        assertArrayEquals(new long[] {2}, ((NbtValue.LongArrayValue) readBiomes.values().get("data")).value());
        assertFalse(installed.values().containsKey(SeedTerrainDigest.BLENDING_DATA));
    }

    @Test
    void resumesASeedReplacementAfterMcaCommitWithoutRewritingTerrain() throws Exception {
        Path source = source();
        Path destination = destination();
        writeMirrorMetadata(destination);
        var capture = chunk(0, "minecraft:stone", CAPTURE);
        writeRegion(source, Map.of(new ChunkPos(0, 0), capture));
        writeRegion(destination, Map.of(new ChunkPos(0, 0),
                SeedTerrainEvidence.prepareReplay(capture, "mirror:test", CAPTURE, true, null, null)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE).recordSeedGeneratedIfMissing("minecraft:overworld",
                    Set.of(new ChunkIndexStore.ChunkCoordinate(0, 0)));
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
    void replacesPristineRetainedSeedAcrossSeedChangesButPreservesEditedAndHigherPriorityTerrain() throws Exception {
        Path source = source();
        Path destination = destination();
        Path metadata = destination.resolve("worldmirror_meta.json");
        writeMirrorMetadata(destination);
        String voidMetadata = Files.readString(metadata);
        String priorEpoch = "16ce57f5-32df-4590-8d9b-1f05e079f044";
        writeRegion(source, Map.of(
                new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE),
                new ChunkPos(1, 0), chunk(1, "minecraft:stone", CAPTURE),
                new ChunkPos(2, 0), chunk(2, "minecraft:stone", CAPTURE)));
        writeRegion(destination, Map.of(
                new ChunkPos(0, 0), withSeedReceipt(chunk(0, "minecraft:dirt", 0), priorEpoch),
                new ChunkPos(1, 0), withSeedReceipt(chunk(1, "minecraft:dirt", 0), priorEpoch, true),
                new ChunkPos(2, 0), withSeedReceipt(chunk(2, "minecraft:dirt", 0), priorEpoch)));
        try (var connection = openIndex(destination)) {
            new ChunkIndexStore(connection, SOURCE).recordUpdates("minecraft:overworld",
                    Map.of(new ChunkIndexStore.ChunkCoordinate(2, 0), CAPTURE - 1), "player");
        }

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertEquals(1, importer.plan(options).newerReplaced(), "removed seed with ordinary terrain retained");
        Files.writeString(metadata, voidMetadata.replace("\"mode\":\"VOID\"",
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

    private static NbtValue.CompoundValue withSeedReceipt(NbtValue.CompoundValue chunk, String epoch, boolean edited) {
        String digest = SeedTerrainDigest.digest(Map.of("sections", List.of(Map.of("Y", (byte) 0,
                "block_states", Map.of("palette", List.of(Map.of("Name", "minecraft:dirt"))),
                "biomes", Map.of("palette", List.of("minecraft:plains"))))));
        var fields = new HashMap<>(chunk.values());
        fields.put(SeedTerrainDigest.ATTACHMENTS, NbtValue.compound(Map.of(
                SeedTerrainDigest.ATTACHMENT, NbtValue.compound(Map.of(
                        SeedTerrainDigest.EPOCH, NbtValue.stringValue(epoch),
                        SeedTerrainDigest.SEED_SCHEMA, NbtValue.intValue(SeedTerrainDigest.SEED_SCHEMA_VERSION),
                        SeedTerrainDigest.SEED_EDITED, NbtValue.byteValue(edited),
                        SeedTerrainDigest.DATA_VERSION, NbtValue.intValue(4903),
                        SeedTerrainDigest.BASELINE, NbtValue.stringValue(digest))))));
        return NbtValue.compound(fields);
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
    void detachedMirrorsKeepTheirIndexNamespaceAndRequireExplicitReplayApproval() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "index:preserved");
        Path metadata = destination.resolve("worldmirror_meta.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(metadata.toFile());
        document.remove("parentNodeId");
        json.writeValue(metadata.toFile(), document);
        Path graph = temporary.resolve("graph/world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");
        var routing = new ChunkImporter.Routing(graph, null);
        var importer = new ChunkImporter();
        var requireMatch = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(requireMatch, false, routing))
                .getMessage().contains("--allow-source-mismatch"));
        var approved = importer.plan(mirrorOptions(source, destination), false, routing);
        assertEquals("index:preserved", approved.sourceId());
        importer.execute(approved, false);
        assertFalse(json.readTree(metadata.toFile()).hasNonNull("parentNodeId"));
    }

    @Test
    void validatesAnAutomaticallyDiscoveredEndpointRuleAgainstAChainedMirror() throws Exception {
        Path source = source();
        Path destination = temporary.resolve("game/saves/test");
        Files.createDirectories(destination);
        Files.copy(source.resolve("level.dat"), destination.resolve("level.dat"));
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "mirror:upstream", "index:namespace");
        Path graph = temporary.resolve("game/config/worldmirror/world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");

        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        var importer = new ChunkImporter();
        Path alternate = temporary.resolve("alternate/world-graph.json");
        writeRoutingGraph(alternate, "\"remote:logical-source\"");
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(alternate, null)))
                .getMessage().contains("cannot override"));
        Path journal = Files.createDirectory(graph.resolveSibling(".worldmirror-transaction-pending"));
        var fallback = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(fallback))
                .getMessage().contains("world graph requires recovery"));
        Files.delete(journal);

        var plan = importer.plan(options);
        assertEquals("remote:logical-source", plan.asMap().get("replay_source"));
        assertEquals("index:namespace", plan.sourceId());
        importer.execute(plan, false);
        try (var connection = openIndex(destination)) {
            assertEquals(1, new ChunkIndexStore(connection, "index:namespace").queryAll("minecraft:overworld").size());
        }
    }

    @Test
    void requiresAnExplicitMatchingSourceAndRechecksTheSelectedLineageUnderTheLock() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "mirror:upstream", "index:namespace");
        Path graph = temporary.resolve("world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\", \"remote:other\"");

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, null)))
                .getMessage().contains("--replay-source"));
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:missing")))
                .getMessage().contains("not a candidate"));
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:other")))
                .getMessage().contains("does not match"));

        var plan = importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:logical-source"));
        Files.writeString(graph, Files.readString(graph).replace(
                "\"kind\":\"MIRROR\",\"parentId\":\"remote:logical-source\"",
                "\"kind\":\"MIRROR\",\"parentId\":\"remote:other\""));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                .getMessage().contains("does not match"));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(plan.database()));
    }

    @Test
    void aSourceBoundThroughAnAddressAliasAlsoRequiresExplicitReplayConfirmation() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "remote:other", "index:namespace");
        Path graph = temporary.resolve("world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(graph.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("endpointRules")).putObject("alias.example.test")
                .put("addressGroupId", "endpoint:test").put("idPolicy", "PREFER_BOUND_ID")
                .putArray("sourceIds").add("remote:other");
        document.putArray("stableBindings").addObject()
                .put("addressGroupId", "endpoint:test").put("provider", "xaero").put("advertisedId", "1")
                .put("sourceId", "remote:other");
        mapper.writeValue(graph.toFile(), document);

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, null)))
                .getMessage().contains("--replay-source"));
        assertEquals("remote:other", importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:other"))
                .asMap().get("replay_source"));
    }

    private static void writeRoutingGraph(Path graph, String sources) throws IOException {
        Files.createDirectories(graph.getParent());
        Files.writeString(graph, """
                {"schema":2,"nodes":{
                    "mirror:upstream":{"id":"mirror:upstream","kind":"MIRROR","parentId":"remote:logical-source"},
                    "remote:logical-source":{"id":"remote:logical-source","kind":"REMOTE"},
                    "remote:other":{"id":"remote:other","kind":"REMOTE","parentId":null}},
                 "endpointRules":{"%s:25565":{"addressGroupId":"endpoint:test","idPolicy":"PREFER_BOUND_ID",
                    "sourceIds":[%s],"defaultSourceId":"remote:logical-source"}},"stableBindings":[]}
                """.formatted(SOURCE.substring("server:".length()).toUpperCase(java.util.Locale.ROOT), sources));
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
        writeGraphMetadata(destination, nodeId, "remote:logical-source", indexSource);
    }

    private static void writeGraphMetadata(Path destination, String nodeId, String parentId, String indexSource)
            throws IOException {
        Files.writeString(destination.resolve("worldmirror_meta.json"), """
                {"format":"worldmirror","metadataSchema":2,"nodeId":"%s","parentNodeId":"%s",
                 "kind":"MIRROR","role":"SYNC","sourceId":"%s","lastSyncTime":0,
                 "generation":{"mode":"VOID","revision":1,"assetRevision":3,"dataVersion":4903,"cleanupRevision":1}}
                """.formatted(nodeId, parentId, indexSource));
    }

    private static Connection openIndex(Path destination) throws IOException, SQLException {
        Path database = destination.resolve("data/world_mirror.sqlite");
        Files.createDirectories(database.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        ChunkIndexStore.initialize(connection);
        return connection;
    }

    private Path source() throws IOException {
        return source(Map.of("minecraft:overworld", 3));
    }

    private Path source(Map<String, Integer> countByDimension) throws IOException {
        Path source = temporary.resolve("source");
        AnalysisExportMetadata.create("26.2", 4903, WorldLayout.NAMESPACED, SOURCE,
                "world-test", "1", CAPTURE, CAPTURE, 1000, List.of("test.mcpr"),
                countByDimension).write(source);
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
            if (position.x() >> 5 == regionX) selected.put(position, chunk);
        });
        return selected;
    }

    private NbtValue.CompoundValue fullChunk(int x, int z) {
        return withTag(withTag(chunk(x, "minecraft:stone", CAPTURE), "zPos", NbtValue.intValue(z)),
                "Status", NbtValue.stringValue("minecraft:full"));
    }

    private static NbtValue.CompoundValue withTag(NbtValue.CompoundValue chunk, String key, NbtValue value) {
        var fields = new HashMap<>(chunk.values());
        fields.put(key, value);
        return NbtValue.compound(fields);
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
