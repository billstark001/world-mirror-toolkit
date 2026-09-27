package dev.worldmirror.toolkit.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.worldmirror.toolkit.core.ToolkitException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Opens {@code recording.tmcpr} from a raw file, an unpacked ReplayMod directory, or an .mcpr zip. */
public final class McprInputResolver {
    private McprInputResolver() {}

    public static ReplayMetadata readMetadata(Path input) throws IOException {
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isDirectory(input)) {
            return readMetadataJson(Files.newInputStream(input.resolve("metaData.json")));
        }
        if (fileName.endsWith(".mcpr") || fileName.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(input.toFile())) {
                ZipEntry entry = zip.getEntry("metaData.json");
                if (entry == null) throw new ToolkitException("replay has no metaData.json: " + input);
                return readMetadataJson(zip.getInputStream(entry));
            }
        }
        Path metadata = input.resolveSibling("metaData.json");
        return readMetadataJson(Files.newInputStream(metadata));
    }

    private static ReplayMetadata readMetadataJson(InputStream stream) throws IOException {
        try (stream) {
            JsonNode node = new ObjectMapper().readTree(stream);
            String version = node.path("mcversion").asText();
            int protocol = node.path("protocol").asInt(-1);
            int format = node.path("fileFormatVersion").asInt(-1);
            if (version.isBlank() || protocol < 0 || format < 0) {
                throw new ToolkitException("replay metadata lacks mcversion, protocol, or fileFormatVersion");
            }
            return new ReplayMetadata(version, protocol, format,
                    node.path("date").asLong(0), node.path("duration").asLong(0),
                    node.path("serverName").asText("unknown"), node.path("singleplayer").asBoolean(false));
        }
    }

    /** Expands recording folders but keeps an unpacked ReplayMod directory as one input. */
    public static List<Path> resolveInputs(List<Path> inputs) throws IOException {
        List<Path> resolved = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input) && !Files.isRegularFile(input.resolve("recording.tmcpr"))) {
                try (var children = Files.list(input)) {
                    resolved.addAll(children.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpr"))
                            .toList());
                }
            } else {
                resolved.add(input);
            }
        }
        if (resolved.isEmpty()) throw new ToolkitException("no ReplayMod files found in inputs");
        resolved.sort(Comparator.comparingLong((Path path) -> {
            try { return readMetadata(path).startTimeMillis(); }
            catch (IOException failure) { throw new ToolkitException("cannot read replay metadata: " + path, failure); }
        }).thenComparing(Path::toString));
        return List.copyOf(resolved);
    }

    public static ReplaySource open(Path input) throws IOException {
        if (Files.isDirectory(input)) {
            Path recording = input.resolve("recording.tmcpr");
            if (!Files.isRegularFile(recording)) {
                throw new ToolkitException("directory does not contain recording.tmcpr: " + input);
            }
            return new TmcprEventReader(Files.newInputStream(recording));
        }
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".mcpr") || fileName.endsWith(".zip")) {
            return openZip(input);
        }
        return new TmcprEventReader(Files.newInputStream(input));
    }

    private static ReplaySource openZip(Path zipPath) throws IOException {
        ZipFile zip = new ZipFile(zipPath.toFile());
        ZipEntry entry = zip.getEntry("recording.tmcpr");
        if (entry == null) {
            zip.close();
            throw new ToolkitException("zip does not contain recording.tmcpr: " + zipPath);
        }
        InputStream stream = zip.getInputStream(entry);
        TmcprEventReader reader = new TmcprEventReader(stream);
        return new ReplaySource() {
            @Override
            public void forEach(ReplayEventHandler handler) throws IOException {
                reader.forEach(handler);
            }

            @Override
            public void close() throws IOException {
                IOException first = null;
                try { reader.close(); } catch (IOException ex) { first = ex; }
                try { zip.close(); } catch (IOException ex) { if (first == null) first = ex; }
                if (first != null) throw first;
            }
        };
    }
}
