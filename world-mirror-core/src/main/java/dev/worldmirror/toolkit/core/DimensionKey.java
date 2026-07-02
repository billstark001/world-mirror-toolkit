package dev.worldmirror.toolkit.core;

import java.util.Objects;

/** Namespaced Minecraft dimension key such as {@code minecraft:overworld}. */
public record DimensionKey(String value) {
    public static final DimensionKey OVERWORLD = new DimensionKey("minecraft:overworld");
    public static final DimensionKey NETHER = new DimensionKey("minecraft:the_nether");
    public static final DimensionKey END = new DimensionKey("minecraft:the_end");

    public DimensionKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("dimension key must not be blank");
        }
    }

    /** Directory suffix used by vanilla save layout. */
    public String saveSubdirectory() {
        return switch (value) {
            case "minecraft:overworld" -> ".";
            case "minecraft:the_nether" -> "DIM-1";
            case "minecraft:the_end" -> "DIM1";
            default -> "dimensions/" + value.replace(':', '/');
        };
    }
}
