package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ByteCursor;
import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.ParseException;
import dev.worldmirror.toolkit.replay.ReplayEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the legacy/26.1.2 chunk layout used by the original Python proof of concept.
 *
 * <p>This parser is selected by schema field {@code parser = level-chunk-with-light/v1}. Newer
 * Minecraft versions can keep the same high-level API while adding another parser id.</p>
 */
public final class LevelChunkWithLightV1Parser {
    private final NetworkNbtSkipper nbtSkipper = new NetworkNbtSkipper();

    public DecodedChunkPacket parse(ReplayEvent event, DimensionKey dimension) {
        ByteCursor cursor = new ByteCursor(event.payload(), event.packetIdLength());
        int chunkX = cursor.readInt();
        int chunkZ = cursor.readInt();
        skipHeightmaps(cursor);
        int chunkDataLength = cursor.readVarInt();
        int chunkDataStart = cursor.offset();
        cursor.skip(chunkDataLength);
        int chunkDataEnd = cursor.offset();
        int blockEntityCount = cursor.readVarInt();
        List<DecodedChunkPacket.BlockEntity> blockEntities = new ArrayList<>(blockEntityCount);
        for (int i = 0; i < blockEntityCount; i++) {
            int packedXZ = cursor.readUnsignedByte();
            int y = cursor.readShort();
            int typeId = cursor.readVarInt();
            if (cursor.remaining() <= 0) {
                throw new ParseException("missing block entity NBT in chunk " + chunkX + "," + chunkZ);
            }
            int nbtStart = cursor.offset();
            nbtSkipper.skipUnnamedRoot(cursor);
            blockEntities.add(new DecodedChunkPacket.BlockEntity(packedXZ, y, typeId, cursor.slice(nbtStart, cursor.offset())));
        }
        byte[] rawChunkData = cursor.slice(chunkDataStart, chunkDataEnd);
        byte[] rawLightData = cursor.slice(cursor.offset(), cursor.data().length);
        return new DecodedChunkPacket(
                event.index(),
                event.timestampMillis(),
                dimension,
                new ChunkPos(chunkX, chunkZ),
                rawChunkData,
                rawLightData,
                List.copyOf(blockEntities),
                "level-chunk-with-light/v1");
    }

    private void skipHeightmaps(ByteCursor cursor) {
        int heightmapCount = cursor.readVarInt();
        for (int i = 0; i < heightmapCount; i++) {
            cursor.readVarInt(); // heightmap key id or registry/string surrogate depending on version
            int longArrayLength = cursor.readVarInt();
            cursor.skip(longArrayLength * 8);
        }
    }
}
