package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.RegionPos;
import dev.worldmirror.toolkit.core.WorldLayout;
import dev.worldmirror.toolkit.protocol.DecodedChunkPacket;
import dev.worldmirror.toolkit.protocol.ReplayProtocolDecoder;
import dev.worldmirror.toolkit.replay.McprInputResolver;
import dev.worldmirror.toolkit.replay.ReplayMetadata;
import dev.worldmirror.toolkit.replay.ReplaySource;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Merges full replay chunks by capture time, keeping unrelated worlds separate. */
public final class AnalysisWorldExporter {
    private final ProtocolSchema schema;
    private final AnalysisChunkBuilder chunkBuilder;
    private final RegionFileWriter regionWriter = new RegionFileWriter();

    public AnalysisWorldExporter(ProtocolSchema schema, RegistryMappings mappings) {
        this.schema = schema;
        mappings.requireUsable();
        this.chunkBuilder = new AnalysisChunkBuilder(schema, mappings);
    }

    public ExportSummary export(List<Path> inputs, Path outDir, DimensionKey initialDimension,
            WorldLayout layout, String worldSelection) throws IOException {
        Files.createDirectories(outDir);
        try (var entries = Files.list(outDir)) {
            if (entries.findAny().isPresent()) throw new IOException("output directory must be empty: " + outDir);
        }
        Scan scan = new Scan();
        for (Path input : inputs) {
            ReplayMetadata metadata = McprInputResolver.readMetadata(input);
            if (!schema.minecraftVersion().equals(metadata.minecraftVersion())
                    || schema.networkProtocol() != metadata.protocol()) {
                throw new IOException("mixed or unsupported replay version in " + input + ": "
                        + metadata.minecraftVersion() + "/" + metadata.protocol());
            }
            if (inputs.size() > 1 && metadata.startTimeMillis() <= 0) {
                throw new IOException("multi-file replay needs metaData.json date: " + input);
            }
            System.out.println("reading " + input + " start=" + metadata.startTimeMillis()
                    + " server=" + metadata.sourceKey());
            try (ReplaySource source = McprInputResolver.open(input)) {
                scanFile(source, metadata, input.getFileName().toString(), initialDimension, scan);
            }
        }
        return write(scan, outDir, layout, worldSelection);
    }

    private void scanFile(ReplaySource source, ReplayMetadata metadata, String replayFile,
            DimensionKey initialDimension, Scan scan) throws IOException {
        ReplayProtocolDecoder decoder = new ReplayProtocolDecoder(schema);
        DimensionKey[] dimension = {initialDimension};
        String[] worldId = {"unidentified-" + fingerprint(metadata.sourceKey())};
        int[] minSectionY = {switch (initialDimension.value()) {
            case "minecraft:the_nether", "minecraft:the_end" -> 0;
            default -> schema.minSectionY();
        }};
        long[] segmentStart = {0};
        long[] lastEventTime = {0};
        source.forEach(event -> {
            scan.eventsRead++;
            long relativeTime = event.timestampMillis();
            lastEventTime[0] = Math.max(lastEventTime[0], relativeTime);
            long captureTime = metadata.startTimeMillis() > 0
                    ? Math.addExact(metadata.startTimeMillis(), relativeTime) : relativeTime;
            decoder.tryDecodeWorldSwitch(event).ifPresent(spawn -> {
                if (scan.origins.containsKey(worldId[0])) {
                    scan.durations.merge(worldId[0], Math.max(0, relativeTime - segmentStart[0]), Long::sum);
                }
                dimension[0] = spawn.dimension();
                worldId[0] = "world-" + fingerprint(metadata.sourceKey())
                        + String.format(Locale.ROOT, "-%016x", spawn.hashedSeed());
                minSectionY[0] = spawn.minSectionY();
                segmentStart[0] = relativeTime;
                scan.origins.put(worldId[0], new Origin(metadata.sourceKey(), spawn.hashedSeed()));
                scan.replayFiles.computeIfAbsent(worldId[0], ignored -> new HashSet<>()).add(replayFile);
                scan.worldSwitches++;
                System.out.println("world switch @ " + replayFile + " event " + event.index()
                        + " -> " + worldId[0] + " " + dimension[0].value()
                        + " minSectionY=" + minSectionY[0]);
            });
            decoder.tryDecodeChunk(event, dimension[0]).ifPresent(chunk -> {
                WorldChunkKey key = new WorldChunkKey(worldId[0], chunk.dimension(), chunk.chunkPos());
                ChunkSnapshot incoming = new ChunkSnapshot(chunk, decoder.biomes(), minSectionY[0],
                        captureTime, replayFile);
                scan.latest.merge(key, incoming, (old, newer) ->
                        newer.captureTimeMillis() >= old.captureTimeMillis() ? newer : old);
                scan.counts.merge(key, 1, Integer::sum);
                scan.chunkPacketsRead++;
                if (scan.chunkPacketsRead % 10000 == 0) {
                    System.out.println("scanned events=" + scan.eventsRead + " chunks=" + scan.chunkPacketsRead
                            + " unique=" + scan.latest.size());
                }
            });
            decoder.advance(event);
        });
        if (scan.origins.containsKey(worldId[0])) {
            scan.durations.merge(worldId[0],
                    Math.max(0, Math.max(metadata.durationMillis(), lastEventTime[0]) - segmentStart[0]), Long::sum);
        }
    }

