package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.worldmirror.toolkit.core.ParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Source-derived registry id mappings used to turn network palette ids into Anvil names. */
public final class RegistryMappings {
    private final int dataVersion;
    private final Map<Integer, BlockState> blockStates;
    private final Map<Integer, String> blockEntityTypes;

    private RegistryMappings(int dataVersion, Map<Integer, BlockState> blockStates, Map<Integer, String> blockEntityTypes) {
        this.dataVersion = dataVersion;
        this.blockStates = Map.copyOf(blockStates);
        this.blockEntityTypes = Map.copyOf(blockEntityTypes);
    }

    public static RegistryMappings load(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            return load(stream);
        }
    }

    public static RegistryMappings loadBundled(String minecraftVersion) throws IOException {
        String resource = "registries/registries_" + minecraftVersion + ".json.gz";
        InputStream stream = RegistryMappings.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) throw new ParseException("no bundled registry mappings for " + minecraftVersion);
        try (InputStream inflated = new GZIPInputStream(stream)) {
            return load(inflated);
        }
    }

    private static RegistryMappings load(InputStream stream) throws IOException {
        JsonNode root = new ObjectMapper().readTree(stream);
        Map<Integer, BlockState> blockStates = new LinkedHashMap<>();
        JsonNode states = root.path("block_states");
        states.fieldNames().forEachRemaining(key -> {
            JsonNode node = states.path(key);
            Map<String, String> properties = new LinkedHashMap<>();
            node.path("Properties").fields().forEachRemaining(entry -> properties.put(entry.getKey(), entry.getValue().asText()));
            blockStates.put(Integer.parseInt(key), new BlockState(node.path("Name").asText("minecraft:air"), properties));
        });
        Map<Integer, String> blockEntityTypes = new LinkedHashMap<>();
        JsonNode entityTypes = root.path("block_entity_types");
        entityTypes.fieldNames().forEachRemaining(key -> blockEntityTypes.put(Integer.parseInt(key), entityTypes.path(key).asText()));
        if (!root.has("data_version")) throw new ParseException("registry mappings lack data_version");
        return new RegistryMappings(root.path("data_version").asInt(), blockStates, blockEntityTypes);
    }

    public int dataVersion() {
        return dataVersion;
    }

    public NbtValue.CompoundValue blockStateNbt(int globalId) {
        BlockState state = requireBlockState(globalId);
        Map<String, NbtValue> out = new LinkedHashMap<>();
        out.put("Name", NbtValue.stringValue(state.name()));
        if (!state.properties().isEmpty()) {
            Map<String, NbtValue> properties = new LinkedHashMap<>();
            state.properties().forEach((key, value) -> properties.put(key, NbtValue.stringValue(value)));
            out.put("Properties", NbtValue.compound(properties));
        }
        return NbtValue.compound(out);
    }

    public String blockStateName(int globalId) {
        return requireBlockState(globalId).name();
    }

    public String blockEntityType(int typeId) {
        String name = blockEntityTypes.get(typeId);
        if (name == null) throw new ParseException("unknown block entity registry id " + typeId);
        return name;
    }

    public void requireUsable() {
        if (blockStates.isEmpty()) {
            throw new ParseException("registry mappings contain no block_states");
        }
    }

    private BlockState requireBlockState(int id) {
        BlockState state = blockStates.get(id);
        if (state == null) throw new ParseException("unknown block state registry id " + id);
        return state;
    }

    private record BlockState(String name, Map<String, String> properties) {
    }
}
