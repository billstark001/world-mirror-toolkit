package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.worldmirror.toolkit.core.ChunkPos;
import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.WorldGraphLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkImportRoutingTest extends ChunkImportFixture {
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
                        .getMessage()
                        .contains("upgrade the mirror metadata in World Mirror"));
        assertArrayEquals(legacy, Files.readAllBytes(metadata));
        assertTrue(Files.notExists(destination.resolve("session.lock")));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(preview, false))
                        .getMessage()
                        .contains("upgrade the mirror metadata in World Mirror"));
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
                        .getMessage()
                        .contains("--allow-source-mismatch"));

        String indexSource = "server:former.example.test";
        writeGraphMetadata(destination, "mirror:first", indexSource);
        String original = Files.readString(metadata);
        var options = new ChunkImporter.Options(
                source, destination, ChunkImporter.Mode.MIRROR, ChunkImporter.Policy.TIMESTAMP, "auto", true);
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
                        .getMessage()
                        .contains("--allow-source-mismatch"));
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
        Path upstream = Files.createDirectories(destination.resolveSibling("Upstream"));
        writeGraphMetadata(upstream, "mirror:upstream", "remote:logical-source", "server:example.test");

        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        var importer = new ChunkImporter();
        Path alternate = temporary.resolve("alternate/world-graph.json");
        writeRoutingGraph(alternate, "\"remote:logical-source\"");
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(alternate, null)))
                        .getMessage()
                        .contains("cannot override"));
        Path journal = Files.createDirectory(graph.resolveSibling(".worldmirror-transaction-pending"));
        var fallback = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(fallback))
                        .getMessage()
                        .contains("world graph requires recovery"));
        Files.delete(journal);
        Path adoption = graph.resolveSibling(".world-graph-local-adoption.json");
        Files.writeString(adoption, "{}");
        assertTrue(assertThrows(IOException.class, () -> importer.plan(fallback))
                        .getMessage()
                        .contains("world graph requires recovery"));
        Files.delete(adoption);

        var plan = importer.plan(options);
        assertEquals("remote:logical-source", plan.asMap().get("replay_source"));
        assertEquals("index:namespace", plan.sourceId());
        var lease = WorldGraphLease.acquire(graph.getParent());
        try (lease) {
            assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                            .getMessage()
                            .contains("World graph operation is already running"));
            assertTrue(Files.notExists(destination.resolve("session.lock")));
        }
        writeGraphMetadata(upstream, "mirror:upstream", "remote:other", "server:example.test");
        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                        .getMessage()
                        .contains("differs from the saved world"));
        writeGraphMetadata(upstream, "mirror:upstream", "remote:logical-source", "server:example.test");
        importer.execute(plan, false);
        try (var connection = openIndex(destination)) {
            assertEquals(1,
                    new ChunkIndexStore(connection, "index:namespace")
                            .queryAll("minecraft:overworld")
                            .size());
        }
    }

    @Test
    void nativeLineageCannotWaiveAnExistingWorldWithheldByInventory() throws Exception {
        Path source = source();
        Path destination = temporary.resolve("game/saves/Target");
        Files.createDirectories(destination);
        Files.copy(source.resolve("level.dat"), destination.resolve("level.dat"));
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "mirror:upstream", "index:namespace");
        Path graph = temporary.resolve("game/config/worldmirror/world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(graph.toFile());
        var nodes = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("nodes");
        var savedLocation = nodes.remove("mirror:upstream").path("location");
        document.putObject("locations").set("mirror:upstream", savedLocation);
        mapper.writeValue(graph.toFile(), document);
        Path upstream = Files.createDirectories(destination.resolveSibling("Upstream"));
        writeGraphMetadata(upstream, "mirror:upstream", "mirror:first", "index:upstream");

        var importer = new ChunkImporter();
        assertTrue(assertThrows(IOException.class, () -> importer.plan(mirrorOptions(source, destination)))
                        .getMessage()
                        .contains("withheld world mirror:upstream"));
        assertTrue(Files.notExists(destination.resolve("session.lock")));
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
                        .getMessage()
                        .contains("--replay-source"));
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:missing")))
                        .getMessage()
                        .contains("not a candidate"));
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:other")))
                        .getMessage()
                        .contains("does not match"));

        var plan = importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:logical-source"));
        Files.writeString(graph,
                Files.readString(graph).replace("\"kind\":\"MIRROR\",\"parentId\":\"remote:logical-source\"",
                        "\"kind\":\"MIRROR\",\"parentId\":\"remote:other\""));
        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                        .getMessage()
                        .contains("does not match"));
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
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("endpointRules"))
                .putObject("alias.example.test")
                .put("addressGroupId", "endpoint:test")
                .put("idPolicy", "PREFER_BOUND_ID")
                .putArray("sourceIds")
                .add("remote:other");
        document.putArray("stableBindings")
                .addObject()
                .put("addressGroupId", "endpoint:test")
                .put("provider", "xaero")
                .put("advertisedId", "1")
                .put("sourceId", "remote:other");
        mapper.writeValue(graph.toFile(), document);

        var importer = new ChunkImporter();
        var options = mirrorOptions(source, destination);
        assertTrue(assertThrows(IOException.class,
                () -> importer.plan(options, false, new ChunkImporter.Routing(graph, null)))
                        .getMessage()
                        .contains("--replay-source"));
        assertEquals("remote:other",
                importer.plan(options, false, new ChunkImporter.Routing(graph, "remote:other"))
                        .asMap()
                        .get("replay_source"));
    }

    private static void writeRoutingGraph(Path graph, String sources) throws IOException {
        Files.createDirectories(graph.getParent());
        Files.writeString(graph,
                """
                {"schema":2,"nodes":{
                    "mirror:upstream":{"id":"mirror:upstream","kind":"MIRROR","parentId":"remote:logical-source",
                        "location":{"root":"SAVES","folder":"Upstream"}},
                    "remote:logical-source":{"id":"remote:logical-source","kind":"REMOTE"},
                    "remote:other":{"id":"remote:other","kind":"REMOTE","parentId":null}},
                 "endpointRules":{"%s:25565":{"addressGroupId":"endpoint:test","idPolicy":"PREFER_BOUND_ID",
                    "sourceIds":[%s],"defaultSourceId":"remote:logical-source"}},"stableBindings":[]}
                """.formatted(
                        SOURCE.substring("server:".length()).toUpperCase(java.util.Locale.ROOT), sources));
    }

    @Test
    void replaysThroughASnapshotAndRechecksTheEntireChain() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "mirror:upstream", "index:preserved");
        Path graph = temporary.resolve("mixed-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(graph.toFile());
        var nodes = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("nodes");
        var upstream = (com.fasterxml.jackson.databind.node.ObjectNode) nodes.path("mirror:upstream");
        upstream.put("parentId", "snapshot:base");
        nodes.putObject("snapshot:base")
                .put("id", "snapshot:base")
                .put("kind", "SNAPSHOT")
                .put("parentId", "remote:logical-source");
        json.writeValue(graph.toFile(), document);
        var options = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);
        var request = new ChunkImporter.Routing(graph, null);
        var importer = new ChunkImporter();
        var plan = importer.plan(options, false, request);
        assertEquals("remote:logical-source", plan.asMap().get("replay_source"));
        assertEquals("index:preserved", plan.sourceId());

        upstream.put("parentId", "remote:logical-source");
        json.writeValue(graph.toFile(), document);
        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                        .getMessage()
                        .contains("review a fresh plan"));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(plan.database()));

        upstream.put("parentId", "snapshot:base");
        json.writeValue(graph.toFile(), document);
        importer.execute(plan, false);
        assertTrue(Files.isRegularFile(region(destination)));
    }

    @Test
    void onlyMissingLineageEvidenceCanBeWaived() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", "mirror:upstream", "index:preserved");
        Path graph = temporary.resolve("world-graph.json");
        writeRoutingGraph(graph, "\"remote:logical-source\"");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(graph.toFile());
        var nodes = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("nodes");
        var upstream = (com.fasterxml.jackson.databind.node.ObjectNode) nodes.path("mirror:upstream");
        var request = new ChunkImporter.Routing(graph, null);
        var importer = new ChunkImporter();
        var waived = mirrorOptions(source, destination);
        var required = new ChunkImporter.Options(source, destination, ChunkImporter.Mode.MIRROR,
                ChunkImporter.Policy.TIMESTAMP, "auto", false);

        upstream.put("parentId", "snapshot:missing");
        json.writeValue(graph.toFile(), document);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(required, false, request))
                        .getMessage()
                        .contains("missing upstream snapshot:missing"));
        var pending = importer.plan(waived, false, request);
        assertEquals(null, pending.asMap().get("replay_source"));

        var intermediate = nodes.putObject("snapshot:missing")
                                   .put("id", "snapshot:missing")
                                   .put("kind", "SNAPSHOT")
                                   .put("parentId", "mirror:first");
        json.writeValue(graph.toFile(), document);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(waived, false, request))
                        .getMessage()
                        .contains("CYCLE"));

        intermediate.put("kind", "MIRROR");
        json.writeValue(graph.toFile(), document);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(waived, false, request))
                        .getMessage()
                        .contains("INVALID"));

        upstream.put("parentId", "local:a");
        nodes.putObject("local:a").put("id", "local:a").put("kind", "LOCAL");
        json.writeValue(graph.toFile(), document);
        assertTrue(assertThrows(IOException.class, () -> importer.plan(waived, false, request))
                        .getMessage()
                        .contains("does not match"));
    }

    @Test
    void rejectsChangedGraphNodeEvenWhenPathIndexAndTerrainStillMatchThePreview() throws Exception {
        Path source = source();
        Path destination = destination();
        writeRegion(source, Map.of(new ChunkPos(0, 0), chunk(0, "minecraft:stone", CAPTURE)));
        writeGraphMetadata(destination, "mirror:first", SOURCE);
        var importer = new ChunkImporter();
        var options = new ChunkImporter.Options(
                source, destination, ChunkImporter.Mode.MIRROR, ChunkImporter.Policy.TIMESTAMP, "auto", true);
        var plan = importer.plan(options);
        writeGraphMetadata(destination, "mirror:second", SOURCE);

        assertTrue(assertThrows(IOException.class, () -> importer.execute(plan, false))
                        .getMessage()
                        .contains("import plan changed"));
        assertTrue(Files.notExists(region(destination)));
        assertTrue(Files.notExists(plan.database()));
    }
}
