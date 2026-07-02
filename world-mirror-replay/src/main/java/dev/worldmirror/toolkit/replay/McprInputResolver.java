package dev.worldmirror.toolkit.replay;

import dev.worldmirror.toolkit.core.ToolkitException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Opens {@code recording.tmcpr} from a raw file, an unpacked ReplayMod directory, or an .mcpr zip. */
public final class McprInputResolver {
    private McprInputResolver() {}

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
