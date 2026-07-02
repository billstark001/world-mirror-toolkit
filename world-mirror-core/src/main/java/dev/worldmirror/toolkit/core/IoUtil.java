package dev.worldmirror.toolkit.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small file helpers used by CLI modules. */
public final class IoUtil {
    private IoUtil() {}

    public static void createParentDirectories(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static byte[] readAllLimited(InputStream in, int maxBytes) throws IOException {
        byte[] data = in.readAllBytes();
        if (data.length > maxBytes) {
            throw new IOException("input is too large: " + data.length + " > " + maxBytes);
        }
        return data;
    }
}
