package dev.worldmirror.toolkit.anvil;

/** Vanilla NBT tag ids. */
public enum NbtTagId {
    END(0), BYTE(1), SHORT(2), INT(3), LONG(4), FLOAT(5), DOUBLE(6), BYTE_ARRAY(7), STRING(8), LIST(9), COMPOUND(10), INT_ARRAY(11), LONG_ARRAY(12);

    private final int id;

    NbtTagId(int id) { this.id = id; }
    public int id() { return id; }

    public static NbtTagId fromId(int id) {
        for (NbtTagId tag : values()) {
            if (tag.id == id) {
                return tag;
            }
        }
        throw new IllegalArgumentException("unknown NBT tag id " + id);
    }
}
