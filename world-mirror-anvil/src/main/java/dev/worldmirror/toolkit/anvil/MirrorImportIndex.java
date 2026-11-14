package dev.worldmirror.toolkit.anvil;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkRecord;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Mode;
import dev.worldmirror.toolkit.anvil.ChunkImporter.Plan;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requireIndexFiles;

/** Opens the mirror index and reads the committed claims for a region. */
final class MirrorImportIndex {
    private MirrorImportIndex() {}

    static Map<ChunkCoordinate, ChunkRecord> indexedRegion(
            ChunkIndexStore index, String dimension, int regionX, int regionZ) throws SQLException {
        Map<ChunkCoordinate, ChunkRecord> result = new HashMap<>();
        for (ChunkRecord chunk : index.queryRegion(dimension, regionX, regionZ)) {
            result.put(new ChunkCoordinate(chunk.x(), chunk.z()), chunk);
        }
        return result;
    }

    static Connection openReadOnlyIndex(Path database) throws SQLException {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + database, readOnly.toProperties());
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
