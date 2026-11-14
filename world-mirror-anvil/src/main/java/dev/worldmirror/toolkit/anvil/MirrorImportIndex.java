package dev.worldmirror.toolkit.anvil;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkRecord;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Mode;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Plan;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requireIndexFiles;

/** Opens the mirror index and reads the committed claims for a region. */
final class MirrorImportIndex {
    private static final List<String> SNAPSHOT_SUFFIXES = List.of("", "-wal", "-journal");
    private static final List<String> SQLITE_SUFFIXES = List.of("", "-wal", "-journal", "-shm");

    private MirrorImportIndex() {}

    static Map<ChunkCoordinate, ChunkRecord> indexedRegion(
            ChunkIndexStore index, String dimension, int regionX, int regionZ) throws SQLException {
        Map<ChunkCoordinate, ChunkRecord> result = new HashMap<>();
        for (ChunkRecord chunk : index.queryRegion(dimension, regionX, regionZ)) {
            result.put(new ChunkCoordinate(chunk.x(), chunk.z()), chunk);
        }
        return result;
    }

    static Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + database, readOnly.toProperties());
    }

    /** Opening a read-only WAL database can still create or update sidecars. Read a private copy. */
    static final class Snapshot implements AutoCloseable {
        final Connection connection;
        private final Path database;

        private Snapshot(Path database, Connection connection) {
            this.database = database;
            this.connection = connection;
        }

        static Snapshot open(Path source, boolean saveLocked) throws IOException, SQLException {
            if (source == null)
                return new Snapshot(null, null);
            requireIndexFiles(source);
            var before = files(source);
            if (!before.containsKey("")) {
                if (!before.isEmpty())
                    throw new IOException("index journal exists without its database: " + source);
                return new Snapshot(null, null);
            }
            if (saveLocked)
                return new Snapshot(null, openReadOnly(source));
            Path target = Files.createTempDirectory("toolkit-index-").resolve(source.getFileName());
            try {
                for (String suffix : before.keySet()) {
                    Files.copy(sidecar(source, suffix), sidecar(target, suffix), LinkOption.NOFOLLOW_LINKS);
                }
                if (!before.equals(files(source)))
                    throw new IOException("index changed while copying: " + source);
                return new Snapshot(target, openReadOnly(target));
            } catch (IOException | SQLException | RuntimeException failure) {
                try {
                    remove(target);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static Map<String, FileVersion> files(Path source) throws IOException {
            var files = new HashMap<String, FileVersion>();
            for (String suffix : SNAPSHOT_SUFFIXES) {
                try {
                    var attributes = Files.readAttributes(
                            sidecar(source, suffix), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile())
                        throw new IOException("invalid index file: " + source + suffix);
                    files.put(suffix,
                            new FileVersion(
                                    attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()));
                } catch (NoSuchFileException absent) {
                    // A cleanly closed database need not have sidecars.
                }
            }
            return files;
        }

        private record FileVersion(long size, java.nio.file.attribute.FileTime modified, Object key) {}

        private static Path sidecar(Path database, String suffix) {
            return database.resolveSibling(database.getFileName() + suffix);
        }

        private static void remove(Path database) throws IOException {
            for (String suffix : SQLITE_SUFFIXES) Files.deleteIfExists(sidecar(database, suffix));
            Files.delete(database.getParent());
        }

        @Override
        public void close() throws IOException, SQLException {
            if (connection == null)
                return;
            if (database == null) {
                connection.close();
                return;
            }
            try {
                connection.close();
            } catch (SQLException failure) {
                try {
                    remove(database);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            remove(database);
        }
    }

    static Connection openWritable(Plan plan) throws IOException, SQLException {
        if (plan.options().mode() != Mode.MIRROR)
            return null;
        requireIndexFiles(plan.database());
        Files.createDirectories(plan.database().getParent());
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + plan.database());
        try {
            ChunkIndexStore.initialize(db);
            return db;
        } catch (SQLException | RuntimeException failure) {
            try {
                db.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }
}
