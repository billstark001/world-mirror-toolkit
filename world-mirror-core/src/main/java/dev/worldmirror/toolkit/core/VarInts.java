package dev.worldmirror.toolkit.core;

/** Utility methods for Minecraft VarInt values. */
public final class VarInts {
    private VarInts() {}

    /** Returns the number of bytes used by the leading VarInt in {@code data}. */
    public static int leadingSize(byte[] data) {
        int size = 0;
        while (size < 5 && size < data.length) {
            int current = data[size++] & 0xFF;
            if ((current & 0x80) == 0) {
                return size;
            }
        }
        throw new ParseException("missing or too-long leading VarInt");
    }

    /** Reads the leading VarInt without advancing an external cursor. */
    public static int leadingValue(byte[] data) {
        return new ByteCursor(data).readVarInt();
    }
}
