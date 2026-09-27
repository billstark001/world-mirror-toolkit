package dev.worldmirror.toolkit.anvil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.worldmirror.toolkit.core.WorldLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Provenance for an analysis export, alongside a minimal standard level.dat version marker. */
public record AnalysisExportMetadata(String format, int metadataSchema, String minecraftVersion,
        int dataVersion, String layout, String sourceKey, String worldId, String hashedSeed,
        long firstCaptureMillis, long lastCaptureMillis, long playDurationMillis,
        List<String> replayFiles, Map<String, Integer> chunksByDimension) {
    public static final String FILE_NAME = "worldmirror_toolkit_export.json";
    private static final String FORMAT = "world-mirror-toolkit.analysis";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public AnalysisExportMetadata {
        replayFiles = List.copyOf(replayFiles);
        chunksByDimension = Map.copyOf(chunksByDimension);
    }

    public static AnalysisExportMetadata create(String minecraftVersion, int dataVersion, WorldLayout layout,
            String sourceKey, String worldId, String hashedSeed, long firstCaptureMillis,
            long lastCaptureMillis, long playDurationMillis, List<String> replayFiles,
            Map<String, Integer> chunksByDimension) {
        return new AnalysisExportMetadata(FORMAT, 1, minecraftVersion, dataVersion,
                layout.name().toLowerCase(java.util.Locale.ROOT), sourceKey, worldId, hashedSeed,
                firstCaptureMillis, lastCaptureMillis, playDurationMillis, replayFiles, chunksByDimension);
    }

    public static AnalysisExportMetadata read(Path worldRoot) throws IOException {
        AnalysisExportMetadata result = JSON.readValue(worldRoot.resolve(FILE_NAME).toFile(), AnalysisExportMetadata.class);
        if (!FORMAT.equals(result.format()) || result.metadataSchema() != 1) {
            throw new IOException("unsupported analysis metadata in " + worldRoot.resolve(FILE_NAME));
        }
        return result;
    }

    public void write(Path worldRoot) throws IOException {
        Files.createDirectories(worldRoot);
        JSON.writeValue(worldRoot.resolve(FILE_NAME).toFile(), this);
        writeLevelData(worldRoot);
    }

    private void writeLevelData(Path worldRoot) throws IOException {
        Map<String, NbtValue> version = new LinkedHashMap<>();
        version.put("Id", NbtValue.intValue(dataVersion));
        version.put("Name", NbtValue.stringValue(minecraftVersion));
        version.put("Series", NbtValue.stringValue("main"));
        version.put("Snapshot", NbtValue.byteValue(false));

        Map<String, NbtValue> data = new LinkedHashMap<>();
        data.put("DataVersion", NbtValue.intValue(dataVersion));
        data.put("Version", NbtValue.compound(version));
        data.put("LevelName", NbtValue.stringValue("Replay analysis " + worldId));
        data.put("LastPlayed", NbtValue.longValue(lastCaptureMillis));
        data.put("WasModded", NbtValue.byteValue(true));
        data.put("initialized", NbtValue.byteValue(false));
        Map<String, NbtValue> root = Map.of("Data", NbtValue.compound(data));
        byte[] nbt = new PortableNbtWriter().writeUnnamedRoot(NbtValue.compound(root));
        try (GZIPOutputStream compressed = new GZIPOutputStream(
                Files.newOutputStream(worldRoot.resolve("level.dat")))) {
            compressed.write(nbt);
        }
    }
}