    private ExportSummary write(Scan scan, Path outDir, WorldLayout layout, String selection) throws IOException {
        Set<String> available = new HashSet<>();
        for (WorldChunkKey key : scan.latest.keySet()) available.add(key.worldId());
        if (available.isEmpty()) throw new IOException("no full chunk packets found in replay input");
        String selected = switch (selection) {
            case "all" -> null;
            case "longest" -> available.stream().max(Comparator
                    .comparingLong((String id) -> scan.durations.getOrDefault(id, 0L))
                    .thenComparing(id -> id)).orElseThrow();
            default -> {
                if (!available.contains(selection)) throw new IOException("world not found: " + selection
                        + "; available=" + available);
                yield selection;
            }
        };

        Map<String, Map<DimensionKey, Map<RegionPos, Map<ChunkPos, NbtValue.CompoundValue>>>> byWorld = new TreeMap<>();
        Map<String, long[]> captureRanges = new HashMap<>();
        int skippedEmpty = 0;
        for (Map.Entry<WorldChunkKey, ChunkSnapshot> entry : scan.latest.entrySet()) {
            WorldChunkKey key = entry.getKey();
            if (selected != null && !selected.equals(key.worldId())) continue;
            ChunkSnapshot snapshot = entry.getValue();
            var chunkRoot = chunkBuilder.build(snapshot.packet(), scan.counts.getOrDefault(key, 1),
                    snapshot.biomes(), snapshot.minSectionY(), snapshot.captureTimeMillis(), snapshot.replayFile());
            if (chunkRoot.isEmpty()) {
                skippedEmpty++;
                continue;
            }
            byWorld.computeIfAbsent(key.worldId(), ignored -> new TreeMap<>(
                            Comparator.comparing(DimensionKey::value)))
                    .computeIfAbsent(key.dimension(), ignored -> new TreeMap<>())
                    .computeIfAbsent(key.chunkPos().region(), ignored -> new TreeMap<>())
                    .put(key.chunkPos(), chunkRoot.get());
            long[] range = captureRanges.computeIfAbsent(key.worldId(), ignored ->
                    new long[] {Long.MAX_VALUE, Long.MIN_VALUE});
            range[0] = Math.min(range[0], snapshot.captureTimeMillis());
            range[1] = Math.max(range[1], snapshot.captureTimeMillis());
        }

        int regionsWritten = 0;
        Map<String, Map<String, Integer>> chunksByWorld = new TreeMap<>();
        Map<String, Integer> chunksByDimension = new TreeMap<>();
        for (var worldEntry : byWorld.entrySet()) {
            String id = worldEntry.getKey();
            Path worldRoot = selected == null && byWorld.size() > 1 ? outDir.resolve(id) : outDir;
            Map<String, Integer> worldDimensions = new TreeMap<>();
            for (var dimensionEntry : worldEntry.getValue().entrySet()) {
                Path regionDir = layout.dimensionPath(worldRoot, dimensionEntry.getKey()).resolve("region");
                Files.createDirectories(regionDir);
                int dimensionChunks = 0;
                for (var regionEntry : dimensionEntry.getValue().entrySet()) {
                    dimensionChunks += regionEntry.getValue().size();
                    regionWriter.write(regionDir.resolve(regionEntry.getKey().fileName()), regionEntry.getValue());
                    regionsWritten++;
                    System.out.println("writing " + id + " " + dimensionEntry.getKey().value() + " "
                            + regionEntry.getKey().fileName() + " chunks=" + regionEntry.getValue().size());
                }
                worldDimensions.put(dimensionEntry.getKey().value(), dimensionChunks);
                chunksByDimension.merge(dimensionEntry.getKey().value(), dimensionChunks, Integer::sum);
            }
            chunksByWorld.put(id, worldDimensions);
            Origin origin = scan.origins.getOrDefault(id, new Origin("unknown", 0));
            long[] range = captureRanges.get(id);
            AnalysisExportMetadata.create(schema.minecraftVersion(), schema.dataVersion(), layout,
                    origin.sourceKey(), id, Long.toUnsignedString(origin.hashedSeed(), 16), range[0], range[1],
                    scan.durations.getOrDefault(id, 0L),
                    scan.replayFiles.getOrDefault(id, Set.of()).stream().sorted().toList(),
                    worldDimensions).write(worldRoot);
        }
        int chunksWritten = chunksByDimension.values().stream().mapToInt(Integer::intValue).sum();
        return new ExportSummary(schema.minecraftVersion(), scan.eventsRead, scan.chunkPacketsRead,
                chunksWritten, skippedEmpty, regionsWritten, scan.worldSwitches,
                chunksByDimension, chunksByWorld, new TreeMap<>(scan.durations), selected, outDir);
    }

