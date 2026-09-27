package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.protocol.DecodedChunkPacket;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds an audit-friendly Anvil chunk root from replay-derived data. */
public final class AnalysisChunkBuilder {
    private final ProtocolSchema schema;
    private final RegistryMappings mappings;
    private final ChunkSectionDecoder sectionDecoder = new ChunkSectionDecoder();
    private final LightDataDecoder lightDecoder = new LightDataDecoder();
    private final PortableNbtParser nbtParser = new PortableNbtParser();

    public AnalysisChunkBuilder(ProtocolSchema schema, RegistryMappings mappings) {
        this.schema = schema;
        this.mappings = mappings;
    }

    public Optional<NbtValue.CompoundValue> build(DecodedChunkPacket packet, int packetCountForChunk) {
        return build(packet, packetCountForChunk, Map.of(), schema.minSectionY());
    }

    public Optional<NbtValue.CompoundValue> build(DecodedChunkPacket packet, int packetCountForChunk, Map<Integer, String> biomes) {
        return build(packet, packetCountForChunk, biomes, schema.minSectionY());
    }

    public Optional<NbtValue.CompoundValue> build(DecodedChunkPacket packet, int packetCountForChunk,
            Map<Integer, String> biomes, int minSectionY) {
        return build(packet, packetCountForChunk, biomes, minSectionY, packet.timestampMillis(), "unknown");
    }

    public Optional<NbtValue.CompoundValue> build(DecodedChunkPacket packet, int packetCountForChunk,
            Map<Integer, String> biomes, int minSectionY, long captureTimeMillis, String replayFile) {
        ChunkSectionDecoder.DecodeResult sectionResult = sectionDecoder.decode(packet.rawChunkData(), mappings, minSectionY, biomes);
        LightDataDecoder.DecodeResult lightResult = lightDecoder.decode(packet.rawLightData(), minSectionY - 1);
        List<NbtValue.CompoundValue> sections = new ArrayList<>();
        for (NbtValue.CompoundValue section : sectionResult.sections()) {
            Map<String, NbtValue> values = new LinkedHashMap<>(section.values());
            NbtValue yValue = values.get("Y");
            if (yValue instanceof NbtValue.ByteValue y) {
                byte[] block = lightResult.block().get((int) y.value());
                byte[] sky = lightResult.sky().get((int) y.value());
                if (block != null) {
                    values.put("BlockLight", NbtValue.byteArray(block));
                }
                if (sky != null) {
                    values.put("SkyLight", NbtValue.byteArray(sky));
                }
            }
            sections.add(NbtValue.compound(values));
        }
        List<NbtValue> blockEntities = buildBlockEntities(packet);
        if (sections.isEmpty() && blockEntities.isEmpty()) {
            return Optional.empty();
        }

        List<NbtValue> warnings = new ArrayList<>();
        sectionResult.warnings().forEach(warning -> warnings.add(NbtValue.stringValue(warning)));
        lightResult.warnings().forEach(warning -> warnings.add(NbtValue.stringValue(warning)));

        Map<String, NbtValue> replay = new LinkedHashMap<>();
        replay.put("Format", NbtValue.stringValue("world-mirror-toolkit.analysis.v2"));
        replay.put("Parser", NbtValue.stringValue(packet.parser()));
        replay.put("MinecraftVersion", NbtValue.stringValue(schema.minecraftVersion()));
        replay.put("ProtocolVersion", NbtValue.stringValue(schema.protocolVersion()));
        replay.put("Dimension", NbtValue.stringValue(packet.dimension().value()));
        replay.put("LatestReplayEvent", NbtValue.longValue(packet.eventIndex()));
        replay.put("LatestTimestampMillis", NbtValue.longValue(packet.timestampMillis()));
        replay.put("CaptureTimestampMillis", NbtValue.longValue(captureTimeMillis));
        replay.put("SourceReplay", NbtValue.stringValue(replayFile));
        replay.put("ChunkPacketCount", NbtValue.intValue(packetCountForChunk));
        replay.put("BlockEntityCountInPacket", NbtValue.intValue(packet.blockEntities().size()));
        replay.put("RawLevelChunkData", NbtValue.byteArray(packet.rawChunkData()));
        replay.put("RawLightData", NbtValue.byteArray(packet.rawLightData()));
        if (!warnings.isEmpty()) {
            replay.put("Warnings", NbtValue.list(NbtTagId.STRING, warnings));
        }

        Map<String, NbtValue> root = new LinkedHashMap<>();
        root.put("DataVersion", NbtValue.intValue(mappings.dataVersion()));
        root.put("xPos", NbtValue.intValue(packet.chunkPos().x()));
        root.put("yPos", NbtValue.intValue(minSectionY));
        root.put("zPos", NbtValue.intValue(packet.chunkPos().z()));
        root.put("Status", NbtValue.stringValue("minecraft:full"));
        root.put("LastUpdate", NbtValue.longValue(0));
        root.put("InhabitedTime", NbtValue.longValue(0));
        root.put("isLightOn", NbtValue.byteValue(!lightResult.block().isEmpty() || !lightResult.sky().isEmpty()));
        root.put("sections", NbtValue.list(NbtTagId.COMPOUND, new ArrayList<>(sections)));
        root.put("block_entities", NbtValue.list(NbtTagId.COMPOUND, blockEntities));
        root.put("entities", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("block_ticks", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("fluid_ticks", NbtValue.list(NbtTagId.COMPOUND, List.of()));
        root.put("PostProcessing", NbtValue.list(NbtTagId.LIST, List.of()));
        root.put("Heightmaps", NbtValue.compound(new LinkedHashMap<>()));
        root.put("structures", emptyStructures());
        root.put("ReplayRecovered", NbtValue.compound(replay));
        return Optional.of(NbtValue.compound(root));
    }

    private List<NbtValue> buildBlockEntities(DecodedChunkPacket packet) {
        List<NbtValue> out = new ArrayList<>();
        for (DecodedChunkPacket.BlockEntity blockEntity : packet.blockEntities()) {
            Map<String, NbtValue> values = new LinkedHashMap<>();
            try {
                NbtValue parsed = nbtParser.parseUnnamedRoot(blockEntity.rawNbt());
                if (parsed instanceof NbtValue.CompoundValue compound) {
                    values.putAll(compound.values());
                } else {
                    values.put("ReplayRawViewerNbt", NbtValue.byteArray(blockEntity.rawNbt()));
                }
            } catch (RuntimeException ex) {
                values.put("ReplayRawViewerNbt", NbtValue.byteArray(blockEntity.rawNbt()));
                values.put("ReplayNbtParseError", NbtValue.stringValue(ex.getMessage()));
            }
            values.put("x", NbtValue.intValue(blockEntity.blockX(packet.chunkPos())));
            values.put("y", NbtValue.intValue(blockEntity.y()));
            values.put("z", NbtValue.intValue(blockEntity.blockZ(packet.chunkPos())));
            values.putIfAbsent("id", NbtValue.stringValue(mappings.blockEntityType(blockEntity.typeId())));
            out.add(NbtValue.compound(values));
        }
        return out;
    }

    private NbtValue.CompoundValue emptyStructures() {
        Map<String, NbtValue> structures = new LinkedHashMap<>();
        structures.put("starts", NbtValue.compound(new LinkedHashMap<>()));
        structures.put("References", NbtValue.compound(new LinkedHashMap<>()));
        return NbtValue.compound(structures);
    }
}
