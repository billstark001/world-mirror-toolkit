package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.RegionPos;
import dev.worldmirror.toolkit.protocol.DecodedChunkPacket;
import dev.worldmirror.toolkit.protocol.ReplayProtocolDecoder;
import dev.worldmirror.toolkit.replay.ReplaySource;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** High-level exporter for analysis-mode world mirrors. */
public final class AnalysisWorldExporter {
    private final ProtocolSchema schema;
    private final ReplayProtocolDecoder decoder;
    private final AnalysisChunkBuilder chunkBuilder;
    private final RegionFileWriter regionWriter = new RegionFileWriter();

    public AnalysisWorldExporter(ProtocolSchema schema) {
        this.schema = schema;
        this.decoder = new ReplayProtocolDecoder(schema);
        this.chunkBuilder = new AnalysisChunkBuilder(schema);
    }

    public ExportSummary export(ReplaySource source, Path outDir, DimensionKey dimension) throws IOException {
        Files.createDirectories(outDir);
        Map<ChunkPos, DecodedChunkPacket> latest = new HashMap<>();
        Map<ChunkPos, Integer> counts = new HashMap<>();
        long[] eventCount = new long[1];
        long[] chunkPacketCount = new long[1];
        source.forEach(event -> {
            eventCount[0]++;
            decoder.tryDecodeChunk(event, dimension).ifPresent(chunk -> {
                latest.put(chunk.chunkPos(), chunk);
                counts.merge(chunk.chunkPos(), 1, Integer::sum);
                chunkPacketCount[0]++;
            });
        });

        Map<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>> byRegion = new TreeMap<>();
        for (Map.Entry<ChunkPos, DecodedChunkPacket> entry : latest.entrySet()) {
            ChunkPos pos = entry.getKey();
            byRegion.computeIfAbsent(pos.region(), ignored -> new TreeMap<>())
                    .put(pos, chunkBuilder.build(entry.getValue(), counts.getOrDefault(pos, 1)));
        }

        Path regionDir = outDir.resolve("region");
        Files.createDirectories(regionDir);
        for (Map.Entry<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>> entry : byRegion.entrySet()) {
            regionWriter.write(regionDir.resolve(entry.getKey().fileName()), entry.getValue());
        }
        return new ExportSummary(schema.minecraftVersion(), eventCount[0], chunkPacketCount[0], latest.size(), byRegion.size(), outDir);
    }

    public record ExportSummary(String minecraftVersion, long eventsRead, long chunkPacketsRead, int chunksWritten, int regionsWritten, Path outputDirectory) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minecraft_version", minecraftVersion);
            out.put("events_read", eventsRead);
            out.put("chunk_packets_read", chunkPacketsRead);
            out.put("chunks_written", chunksWritten);
            out.put("regions_written", regionsWritten);
            out.put("output_directory", outputDirectory.toString());
            return out;
        }
    }
}
