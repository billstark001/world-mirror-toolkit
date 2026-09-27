package dev.worldmirror.toolkit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.worldmirror.toolkit.replay.ReplayEvent;
import dev.worldmirror.toolkit.schema.SchemaRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplayProtocolDecoderTest {
    @Test
    void spawnUsesDimensionTypeMinYAndKeepsWorldIdentityAcrossDimensions() throws IOException {
        ReplayProtocolDecoder decoder = new ReplayProtocolDecoder(SchemaRepository.loadBundled().require("26.2"));
        decoder.advance(event(7, registry()));
        decoder.advance(event(3, new byte[0]));

        ReplayProtocolDecoder.SpawnInfo overworld = decoder.tryDecodeWorldSwitch(
                event(82, spawn(0, "minecraft:overworld", -123456789L))).orElseThrow();
        ReplayProtocolDecoder.SpawnInfo nether = decoder.tryDecodeWorldSwitch(
                event(82, spawn(1, "minecraft:the_nether", -123456789L))).orElseThrow();

        assertEquals(-4, overworld.minSectionY());
        assertEquals(0, nether.minSectionY());
        assertEquals(overworld.hashedSeed(), nether.hashedSeed());
    }

    private byte[] registry() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        utf(out, "minecraft:dimension_type");
        out.writeByte(2);
        utf(out, "minecraft:overworld");
        out.writeBoolean(true);
        out.writeByte(10); // unnamed compound root
        out.writeByte(3); // int min_y
        out.writeUTF("min_y");
        out.writeInt(-64);
        out.writeByte(0); // compound end
        utf(out, "minecraft:the_nether");
        out.writeBoolean(false); // vanilla registry value comes from the client
        return bytes.toByteArray();
    }

    private byte[] spawn(int holder, String dimension, long seed) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(holder);
        utf(out, dimension);
        out.writeLong(seed);
        out.writeByte(0); // game mode
        out.writeByte(-1); // previous game mode
        out.writeBoolean(false); // debug
        out.writeBoolean(false); // flat
        out.writeBoolean(false); // last death location absent
        out.writeByte(0); // portal cooldown
        out.writeByte(63); // sea level
        return bytes.toByteArray();
    }

    private ReplayEvent event(int packetId, byte[] body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(packetId);
        bytes.writeBytes(body);
        return new ReplayEvent(0, 0, 0, bytes.toByteArray());
    }

    private void utf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(bytes.length);
        out.write(bytes);
    }
}
