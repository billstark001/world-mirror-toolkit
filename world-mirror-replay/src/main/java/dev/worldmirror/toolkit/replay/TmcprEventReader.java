package dev.worldmirror.toolkit.replay;

import dev.worldmirror.toolkit.core.ParseException;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reader for the raw ReplayMod {@code recording.tmcpr} stream.
 *
 * <p>The observed layout is repeated {@code uint32_be timestamp_ms}, {@code uint32_be
 * packet_length}, then the raw Minecraft packet payload. The reader is intentionally ignorant of
 * packet schemas; decoding belongs in {@code world-mirror-protocol}.</p>
 */
public final class TmcprEventReader implements ReplaySource {
    private static final int DEFAULT_MAX_PACKET_BYTES = 64 * 1024 * 1024;

    private final DataInputStream in;
    private final int maxPacketBytes;

    public TmcprEventReader(InputStream input) {
        this(input, DEFAULT_MAX_PACKET_BYTES);
    }

    public TmcprEventReader(InputStream input, int maxPacketBytes) {
        this.in = new DataInputStream(new BufferedInputStream(input));
        this.maxPacketBytes = maxPacketBytes;
    }

    @Override
    public void forEach(ReplayEventHandler handler) throws IOException {
        long offset = 0;
        long index = 0;
        while (true) {
            long timestamp;
            int length;
            try {
                timestamp = Integer.toUnsignedLong(in.readInt());
                length = in.readInt();
            } catch (EOFException eof) {
                return;
            }
            offset += 8;
            if (length < 0 || length > maxPacketBytes) {
                throw new ParseException("invalid replay packet length " + length + " at payload offset " + offset);
            }
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                throw new ParseException("truncated replay packet at payload offset " + offset + ": expected " + length + ", got " + payload.length);
            }
            handler.accept(new ReplayEvent(index, offset - 8, timestamp, payload));
            offset += length;
            index++;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
