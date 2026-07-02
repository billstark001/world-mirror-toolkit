package dev.worldmirror.toolkit.protocol;

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
}
