package dev.worldmirror.toolkit.replay;

import dev.worldmirror.toolkit.core.ParseException;
import dev.worldmirror.toolkit.core.VarInts;

/** One timestamped Minecraft clientbound packet as stored in {@code recording.tmcpr}. */
public record ReplayEvent(long index, long fileOffset, long timestampMillis, byte[] payload) {
    public int packetId() {
        return VarInts.leadingValue(payload);
    }

    public int packetIdLength() {
        return VarInts.leadingSize(payload);
    }

    public int payloadLength() {
        return payload.length;
    }

    public boolean hasReadablePacketId() {
        try {
            packetId();
            return true;
        } catch (ParseException ignored) {
            return false;
        }
    }
}
