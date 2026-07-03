package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ByteCursor;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.ParseException;
import dev.worldmirror.toolkit.replay.ReplayEvent;
import dev.worldmirror.toolkit.schema.PacketKind;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.util.Optional;

/** Converts ReplayMod packet events into semantic records using a versioned schema. */
public final class ReplayProtocolDecoder {
    private final PacketClassifier classifier;
    private final LevelChunkWithLightV1Parser chunkV1Parser = new LevelChunkWithLightV1Parser();

    public ReplayProtocolDecoder(ProtocolSchema schema) {
        this.classifier = new PacketClassifier(schema);
    }

    public Optional<DecodedChunkPacket> tryDecodeChunk(ReplayEvent event, DimensionKey dimension) {
        return classifier.classify(event.payload())
                .filter(c -> c.descriptor().kind() == PacketKind.LEVEL_CHUNK_WITH_LIGHT)
                .map(c -> {
                    if (!"level-chunk-with-light/v1".equals(c.descriptor().parser())) {
                        throw new ParseException("unsupported chunk parser: " + c.descriptor().parser());
                    }
                    return chunkV1Parser.parse(event, dimension);
                });
    }

    public Optional<DimensionKey> tryDecodeDimensionSwitch(ReplayEvent event) {
        return classifier.classify(event.payload())
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

    private DimensionKey readLoginDimension(ByteCursor cursor) {
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

    private DimensionKey readCommonSpawnDimension(ByteCursor cursor) {
        // 26.1.2 CommonPlayerSpawnInfo writes holder id first, then the dimension ResourceKey string.
        cursor.readVarInt();
        DimensionKey dimension = new DimensionKey(cursor.readUtf(Short.MAX_VALUE));
        cursor.readLong(); // seed
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
        return dimension;
    }
}
