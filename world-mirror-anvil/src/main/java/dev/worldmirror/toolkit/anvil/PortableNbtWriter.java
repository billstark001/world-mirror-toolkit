package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ParseException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fallback NBT binary writer for the toolkit's minimal AST.
 *
 * <p>The production dependency is the ens-gijs/Querz fork; this writer exists as an isolation and
 * testing layer, and as a safe fallback if the external MCA API changes. It only writes the tag
 * types used by the analysis exporter.</p>
 */
public final class PortableNbtWriter {
    public byte[] writeUnnamedRoot(NbtValue.CompoundValue root) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(NbtTagId.COMPOUND.id());
            writeStringPayload(out, "");
            writePayload(out, root);
            out.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            throw new ParseException("failed to serialize NBT", ex);
        }
    }

    private void writeNamed(DataOutputStream out, String name, NbtValue value) throws IOException {
        out.writeByte(value.tagId().id());
        writeStringPayload(out, name);
        writePayload(out, value);
    }

    private void writePayload(DataOutputStream out, NbtValue value) throws IOException {
        switch (value) {
            case NbtValue.ByteValue v -> out.writeByte(v.value());
            case NbtValue.ShortValue v -> out.writeShort(v.value());
            case NbtValue.IntValue v -> out.writeInt(v.value());
            case NbtValue.LongValue v -> out.writeLong(v.value());
            case NbtValue.FloatValue v -> out.writeFloat(v.value());
            case NbtValue.DoubleValue v -> out.writeDouble(v.value());
            case NbtValue.StringValue v -> writeStringPayload(out, v.value());
            case NbtValue.ByteArrayValue v -> {
                out.writeInt(v.value().length);
                out.write(v.value());
            }
            case NbtValue.IntArrayValue v -> {
                out.writeInt(v.value().length);
                for (int item : v.value()) {
                    out.writeInt(item);
                }
            }
            case NbtValue.LongArrayValue v -> {
                out.writeInt(v.value().length);
                for (long item : v.value()) {
                    out.writeLong(item);
                }
            }
            case NbtValue.ListValue v -> {
                out.writeByte(v.elementType().id());
                out.writeInt(v.values().size());
                for (NbtValue item : v.values()) {
                    if (item.tagId() != v.elementType()) {
                        throw new ParseException("NBT list element type mismatch: expected " + v.elementType() + ", got " + item.tagId());
                    }
                    writePayload(out, item);
                }
            }
            case NbtValue.CompoundValue v -> {
                for (Map.Entry<String, NbtValue> entry : v.values().entrySet()) {
                    writeNamed(out, entry.getKey(), entry.getValue());
                }
                out.writeByte(NbtTagId.END.id());
            }
        }
    }

    private void writeStringPayload(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 65535) {
            throw new ParseException("NBT string too long: " + raw.length + " bytes");
        }
        out.writeShort(raw.length);
        out.write(raw);
    }
}
