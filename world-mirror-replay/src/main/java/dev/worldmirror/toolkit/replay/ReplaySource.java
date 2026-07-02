package dev.worldmirror.toolkit.replay;

import java.io.IOException;

/** Source of ReplayMod packet events. */
public interface ReplaySource extends AutoCloseable {
    void forEach(ReplayEventHandler handler) throws IOException;

    @Override
    void close() throws IOException;
}
