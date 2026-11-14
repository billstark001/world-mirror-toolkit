package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.DimensionKey;
import dev.worldmirror.toolkit.core.WorldLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Resolves import paths and rejects non-regular save entries before opening them. */
final class ImportSavePaths {
    private ImportSavePaths() {}

    static Path regionDirectory(Path world, WorldLayout layout, DimensionKey dimension) throws IOException {
        Path path = layout.dimensionPath(world, dimension).resolve("region").normalize();
        if (!path.startsWith(world))
            throw new IOException("region directory leaves its save: " + path);
        for (Path part = path; !part.equals(world); part = part.getParent()) {
            requirePlainDirectory(part);
        }
        return path;
    }

    static void requireRecoveredSave(Path destination) throws IOException {
        if (!Files.notExists(destination.resolve(".worldmirror-recovery-required"), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(
                        destination.resolve(".worldmirror-relocation.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("destination requires recovery in World Mirror: " + destination);
        }
        try (var journals = Files.newDirectoryStream(destination, ".worldmirror-transaction-*")) {
            if (journals.iterator().hasNext()) {
                throw new IOException("destination requires recovery in World Mirror: " + destination);
            }
        }
    }

    static void requirePlainDirectory(Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // NTFS junctions can report both isDirectory and isOther.
            if (!attributes.isDirectory() || attributes.isOther() || attributes.isSymbolicLink()) {
                throw new IOException("not a plain directory: " + path);
            }
        } catch (NoSuchFileException missing) {
            // The import may create a missing directory.
        }
    }

    static void requirePlainFile(Path path) throws IOException {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file: " + path);
        }
    }

    static void requireIndexFiles(Path database) throws IOException {
        requirePlainDirectory(database.getParent());
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            requirePlainFile(database.resolveSibling(database.getFileName() + suffix));
        }
    }
}
