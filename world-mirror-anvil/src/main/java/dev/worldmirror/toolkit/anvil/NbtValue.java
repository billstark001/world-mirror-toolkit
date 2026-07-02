package dev.worldmirror.toolkit.anvil;

import java.util.List;
import java.util.Map;

/** Small immutable NBT AST used as an isolation layer around external NBT libraries. */
public sealed interface NbtValue permits NbtValue.ByteValue, NbtValue.IntValue, NbtValue.LongValue,
        NbtValue.StringValue, NbtValue.ByteArrayValue, NbtValue.LongArrayValue, NbtValue.ListValue, NbtValue.CompoundValue {
    NbtTagId tagId();

    record ByteValue(byte value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.BYTE; } }
    record IntValue(int value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.INT; } }
    record LongValue(long value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.LONG; } }
    record StringValue(String value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.STRING; } }
    record ByteArrayValue(byte[] value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.BYTE_ARRAY; } }
    record LongArrayValue(long[] value) implements NbtValue { public NbtTagId tagId() { return NbtTagId.LONG_ARRAY; } }
    record ListValue(NbtTagId elementType, List<NbtValue> values) implements NbtValue { public NbtTagId tagId() { return NbtTagId.LIST; } }
    record CompoundValue(Map<String, NbtValue> values) implements NbtValue { public NbtTagId tagId() { return NbtTagId.COMPOUND; } }

    static ByteValue byteValue(boolean value) { return new ByteValue((byte) (value ? 1 : 0)); }
    static ByteValue byteValue(int value) { return new ByteValue((byte) value); }
    static IntValue intValue(int value) { return new IntValue(value); }
    static LongValue longValue(long value) { return new LongValue(value); }
    static StringValue stringValue(String value) { return new StringValue(value); }
    static ByteArrayValue byteArray(byte[] value) { return new ByteArrayValue(value); }
    static LongArrayValue longArray(long[] value) { return new LongArrayValue(value); }
    static ListValue list(NbtTagId elementType, List<NbtValue> values) { return new ListValue(elementType, values); }
    static CompoundValue compound(Map<String, NbtValue> values) { return new CompoundValue(values); }
}
