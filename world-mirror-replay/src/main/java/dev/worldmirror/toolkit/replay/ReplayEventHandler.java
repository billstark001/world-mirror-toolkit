package dev.worldmirror.toolkit.replay;

import java.io.IOException;

/** Streaming callback used to avoid holding large replay files in memory. */
@FunctionalInterface
public interface ReplayEventHandler {
    void accept(ReplayEvent event) throws IOException;
}
