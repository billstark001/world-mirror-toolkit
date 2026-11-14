package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.worldmirror.toolkit.core.ParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PortableNbtParserTest {
    private final PortableNbtParser parser = new PortableNbtParser();

    @ParameterizedTest
    @EnumSource(value = NbtTagId.class, names = {"BYTE_ARRAY", "INT_ARRAY", "LONG_ARRAY", "LIST"})
    void refusesTruncatedCollectionsBeforeAllocating(NbtTagId type) {
        ByteBuffer nbt = ByteBuffer.allocate(11);
        nbt.put((byte) NbtTagId.COMPOUND.id()).putShort((short) 0);
        nbt.put((byte) type.id()).putShort((short) 0);
        if (type == NbtTagId.LIST)
            nbt.put((byte) NbtTagId.BYTE.id());
        nbt.putInt(Integer.MAX_VALUE);
        assertThrows(ParseException.class, () -> parser.parseDiskRoot(nbt.array()));
    }

    @Test
    void refusesExcessiveNestingAndDecodedBytes() {
        ByteBuffer nbt = ByteBuffer.allocate(514 * 4);
        for (int i = 0; i < 514; i++) nbt.put((byte) NbtTagId.COMPOUND.id()).putShort((short) 0);
        assertThrows(ParseException.class, () -> parser.parseDiskRoot(nbt.array()));
        assertThrows(
                IOException.class, () -> parser.parseDiskRoot(new ByteArrayInputStream(nbt.array()), 16));
    }
}
