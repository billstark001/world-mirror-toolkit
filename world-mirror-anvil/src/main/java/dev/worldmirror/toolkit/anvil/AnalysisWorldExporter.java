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

    public AnalysisWorldExporter(ProtocolSchema schema, RegistryMappings mappings) {
        this.schema = schema;
        this.decoder = new ReplayProtocolDecoder(schema);
        mappings.requireUsable();
        this.chunkBuilder = new AnalysisChunkBuilder(schema, mappings);
    }

    public ExportSummary export(ReplaySource source, Path outDir, DimensionKey initialDimension) throws IOException {
        Files.createDirectories(outDir);
        Map<DimensionChunkKey, DecodedChunkPacket> latest = new HashMap<>();
        Map<DimensionChunkKey, Integer> counts = new HashMap<>();
        Map<String, Integer> chunksByDimension = new TreeMap<>();
        long[] eventCount = new long[1];
        long[] chunkPacketCount = new long[1];
        DimensionKey[] currentDimension = new DimensionKey[] {initialDimension};
        long[] dimensionSwitches = new long[1];
        source.forEach(event -> {
            eventCount[0]++;
            decoder.tryDecodeDimensionSwitch(event).ifPresent(dimension -> {
                currentDimension[0] = dimension;
                dimensionSwitches[0]++;
                System.out.println("dimension switch @ event " + event.index() + " t=" + event.timestampMillis() + " -> " + dimension.value());
            });
            decoder.tryDecodeChunk(event, currentDimension[0]).ifPresent(chunk -> {
                DimensionChunkKey key = new DimensionChunkKey(chunk.dimension(), chunk.chunkPos());
                latest.put(key, chunk);
                counts.merge(key, 1, Integer::sum);
                chunkPacketCount[0]++;
                if (chunkPacketCount[0] % 1000 == 0) {
                    System.out.println("scanned events=" + eventCount[0] + " chunkPackets=" + chunkPacketCount[0] + " uniqueChunks=" + latest.size());
                }
            });
        });

        Map<DimensionKey, Map<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>>> byDimension = new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        int built = 0;
        for (Map.Entry<DimensionChunkKey, DecodedChunkPacket> entry : latest.entrySet()) {
            DimensionChunkKey key = entry.getKey();
            ChunkPos pos = key.chunkPos();
            byDimension.computeIfAbsent(key.dimension(), ignored -> new TreeMap<>())
                    .computeIfAbsent(pos.region(), ignored -> new TreeMap<>())
                    .put(pos, chunkBuilder.build(entry.getValue(), counts.getOrDefault(key, 1)));
            built++;
            if (built % 500 == 0) {
                System.out.println("built chunks=" + built + "/" + latest.size());
            }
        }

        int regionsWritten = 0;
        for (Map.Entry<DimensionKey, Map<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>>> dimensionEntry : byDimension.entrySet()) {
            Path dimensionDir = dimensionPath(outDir, dimensionEntry.getKey());
            Path regionDir = dimensionDir.resolve("region");
            Files.createDirectories(regionDir);
            int dimensionChunks = 0;
            for (Map.Entry<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>> regionEntry : dimensionEntry.getValue().entrySet()) {
                dimensionChunks += regionEntry.getValue().size();
                System.out.println("writing " + dimensionEntry.getKey().value() + " " + regionEntry.getKey().fileName() + " chunks=" + regionEntry.getValue().size());
                regionWriter.write(regionDir.resolve(regionEntry.getKey().fileName()), regionEntry.getValue());
                regionsWritten++;
            }
            chunksByDimension.put(dimensionEntry.getKey().value(), dimensionChunks);
        }
        return new ExportSummary(schema.minecraftVersion(), eventCount[0], chunkPacketCount[0], latest.size(), regionsWritten, dimensionSwitches[0], chunksByDimension, outDir);
    }

    private Path dimensionPath(Path outDir, DimensionKey dimension) {
        String subdir = dimension.saveSubdirectory();
        return ".".equals(subdir) ? outDir : outDir.resolve(subdir);
    }

    private record DimensionChunkKey(DimensionKey dimension, ChunkPos chunkPos) {}

    public record ExportSummary(String minecraftVersion, long eventsRead, long chunkPacketsRead, int chunksWritten, int regionsWritten, long dimensionSwitches, Map<String, Integer> chunksByDimension, Path outputDirectory) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minecraft_version", minecraftVersion);
            out.put("events_read", eventsRead);
            out.put("chunk_packets_read", chunkPacketsRead);
            out.put("chunks_written", chunksWritten);
            out.put("regions_written", regionsWritten);
            out.put("dimension_switches", dimensionSwitches);
            out.put("chunks_by_dimension", chunksByDimension);
            out.put("output_directory", outputDirectory.toString());
            return out;
        }
    }
}
