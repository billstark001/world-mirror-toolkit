package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.format.WorldLineage;
import io.github.billstark001.worldmirror.legacy.LegacyMirrorMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Validates the graph-backed destination identity and advances its capture-time floor. */
record MirrorImportMetadata(ObjectNode document, Identity identity, long lastSyncTime) {
    record Identity(String sourceId, String nodeId, String parentNodeId, String kind, String role) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int GRAPH_METADATA_SCHEMA = 2;
    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;

    static MirrorImportMetadata read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mirror metadata is missing or is not a regular file: " + file);
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        }
        if (bytes.length > MAX_METADATA_BYTES)
            throw new IOException("mirror metadata exceeds size limit: " + file);
        if (!(JSON.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(bytes)
                            instanceof ObjectNode value)
                || !MirrorFormat.FORMAT.equals(value.path("format").textValue())) {
            throw new IOException("unsupported mirror metadata format/schema in " + file);
        }
        var schema = value.path("metadataSchema");
        if (LegacyMirrorMetadata.requiresMigration(schema)) {
            throw new IOException("upgrade the mirror metadata in World Mirror before importing: " + file);
        }
        if (!schema.isIntegralNumber() || !schema.canConvertToInt()
                || schema.intValue() != GRAPH_METADATA_SCHEMA) {
            throw new IOException("unsupported mirror metadata format/schema in " + file);
        }
        String sourceId = textField(value, "sourceId");
        if (sourceId == null)
            throw new IOException("mirror metadata lacks sourceId: " + file);
        var sync = value.path("lastSyncTime");
        if (!sync.isIntegralNumber() || !sync.canConvertToLong() || sync.longValue() < 0) {
            throw new IOException("invalid mirror lastSyncTime: " + file);
        }
        String nodeId = textField(value, "nodeId");
        String parent = textField(value, "parentNodeId");
        String kind = textField(value, "kind");
        String role = textField(value, "role");
        boolean validParent = value.path("parentNodeId").isMissingNode()
                || value.path("parentNodeId").isNull() || WorldLineage.kindOfId(parent) != null;
        var nodeKind = WorldLineage.kindOfId(nodeId);
        boolean mirror = "MIRROR".equals(kind) && nodeKind == WorldLineage.Kind.MIRROR && validParent
                && ("SYNC".equals(role) || "SNAPSHOT".equals(role));
        boolean snapshot = "SNAPSHOT".equals(kind) && nodeKind == WorldLineage.Kind.SNAPSHOT
                && "SNAPSHOT".equals(role) && validParent;
        if (!mirror && !snapshot)
            throw new IOException("invalid mirror graph identity: " + file);
        return new MirrorImportMetadata(
                value, new Identity(sourceId, nodeId, parent, kind, role), sync.longValue());
    }

    private static String textField(ObjectNode document, String name) {
        String value = document.path(name).textValue();
        return value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl) ? null
                                                                                                   : value;
    }

    boolean seeded() {
        return "SEEDED".equals(document.path("generation").path("mode").textValue());
    }

    String blendEpoch(Path metadata) throws IOException {
        var generation = document.path("generation");
        if (!seeded() || !generation.path("blendTerrain").booleanValue())
            return null;
        return validateEpoch(generation.path("seedEpoch").textValue(), metadata);
    }

    private static String validateEpoch(String epoch, Path metadata) throws IOException {
        try {
            if (epoch != null && UUID.fromString(epoch).toString().equals(epoch))
                return epoch;
        } catch (IllegalArgumentException malformed) {
            // A blend marker must carry the target save's canonical seed epoch.
        }
        throw new IOException("invalid seed blending epoch in " + metadata);
    }

    static void advanceLastSync(Path metadataPath, Identity expected, long newestImported)
            throws IOException {
        MirrorImportMetadata read = read(metadataPath);
        if (!expected.equals(read.identity()))
            throw new IOException("mirror identity changed during import: " + metadataPath);
        ObjectNode metadata = read.document();
        metadata.put("lastSyncTime", Math.max(read.lastSyncTime(), newestImported));
        Path temporary = Files.createTempFile(metadataPath.getParent(), "worldmirror_meta-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), metadata);
            try {
                Files.move(temporary, metadataPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, metadataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
