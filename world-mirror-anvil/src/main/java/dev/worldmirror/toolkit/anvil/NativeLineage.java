package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.format.WorldLineage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves native cached locations back to the edge or identity stored by the save. */
final class NativeLineage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOCAL_IDENTITY_BYTES = 16 * 1024;

    private NativeLineage() {}

    static WorldLineage.Trace verify(Path directory, JsonNode document, String destination,
            WorldLineage.Trace cached) throws IOException {
        var locations = document.path("locations");
        var nodes = document.path("nodes");
        rejectWithheldNode(directory, locations, nodes, destination);
        for (int i = 0; i < cached.links().size(); i++) {
            var link = cached.links().get(i);
            if ((WorldLineage.allowsParent(link.kind()) || link.kind() == WorldLineage.Kind.LOCAL)
                    && !verifyLink(directory, nodes.path(link.id()).path("location"), link)) {
                return new WorldLineage.Trace(
                        WorldLineage.State.MISSING, cached.links().subList(0, i), link.id());
            }
        }
        if (cached.stopId() != null)
            rejectWithheldNode(directory, locations, nodes, cached.stopId());
        return cached;
    }

    private static void rejectWithheldNode(Path directory, JsonNode locations, JsonNode nodes, String id)
            throws IOException {
        if (!nodes.has(id) && locations.has(id)
                && Files.exists(saveAt(directory, locations.path(id), id), LinkOption.NOFOLLOW_LINKS)) {
            // Inventory omits cached nodes with ambiguous identities or cyclic ancestry.
            throw new IOException(
                    "native graph withheld world " + id + "; refresh the graph in World Mirror");
        }
    }

    private static boolean verifyLink(Path directory, JsonNode location, WorldLineage.Link cached)
            throws IOException {
        Path save = saveAt(directory, location, cached.id());
        if (Files.notExists(save, LinkOption.NOFOLLOW_LINKS))
            return false;
        ImportSavePaths.requireRecoveredSave(save);

        if (cached.kind() == WorldLineage.Kind.LOCAL)
            return verifyLocal(save, cached);
        Path metadata = save.resolve(MirrorFormat.METADATA_FILE);
        ImportSavePaths.requirePlainFile(metadata);
        if (Files.notExists(metadata, LinkOption.NOFOLLOW_LINKS))
            return false;
        var identity = MirrorImportMetadata.read(metadata).identity();
        var observed = new WorldLineage.Link(
                identity.nodeId(), WorldLineage.kindOfId(identity.nodeId()), identity.parentNodeId());
        if (!observed.equals(cached))
            throw mismatch(cached);
        return true;
    }

    private static Path saveAt(Path directory, JsonNode location, String id) throws IOException {
        if (!location.isObject())
            throw invalidLocation(id);
        String root = switch (location.path("root").asText("")) {
            case "SAVES" -> "saves";
            case "DOWNLOADED" -> "downloaded_worlds";
            default -> throw invalidLocation(id);
        };
        String folder = location.path("folder").textValue();
        if (!validFolder(folder))
            throw invalidLocation(id);
        Path saveRoot = directory.getParent().getParent().resolve(root);
        Path save;
        try {
            save = saveRoot.resolve(folder);
        } catch (java.nio.file.InvalidPathException badPath) {
            throw new IOException("invalid native location for world " + id, badPath);
        }
        ImportSavePaths.requirePlainDirectory(saveRoot);
        ImportSavePaths.requirePlainDirectory(save);
        if (Files.exists(save, LinkOption.NOFOLLOW_LINKS) && !save.toRealPath().equals(save)) {
            throw invalidLocation(id);
        }
        return save;
    }

    private static boolean verifyLocal(Path save, WorldLineage.Link cached) throws IOException {
        Path identity = save.resolve("worldmirror_identity.json");
        ImportSavePaths.requirePlainFile(identity);
        if (Files.notExists(identity, LinkOption.NOFOLLOW_LINKS))
            return false;
        if (!Files.notExists(save.resolve(MirrorFormat.METADATA_FILE), LinkOption.NOFOLLOW_LINKS)) {
            throw mismatch(cached);
        }
        byte[] bytes;
        try (var input = Files.newInputStream(identity, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_LOCAL_IDENTITY_BYTES + 1);
        }
        if (bytes.length > MAX_LOCAL_IDENTITY_BYTES)
            throw mismatch(cached);
        JsonNode document = JSON.reader()
                                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .readTree(bytes);
        if (!document.path("schema").isInt() || document.path("schema").intValue() != 1
                || !Objects.equals(cached.id(), document.path("nodeId").textValue())) {
            throw mismatch(cached);
        }
        return true;
    }

    private static boolean validFolder(String folder) {
        return folder != null && !folder.isBlank() && !folder.equals(".") && !folder.equals("..")
                && folder.indexOf('/') < 0 && folder.indexOf('\\') < 0 && folder.indexOf(':') < 0
                && folder.chars().noneMatch(Character::isISOControl);
    }

    private static IOException invalidLocation(String id) {
        return new IOException("invalid native location for world " + id);
    }

    private static IOException mismatch(WorldLineage.Link link) {
        return new IOException(
                "upstream " + link.id() + " differs from the saved world; refresh the graph in World Mirror");
    }
}
