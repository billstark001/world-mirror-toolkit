package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ByteCursor;
import dev.worldmirror.toolkit.core.ParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal big-endian NBT parser for unnamed network roots embedded in chunk packets. */
final class PortableNbtParser {
    private static final int MAX_DEPTH = 512;

    NbtValue.CompoundValue parseDiskRoot(InputStream input, int maxBytes) throws IOException {
        byte[] raw = input.readNBytes(maxBytes + 1);
        if (raw.length > maxBytes)
            throw new IOException("NBT exceeds " + maxBytes + " decoded bytes");
        return parseDiskRoot(raw);
    }

    NbtValue parseUnnamedRoot(byte[] raw) {
        ByteCursor cursor = new ByteCursor(raw);
        NbtTagId rootType = NbtTagId.fromId(cursor.readUnsignedByte());
        if (rootType == NbtTagId.END) {
            return NbtValue.compound(new LinkedHashMap<>());
        }
        return readPayload(cursor, rootType, 0);
    }

    NbtValue.CompoundValue parseDiskRoot(byte[] raw) {
        ByteCursor cursor = new ByteCursor(raw);
        NbtTagId type = NbtTagId.fromId(cursor.readUnsignedByte());
        if (type != NbtTagId.COMPOUND)
            throw new ParseException("expected disk NBT compound root");
        readClassicString(cursor); // root name
        NbtValue value = readPayload(cursor, type, 0);
        if (cursor.hasRemaining())
            throw new ParseException("trailing bytes after disk NBT root");
        return (NbtValue.CompoundValue) value;
    }

    private NbtValue readPayload(ByteCursor cursor, NbtTagId type, int depth) {
        if (depth > MAX_DEPTH)
            throw new ParseException("NBT nesting exceeds " + MAX_DEPTH);
        return switch (type) {
            case BYTE -> NbtValue.byteValue(cursor.readByte());
            case SHORT -> NbtValue.shortValue(cursor.readShort());
            case INT -> NbtValue.intValue(cursor.readInt());
            case LONG -> NbtValue.longValue(cursor.readLong());
            case FLOAT -> NbtValue.floatValue(Float.intBitsToFloat(cursor.readInt()));
            case DOUBLE -> NbtValue.doubleValue(Double.longBitsToDouble(cursor.readLong()));
            case BYTE_ARRAY -> NbtValue.byteArray(cursor.readBytes(cursor.readInt()));
            case STRING -> NbtValue.stringValue(readClassicString(cursor));
            case LIST -> {
                NbtTagId elementType = NbtTagId.fromId(cursor.readUnsignedByte());
                int size = readLength(cursor, minimumBytes(elementType));
                if (elementType == NbtTagId.END && size != 0)
                    throw new ParseException("nonempty END list");
                List<NbtValue> values = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    values.add(readPayload(cursor, elementType, depth + 1));
                }
                yield NbtValue.list(elementType, List.copyOf(values));
            }
            case COMPOUND -> {
                Map<String, NbtValue> values = new LinkedHashMap<>();
                while (true) {
                    NbtTagId nestedType = NbtTagId.fromId(cursor.readUnsignedByte());
                    if (nestedType == NbtTagId.END) {
                        break;
                    }
                    values.put(readClassicString(cursor), readPayload(cursor, nestedType, depth + 1));
                }
                yield NbtValue.compound(values);
            }
            case INT_ARRAY -> {
                int size = readLength(cursor, Integer.BYTES);
                int[] values = new int[size];
                for (int i = 0; i < size; i++) {
                    values[i] = cursor.readInt();
                }
                yield NbtValue.intArray(values);
            }
            case LONG_ARRAY -> {
                int size = readLength(cursor, Long.BYTES);
                long[] values = new long[size];
                for (int i = 0; i < size; i++) {
                    values[i] = cursor.readLong();
                }
                yield NbtValue.longArray(values);
            }
            case END -> throw new ParseException("unexpected END payload");
        };
    }

    private static int readLength(ByteCursor cursor, int bytesPerEntry) {
        int size = cursor.readInt();
        if (size < 0 || size > cursor.remaining() / bytesPerEntry) {
            throw new ParseException("invalid NBT collection length " + size);
        }
        return size;
    }

    private static int minimumBytes(NbtTagId type) {
        return switch (type) {
            case END, BYTE, COMPOUND -> 1;
            case SHORT, STRING -> Short.BYTES;
            case INT, FLOAT, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY -> Integer.BYTES;
            case LONG, DOUBLE -> Long.BYTES;
            case LIST -> 1 + Integer.BYTES;
        };
    }

    private String readClassicString(ByteCursor cursor) {
        int length = cursor.readUnsignedShort();
        return new String(cursor.readBytes(length), StandardCharsets.UTF_8);
    }
}
