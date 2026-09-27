package dev.worldmirror.toolkit.protocol;

import dev.worldmirror.toolkit.core.ByteCursor;
import dev.worldmirror.toolkit.core.ParseException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

/**
 * Minimal network-NBT skipper for packet field alignment.
 *
 * <p>Minecraft packet NBT compounds are often unnamed. This skipper does not allocate tag objects;
 * it only advances the cursor to the byte immediately after a tag payload. Full NBT parsing/writing is
 * delegated to the ens-gijs/Querz fork in the Anvil module.</p>
 */
public final class NetworkNbtSkipper {
    private static final int MAX_DEPTH = 512;
    private static final int MAX_LIST_ITEMS = 4_194_304;
    private static final int MAX_ARRAY_BYTES = 64 * 1024 * 1024;

    public void skipUnnamedRoot(ByteCursor cursor) {
        int rootType = cursor.readUnsignedByte();
        if (rootType == 0) {
            return;
        }
        skipPayload(cursor, rootType, 0);
    }

    /** Reads one integer field while advancing past an unnamed network compound. */
    public OptionalInt readRootInt(ByteCursor cursor, String field) {
        int rootType = cursor.readUnsignedByte();
        if (rootType != 10) {
            throw new ParseException("expected NBT compound root, got " + rootType);
        }
        OptionalInt result = OptionalInt.empty();
        while (true) {
            int child = cursor.readUnsignedByte();
            if (child == 0) return result;
            int length = cursor.readUnsignedShort();
            String name = new String(cursor.readBytes(length), StandardCharsets.UTF_8);
            if (child == 3 && field.equals(name)) {
                result = OptionalInt.of(cursor.readInt());
            } else {
                skipPayload(cursor, child, 1);
            }
        }
    }

    private void skipPayload(ByteCursor cursor, int tagType, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ParseException("NBT nesting exceeds " + MAX_DEPTH);
        }
        switch (tagType) {
            case 0 -> { }
            case 1 -> cursor.skip(1);
            case 2 -> cursor.skip(2);
            case 3, 5 -> cursor.skip(4);
            case 4, 6 -> cursor.skip(8);
            case 7 -> {
                int length = cursor.readInt();
                if (length < 0 || length > MAX_ARRAY_BYTES) throw new ParseException("bad NBT byte-array length " + length);
                cursor.skip(length);
            }
            case 8 -> {
                int length = cursor.readUnsignedShort();
                cursor.skip(length);
            }
            case 9 -> {
                int elem = cursor.readUnsignedByte();
                int length = cursor.readInt();
                if (length < 0 || length > MAX_LIST_ITEMS) throw new ParseException("bad NBT list length " + length);
                for (int i = 0; i < length; i++) {
                    skipPayload(cursor, elem, depth + 1);
                }
            }
            case 10 -> {
                while (true) {
                    int child = cursor.readUnsignedByte();
                    if (child == 0) return;
                    int nameLength = cursor.readUnsignedShort();
                    cursor.skip(nameLength);
                    skipPayload(cursor, child, depth + 1);
                }
            }
            case 11 -> {
                int length = cursor.readInt();
                if (length < 0 || length > MAX_LIST_ITEMS) throw new ParseException("bad NBT int-array length " + length);
                cursor.skip(length * 4);
            }
            case 12 -> {
                int length = cursor.readInt();
                if (length < 0 || length > MAX_LIST_ITEMS) throw new ParseException("bad NBT long-array length " + length);
                cursor.skip(length * 8);
            }
            default -> throw new ParseException("unsupported NBT tag id " + tagType + " at offset " + cursor.offset());
        }
    }
}
