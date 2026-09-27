package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ByteCursor;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.ParseException;
import dev.worldmirror.toolkit.replay.ReplayEvent;
import dev.worldmirror.toolkit.schema.PacketKind;
import dev.worldmirror.toolkit.schema.PacketState;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Converts ReplayMod packet events into semantic records using a versioned schema. */
public final class ReplayProtocolDecoder {
    private final PacketClassifier classifier;
    private final ProtocolSchema schema;
    private final LevelChunkWithLightV1Parser chunkV1Parser = new LevelChunkWithLightV1Parser();
    private final NetworkNbtSkipper nbtSkipper = new NetworkNbtSkipper();
    private PacketState state = PacketState.CONFIGURATION;
    private Map<Integer, String> biomes = Map.of();
    private Map<Integer, Integer> dimensionMinSections = Map.of();
    private boolean dimensionRegistrySeen;

    public ReplayProtocolDecoder(ProtocolSchema schema) {
        this.schema = schema;
        this.classifier = new PacketClassifier(schema);
    }

    public Optional<DecodedChunkPacket> tryDecodeChunk(ReplayEvent event, DimensionKey dimension) {
        return classifier.classify(event.payload(), state)
                .filter(c -> c.descriptor().kind() == PacketKind.LEVEL_CHUNK_WITH_LIGHT)
                .map(c -> {
                    if (!"level-chunk-with-light/v1".equals(c.descriptor().parser())) {
                        throw new ParseException("unsupported chunk parser: " + c.descriptor().parser());
                    }
                    return chunkV1Parser.parse(event, dimension);
                });
    }

    public Optional<DimensionKey> tryDecodeDimensionSwitch(ReplayEvent event) {
        return tryDecodeWorldSwitch(event).map(SpawnInfo::dimension);
    }

    public Optional<SpawnInfo> tryDecodeWorldSwitch(ReplayEvent event) {
        return classifier.classify(event.payload(), state)
                .filter(c -> c.descriptor().kind() == PacketKind.LOGIN || c.descriptor().kind() == PacketKind.RESPAWN)
                .map(c -> {
                    ByteCursor cursor = new ByteCursor(event.payload(), c.packetIdLength());
                    return switch (c.descriptor().kind()) {
                        case LOGIN -> readLoginDimension(cursor);
                        case RESPAWN -> readCommonSpawnDimension(cursor);
                        default -> throw new ParseException("unexpected dimension packet kind " + c.descriptor().kind());
                    };
                });
    }

    public void advance(ReplayEvent event) {
        Optional<PacketClassification> classification = classifier.classify(event.payload(), state);
        if (classification.isEmpty()) return;
        PacketClassification packet = classification.get();
        if (packet.descriptor().kind() == PacketKind.REGISTRY_DATA) {
            readRegistryData(new ByteCursor(event.payload(), packet.packetIdLength()));
        } else if (packet.descriptor().kind() == PacketKind.FINISH_CONFIGURATION) {
            state = PacketState.PLAY;
        } else if (packet.descriptor().kind() == PacketKind.START_CONFIGURATION) {
            state = PacketState.CONFIGURATION;
            biomes = Map.of();
            dimensionMinSections = Map.of();
            dimensionRegistrySeen = false;
        }
    }

    public Map<Integer, String> biomes() {
        return biomes;
    }

    private void readRegistryData(ByteCursor cursor) {
        String registry = cursor.readUtf(32767);
        if (!"minecraft:worldgen/biome".equals(registry) && !"minecraft:dimension_type".equals(registry)) return;
        int count = cursor.readVarInt();
        if (count < 0 || count > 4096) throw new ParseException("invalid biome registry size " + count);
        Map<Integer, String> updatedBiomes = new HashMap<>();
        Map<Integer, Integer> updatedMinSections = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String name = cursor.readUtf(32767);
            boolean hasData = cursor.readBoolean();
            if ("minecraft:worldgen/biome".equals(registry)) {
                updatedBiomes.put(i, name);
                if (hasData) nbtSkipper.skipUnnamedRoot(cursor);
            } else {
                Integer minY = hasData ? nbtSkipper.readRootInt(cursor, "min_y").orElseThrow(
                        () -> new ParseException("dimension type " + name + " has no min_y")) : knownMinY(name);
                if (minY != null) {
                    if (minY % 16 != 0) throw new ParseException("dimension type " + name + " min_y is not section-aligned: " + minY);
                    updatedMinSections.put(i, minY / 16);
                }
            }
        }
        if ("minecraft:worldgen/biome".equals(registry)) biomes = Map.copyOf(updatedBiomes);
        else {
            dimensionMinSections = Map.copyOf(updatedMinSections);
            dimensionRegistrySeen = true;
        }
    }

    private Integer knownMinY(String name) {
        return switch (name) {
            case "minecraft:overworld", "minecraft:overworld_caves" -> schema.minSectionY() * 16;
            case "minecraft:the_nether", "minecraft:the_end" -> 0;
            default -> null;
        };
    }

    private SpawnInfo readLoginDimension(ByteCursor cursor) {
        cursor.readInt(); // player id
        cursor.readBoolean(); // hardcore
        int dimensionCount = cursor.readVarInt();
        for (int i = 0; i < dimensionCount; i++) {
            cursor.readUtf(Short.MAX_VALUE);
        }
        cursor.readVarInt(); // max players
        cursor.readVarInt(); // chunk radius
        cursor.readVarInt(); // simulation distance
        cursor.readBoolean(); // reduced debug info
        cursor.readBoolean(); // show death screen
        cursor.readBoolean(); // do limited crafting
        return readCommonSpawnDimension(cursor);
    }

    private SpawnInfo readCommonSpawnDimension(ByteCursor cursor) {
        // 26.1.2 CommonPlayerSpawnInfo writes holder id first, then the dimension ResourceKey string.
        int dimensionTypeId = cursor.readVarInt();
        DimensionKey dimension = new DimensionKey(cursor.readUtf(Short.MAX_VALUE));
        long seed = cursor.readLong(); // hashed world seed
        cursor.readByte(); // game type
        cursor.readByte(); // previous game type
        cursor.readBoolean(); // debug
        cursor.readBoolean(); // flat
        if (cursor.readBoolean()) {
            cursor.readUtf(Short.MAX_VALUE);
            cursor.readLong(); // packed BlockPos
        }
        cursor.readVarInt(); // portal cooldown
        cursor.readVarInt(); // sea level
        Integer minSectionY = dimensionMinSections.get(dimensionTypeId);
        if (minSectionY == null) {
            if (dimensionRegistrySeen) {
                throw new ParseException("no min_y for dimension type " + dimensionTypeId + " in " + dimension.value());
            }
            Integer minY = knownMinY(dimension.value());
            if (minY == null) throw new ParseException("unknown min_y for dimension type " + dimensionTypeId + " in " + dimension.value());
            minSectionY = minY / 16;
        }
        return new SpawnInfo(dimension, dimensionTypeId, seed, minSectionY);
    }

    public record SpawnInfo(DimensionKey dimension, int dimensionTypeId, long hashedSeed, int minSectionY) {}
}
