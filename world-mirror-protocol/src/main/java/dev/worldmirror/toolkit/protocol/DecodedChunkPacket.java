package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;

/** Decoded high-value fields from a clientbound LevelChunkWithLight packet. */
public record DecodedChunkPacket(
        long eventIndex,
        long timestampMillis,
        DimensionKey dimension,
        ChunkPos chunkPos,
        byte[] rawChunkData,
        byte[] rawLightData,
        int blockEntityCount,
        String parser) {}
