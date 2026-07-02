package dev.worldmirror.toolkit.replay;

import dev.worldmirror.toolkit.core.IoUtil;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a stable JSONL event index compatible with the original Python tooling. */
public final class ReplayIndexWriter {
    private ReplayIndexWriter() {}

    public static long write(ReplaySource source, Path jsonl) throws IOException {
        IoUtil.createParentDirectories(jsonl);
        long[] count = new long[1];
        try (BufferedWriter out = Files.newBufferedWriter(jsonl)) {
            source.forEach(event -> {
                int packetId = event.hasReadablePacketId() ? event.packetId() : -1;
                int packetIdLen = event.hasReadablePacketId() ? event.packetIdLength() : -1;
                out.write("{\"index\":" + event.index()
                        + ",\"offset\":" + event.fileOffset()
                        + ",\"timestamp_ms\":" + event.timestampMillis()
                        + ",\"length\":" + event.payloadLength()
                        + ",\"packet_id\":" + packetId
                        + ",\"packet_id_len\":" + packetIdLen
                        + "}");
                out.newLine();
                count[0]++;
            });
        }
        return count[0];
    }
}