    private String fingerprint(String sourceKey) {
        UUID id = UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8));
        return id.toString().substring(0, 8);
    }

    private static final class Scan {
        private final Map<WorldChunkKey, ChunkSnapshot> latest = new HashMap<>();
        private final Map<WorldChunkKey, Integer> counts = new HashMap<>();
        private final Map<String, Origin> origins = new HashMap<>();
        private final Map<String, Set<String>> replayFiles = new HashMap<>();
        private final Map<String, Long> durations = new HashMap<>();
        private long eventsRead;
        private long chunkPacketsRead;
        private long worldSwitches;
    }

    private record Origin(String sourceKey, long hashedSeed) {}
    private record WorldChunkKey(String worldId, DimensionKey dimension, ChunkPos chunkPos) {}
    private record ChunkSnapshot(DecodedChunkPacket packet, Map<Integer, String> biomes,
            int minSectionY, long captureTimeMillis, String replayFile) {}

    public record ExportSummary(String minecraftVersion, long eventsRead, long chunkPacketsRead, int chunksWritten,
            int emptyChunksSkipped, int regionsWritten, long dimensionSwitches, Map<String, Integer> chunksByDimension,
            Map<String, Map<String, Integer>> chunksByWorld, Map<String, Long> worldDurationsMillis,
            String selectedWorld, Path outputDirectory) {
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
            out.put("world_durations_millis", worldDurationsMillis);
            out.put("selected_world", selectedWorld);
            out.put("output_directory", outputDirectory.toString());
            return out;
        }
    }
}
