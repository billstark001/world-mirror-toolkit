package io.github.billstark001.worldmirror.legacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** An unfinished local route adoption can leave save metadata ahead of the graph. */
public final class LegacyRoutingRecovery {
    private LegacyRoutingRecovery() {}

    public static void requireCompletedAdoptions(Path directory) throws IOException {
        if (!Files.notExists(
                    directory.resolve(".world-graph-local-adoption.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("world graph requires recovery in World Mirror: " + directory);
        }
    }
}
