package dev.worldmirror.toolkit.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.worldmirror.toolkit.schema.PacketKind;
import dev.worldmirror.toolkit.schema.PacketState;
import dev.worldmirror.toolkit.schema.ProtocolSchema;
import dev.worldmirror.toolkit.schema.SchemaRepository;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BundledCompatibilityTest {
    @Test
    void bundledVersionsHaveMatchingProtocolsMappingsAndStatefulPackets() throws IOException {
        assertVersion("26.1.2", 775, 4790, 45);
        assertVersion("26.2", 776, 4903, 45);
        assertVersion("26.3", 777, 5023, 46);
    }

    private void assertVersion(String version, int protocol, int dataVersion, int chunkPacketId) throws IOException {
        ProtocolSchema schema = SchemaRepository.loadBundled().require(version, protocol);
        RegistryMappings mappings = RegistryMappings.loadBundled(version);
        assertEquals(dataVersion, schema.dataVersion());
        assertEquals(dataVersion, mappings.dataVersion());
        assertEquals("minecraft:air", mappings.blockStateName(0));
        assertEquals(PacketKind.REGISTRY_DATA, schema.findByPacketId(PacketState.CONFIGURATION, 7).orElseThrow().kind());
        assertEquals(PacketKind.LEVEL_CHUNK_WITH_LIGHT,
                schema.findByPacketId(PacketState.PLAY, chunkPacketId).orElseThrow().kind());
        assertTrue(schema.findByPacketId(PacketState.CONFIGURATION, chunkPacketId).isEmpty());
    }
}
