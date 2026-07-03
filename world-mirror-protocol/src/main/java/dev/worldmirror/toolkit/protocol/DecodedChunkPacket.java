package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;
import java.util.List;

/** Decoded high-value fields from a clientbound LevelChunkWithLight packet. */
public record DecodedChunkPacket(
        long eventIndex,
        long timestampMillis,
        DimensionKey dimension,
        ChunkPos chunkPos,
        byte[] rawChunkData,
        byte[] rawLightData,
        List<DecodedChunkPacket.BlockEntity> blockEntities,
        String parser) {
    public record BlockEntity(int packedXZ, int y, int typeId, byte[] rawNbt) {
        public int blockX(ChunkPos chunkPos) {
            return chunkPos.x() * 16 + ((packedXZ >>> 4) & 15);
        }

        public int blockZ(ChunkPos chunkPos) {
            return chunkPos.z() * 16 + (packedXZ & 15);
        }
    }
}
