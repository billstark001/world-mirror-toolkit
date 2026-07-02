package dev.worldmirror.toolkit.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounds-checked big-endian cursor for Minecraft protocol payloads.
 *
 * <p>The class is deliberately small and allocation-light. It does not model every protocol type;
 * version-specific decoders should compose these primitives and keep their assumptions in schema
 * files.</p>
 */
public final class ByteCursor {
    private static final int MAX_VARINT_BYTES = 5;

    private final byte[] data;
    private int offset;

    public ByteCursor(byte[] data) {
        this(data, 0);
    }

    public ByteCursor(byte[] data, int offset) {
        this.data = Objects.requireNonNull(data, "data");
        if (offset < 0 || offset > data.length) {
            throw new ParseException("invalid initial offset " + offset + " for " + data.length + " bytes");
        }
        this.offset = offset;
    }

    public int offset() {
        return offset;
    }

    public void offset(int newOffset) {
        if (newOffset < 0 || newOffset > data.length) {
            throw new ParseException("invalid offset " + newOffset + " for " + data.length + " bytes");
        }
        offset = newOffset;
    }

    public int remaining() {
        return data.length - offset;
    }

    public boolean hasRemaining() {
        return offset < data.length;
    }

    public byte readByte() {
        require(1, "byte");
        return data[offset++];
    }

    public short readShort() {
        require(2, "short");
        short value = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort();
        offset += 2;
        return value;
    }

    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    public int readInt() {
        require(4, "int");
        int value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        offset += 4;
        return value;
    }

    public long readLong() {
        require(8, "long");
        long value = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        offset += 8;
        return value;
    }

    public boolean readBoolean() {
        return readUnsignedByte() != 0;
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        while (position < MAX_VARINT_BYTES) {
            require(1, "varint");
            int current = data[offset++] & 0xFF;
            value |= (current & 0x7F) << (position * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
            position++;
        }
        throw new ParseException("VarInt is too long at offset " + (offset - MAX_VARINT_BYTES));
    }

    public String readUtf(int maxBytes) {
        int length = readVarInt();
        if (length < 0 || length > maxBytes) {
            throw new ParseException("invalid UTF-8 byte length " + length);
        }
        require(length, "utf8 string");
        String value = new String(data, offset, length, StandardCharsets.UTF_8);
        offset += length;
        return value;
    }

    public byte[] readBytes(int length) {
        if (length < 0) {
            throw new ParseException("negative byte length " + length);
        }
        require(length, "byte array");
        byte[] out = Arrays.copyOfRange(data, offset, offset + length);
        offset += length;
        return out;
    }

    public byte[] slice(int startInclusive, int endExclusive) {
        if (startInclusive < 0 || endExclusive < startInclusive || endExclusive > data.length) {
            throw new ParseException("invalid slice " + startInclusive + ".." + endExclusive + " for " + data.length + " bytes");
        }
        return Arrays.copyOfRange(data, startInclusive, endExclusive);
    }

    public void skip(int length) {
        if (length < 0) {
            throw new ParseException("negative skip " + length);
        }
        require(length, "skip");
        offset += length;
    }

    public byte[] data() {
        return data;
    }

    private void require(int length, String what) {
        if (offset + length > data.length) {
            throw new ParseException("truncated " + what + " at offset " + offset + ", need " + length + " bytes, remaining " + remaining());
        }
    }
}
