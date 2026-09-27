package dev.worldmirror.toolkit.schema.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a metadata-derived schema skeleton; packet ids still need source/bytecode verification. */
public final class SchemaSkeletonGenerator {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final MojangVersionAssets assets = new MojangVersionAssets();

    public Result generate(Options options) throws IOException, InterruptedException {
        MojangVersionAssets.PreparedAssets prepared = assets.prepare(new MojangVersionAssets.Options(
                options.minecraftVersion(), options.versionJson(), options.clientJar(), options.workDir()));
        JsonNode version = prepared.versionJsonNode();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("schemaVersion", "0.1");
        schema.put("minecraftVersion", version.path("id").asText(options.minecraftVersion()));
        schema.put("protocolVersion", options.protocolVersion() == null || options.protocolVersion().isBlank()
                ? version.path("id").asText(options.minecraftVersion())
                : options.protocolVersion());
        schema.put("networkProtocol", options.networkProtocol());
        schema.put("dataVersion", options.dataVersion());
        schema.put("minSectionY", options.minSectionY());
        schema.put("maxSectionY", options.maxSectionY());
        schema.put("aliases", List.of());
        schema.put("packets", List.of());
        Files.createDirectories(options.out().getParent());
        mapper.writeValue(options.out().toFile(), schema);
        return new Result(options.out(), (String) schema.get("minecraftVersion"), (String) schema.get("protocolVersion"), options.dataVersion());
    }

    public record Options(String minecraftVersion, String protocolVersion, int networkProtocol, int dataVersion, int minSectionY, int maxSectionY, Path versionJson, Path clientJar, Path workDir, Path out) {}

    public record Result(Path out, String minecraftVersion, String protocolVersion, int dataVersion) {
        public Map<String, Object> asMap() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("out", out.toString());
            outMap.put("minecraft_version", minecraftVersion);
            outMap.put("protocol_version", protocolVersion);
            outMap.put("data_version", dataVersion);
            outMap.put("note", "packet ids are not included; verify them from source/bytecode before using for replay decoding");
            return outMap;
        }
    }
}
