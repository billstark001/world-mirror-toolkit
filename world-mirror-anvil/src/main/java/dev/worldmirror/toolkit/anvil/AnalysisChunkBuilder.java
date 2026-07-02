package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.protocol.DecodedChunkPacket;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an audit-friendly Anvil chunk root from replay-derived data. */
public final class AnalysisChunkBuilder {
    private final ProtocolSchema schema;

    public AnalysisChunkBuilder(ProtocolSchema schema) {
        this.schema = schema;
    }

    public NbtValue.CompoundValue build(DecodedChunkPacket packet, int packetCountForChunk) {
        Map<String, NbtValue> replay = new LinkedHashMap<>();
        replay.put("Format", NbtValue.stringValue("world-mirror-toolkit.analysis.v1"));
        replay.put("Parser", NbtValue.stringValue(packet.parser()));
        replay.put("MinecraftVersion", NbtValue.stringValue(schema.minecraftVersion()));
        replay.put("ProtocolVersion", NbtValue.stringValue(schema.protocolVersion()));
        replay.put("LatestReplayEvent", NbtValue.longValue(packet.eventIndex()));
        replay.put("LatestTimestampMillis", NbtValue.longValue(packet.timestampMillis()));
        replay.put("ChunkPacketCount", NbtValue.intValue(packetCountForChunk));
        replay.put("BlockEntityCountInPacket", NbtValue.intValue(packet.blockEntityCount()));
        replay.put("RawLevelChunkData", NbtValue.byteArray(packet.rawChunkData()));
        replay.put("RawLightData", NbtValue.byteArray(packet.rawLightData()));

        Map<String, NbtValue> root = new LinkedHashMap<>();
        root.put("DataVersion", NbtValue.intValue(schema.dataVersion()));
        root.put("xPos", NbtValue.intValue(packet.chunkPos().x()));
        root.put("yPos", NbtValue.intValue(schema.minSectionY()));
        root.put("zPos", NbtValue.intValue(packet.chunkPos().z()));
        root.put("Status", NbtValue.stringValue("minecraft:full"));
        root.put("LastUpdate", NbtValue.longValue(0));
        root.put("InhabitedTime", NbtValue.longValue(0));
        root.put("isLightOn", NbtValue.byteValue(false));
        root.put("sections", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("block_entities", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("entities", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("Heightmaps", NbtValue.compound(new LinkedHashMap<>()));
        root.put("structures", NbtValue.compound(new LinkedHashMap<>()));
        root.put("ReplayRecovered", NbtValue.compound(replay));
        return NbtValue.compound(root);
    }
}
