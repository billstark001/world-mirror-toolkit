package dev.worldmirror.toolkit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldLayoutTest {
    @Test
    void versionDefaultsAndDimensionPathsMatchMinecraftLayouts() {
        assertEquals(WorldLayout.LEGACY, WorldLayout.forVersion("1.21.11"));
        assertEquals(WorldLayout.NAMESPACED, WorldLayout.forVersion("26.1"));
        assertEquals(WorldLayout.NAMESPACED, WorldLayout.forVersion("26.2"));
        assertEquals(WorldLayout.NAMESPACED, WorldLayout.forVersion("27.0"));
        Path save = Path.of("save");
        assertEquals(save.resolve("DIM-1"), WorldLayout.LEGACY.dimensionPath(save,
                new DimensionKey("minecraft:the_nether")));
        assertEquals(save.resolve("dimensions/minecraft/the_nether"),
                WorldLayout.NAMESPACED.dimensionPath(save,
                        new DimensionKey("minecraft:the_nether")));
    }
}
