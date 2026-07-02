package dev.worldmirror.toolkit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class ByteCursorTest {
    @Test
    void readsVarInt() {
        assertEquals(300, new ByteCursor(new byte[] {(byte) 0xAC, 0x02}).readVarInt());
    }
}
