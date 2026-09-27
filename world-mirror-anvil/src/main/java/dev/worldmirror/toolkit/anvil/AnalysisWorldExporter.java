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
import java.util.Locale;
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
        try (var entries = Files.list(outDir)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("output directory must be empty to avoid mixing worlds or stale regions: " + outDir);
            }
        }
        Map<WorldChunkKey, ChunkSnapshot> latest = new HashMap<>();
        Map<WorldChunkKey, Integer> counts = new HashMap<>();
        Map<String, Integer> chunksByDimension = new TreeMap<>();
        long[] eventCount = new long[1];
        long[] chunkPacketCount = new long[1];
        DimensionKey[] currentDimension = new DimensionKey[] {initialDimension};
        String[] currentWorld = new String[] {"unidentified"};
        int[] currentMinSectionY = new int[] {
                switch (initialDimension.value()) {
                    case "minecraft:the_nether", "minecraft:the_end" -> 0;
                    default -> schema.minSectionY();
                }
        };
        long[] dimensionSwitches = new long[1];
        source.forEach(event -> {
            eventCount[0]++;
            decoder.tryDecodeWorldSwitch(event).ifPresent(spawn -> {
                currentDimension[0] = spawn.dimension();
                currentWorld[0] = String.format(Locale.ROOT, "world-%016x", spawn.hashedSeed());
                currentMinSectionY[0] = spawn.minSectionY();
                dimensionSwitches[0]++;
                System.out.println("world switch @ event " + event.index() + " t=" + event.timestampMillis()
                        + " -> " + currentWorld[0] + " " + spawn.dimension().value()
                        + " minSectionY=" + spawn.minSectionY());
            });
            decoder.tryDecodeChunk(event, currentDimension[0]).ifPresent(chunk -> {
                WorldChunkKey key = new WorldChunkKey(currentWorld[0], chunk.dimension(), chunk.chunkPos());
                latest.put(key, new ChunkSnapshot(chunk, decoder.biomes(), currentMinSectionY[0]));
                counts.merge(key, 1, Integer::sum);
                chunkPacketCount[0]++;
                if (chunkPacketCount[0] % 1000 == 0) {
                    System.out.println("scanned events=" + eventCount[0] + " chunkPackets=" + chunkPacketCount[0] + " uniqueChunks=" + latest.size());
                }
            });
            decoder.advance(event);
        });

        Map<String, Map<DimensionKey, Map<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>>>> byWorld = new TreeMap<>();
        int built = 0;
        int skippedEmpty = 0;
        for (Map.Entry<WorldChunkKey, ChunkSnapshot> entry : latest.entrySet()) {
            WorldChunkKey key = entry.getKey();
            ChunkPos pos = key.chunkPos();
            ChunkSnapshot snapshot = entry.getValue();
            var chunkRoot = chunkBuilder.build(snapshot.packet(), counts.getOrDefault(key, 1),
                    snapshot.biomes(), snapshot.minSectionY());
            if (chunkRoot.isEmpty()) {
                skippedEmpty++;
                continue;
            }
            byWorld.computeIfAbsent(key.worldId(), ignored -> new TreeMap<>((a, b) -> a.value().compareTo(b.value())))
                    .computeIfAbsent(key.dimension(), ignored -> new TreeMap<>())
                    .computeIfAbsent(pos.region(), ignored -> new TreeMap<>())
                    .put(pos, chunkRoot.get());
            built++;
            if (built % 500 == 0) {
                System.out.println("built chunks=" + built + "/" + latest.size());
            }
        }

        int regionsWritten = 0;
        Map<String, Map<String, Integer>> chunksByWorld = new TreeMap<>();
        for (var worldEntry : byWorld.entrySet()) {
            Map<String, Integer> worldDimensions = new TreeMap<>();
            for (var dimensionEntry : worldEntry.getValue().entrySet()) {
                Path dimensionDir = dimensionPath(outDir, worldEntry.getKey(), byWorld.size() > 1, dimensionEntry.getKey());
                Path regionDir = dimensionDir.resolve("region");
                Files.createDirectories(regionDir);
                int dimensionChunks = 0;
                for (var regionEntry : dimensionEntry.getValue().entrySet()) {
                    if (regionEntry.getValue().isEmpty()) continue;
                    dimensionChunks += regionEntry.getValue().size();
                    System.out.println("writing " + worldEntry.getKey() + " " + dimensionEntry.getKey().value()
                            + " " + regionEntry.getKey().fileName() + " chunks=" + regionEntry.getValue().size());
                    regionWriter.write(regionDir.resolve(regionEntry.getKey().fileName()), regionEntry.getValue());
                    regionsWritten++;
                }
                worldDimensions.put(dimensionEntry.getKey().value(), dimensionChunks);
                chunksByDimension.merge(dimensionEntry.getKey().value(), dimensionChunks, Integer::sum);
            }
            chunksByWorld.put(worldEntry.getKey(), worldDimensions);
        }
        int chunksWritten = chunksByDimension.values().stream().mapToInt(Integer::intValue).sum();
        return new ExportSummary(schema.minecraftVersion(), eventCount[0], chunkPacketCount[0], chunksWritten,
                skippedEmpty, regionsWritten, dimensionSwitches[0], chunksByDimension, chunksByWorld, outDir);
    }

    private Path dimensionPath(Path outDir, String worldId, boolean multipleWorlds, DimensionKey dimension) {
        Path worldDir = multipleWorlds ? outDir.resolve(worldId) : outDir;
        String subdir = dimension.saveSubdirectory();
        return ".".equals(subdir) ? worldDir : worldDir.resolve(subdir);
    }

    private record WorldChunkKey(String worldId, DimensionKey dimension, ChunkPos chunkPos) {}
    private record ChunkSnapshot(DecodedChunkPacket packet, Map<Integer, String> biomes, int minSectionY) {}

    public record ExportSummary(String minecraftVersion, long eventsRead, long chunkPacketsRead, int chunksWritten,
            int emptyChunksSkipped, int regionsWritten, long dimensionSwitches, Map<String, Integer> chunksByDimension,
            Map<String, Map<String, Integer>> chunksByWorld, Path outputDirectory) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("minecraft_version", minecraftVersion);
            out.put("events_read", eventsRead);
            out.put("chunk_packets_read", chunkPacketsRead);
            out.put("chunks_written", chunksWritten);
            out.put("empty_chunks_skipped", emptyChunksSkipped);
            out.put("regions_written", regionsWritten);
            out.put("dimension_switches", dimensionSwitches);
            out.put("chunks_by_dimension", chunksByDimension);
            out.put("chunks_by_world", chunksByWorld);
            out.put("output_directory", outputDirectory.toString());
            return out;
        }
    }
}
