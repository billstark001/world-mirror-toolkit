package dev.worldmirror.toolkit.core;

import io.github.billstark001.worldmirror.format.MirrorFormat;
import java.nio.file.Path;
import java.util.Objects;

/** World Mirror save paths derived from the shared on-disk format contract. */
public record MirrorSavePaths(Path root, Path metadata, Path chunkIndex) {
    public static MirrorSavePaths at(Path worldRoot) {
        Path normalized = Objects.requireNonNull(worldRoot, "worldRoot")
                .toAbsolutePath().normalize();
        return new MirrorSavePaths(normalized,
                normalized.resolve(MirrorFormat.METADATA_FILE),
                normalized.resolve(MirrorFormat.DATABASE_FILE));
    }
}
