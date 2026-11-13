package dev.worldmirror.toolkit.anvil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the void mirror's biome to its vanilla counterpart before importing into a seeded save. */
final class SeededBiomePalette {
    private static final int CELLS = 64;

    private SeededBiomePalette() {}

    static void remap(Map<String, NbtValue> fields, String dimension) {
        String mirror = switch (dimension) {
            case "minecraft:overworld" -> "worldmirror:mirror_overworld";
            case "minecraft:the_nether" -> "worldmirror:mirror_nether";
            case "minecraft:the_end" -> "worldmirror:mirror_end";
            default -> null;
        };
        String vanilla = switch (dimension) {
            case "minecraft:overworld" -> "minecraft:plains";
            case "minecraft:the_nether" -> "minecraft:nether_wastes";
            case "minecraft:the_end" -> "minecraft:the_end";
            default -> null;
        };
        var tag = fields.get("sections");
        if (tag == null) return;
        if (!(tag instanceof NbtValue.ListValue sections)) throw new IllegalArgumentException("invalid terrain sections");
        var updated = new ArrayList<NbtValue>(sections.values().size());
        boolean changed = false;
        for (NbtValue value : sections.values()) {
            if (!(value instanceof NbtValue.CompoundValue section)) {
                throw new IllegalArgumentException("invalid terrain section");
            }
            var remapped = remapSection(section, mirror, vanilla);
            updated.add(remapped);
            changed |= remapped != section;
        }
        if (changed) fields.put("sections", NbtValue.list(NbtTagId.COMPOUND, updated));
    }

    private static NbtValue.CompoundValue remapSection(NbtValue.CompoundValue section, String mirror, String vanilla) {
        var tag = section.values().get("biomes");
        if (tag == null) return section;
        if (!(tag instanceof NbtValue.CompoundValue biomes)
                || !(biomes.values().get("palette") instanceof NbtValue.ListValue palette)) {
            throw new IllegalArgumentException("invalid biome palette");
        }
        var values = palette.values();
        boolean changed = false;
        for (NbtValue value : values) {
            if (!(value instanceof NbtValue.StringValue name)) throw new IllegalArgumentException("invalid biome name");
            if (name.value().startsWith("worldmirror:")) {
                if (!name.value().equals(mirror)) throw new IllegalArgumentException("unexpected owned biome " + name.value());
                changed = true;
            }
        }
        if (!changed) return section;
        if (values.size() > CELLS) throw new IllegalArgumentException("invalid biome palette size");

        var indices = new LinkedHashMap<NbtValue, Integer>();
        int[] remap = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            NbtValue key = NbtValue.stringValue(mirror).equals(values.get(i))
                    ? NbtValue.stringValue(vanilla) : values.get(i);
            remap[i] = indices.computeIfAbsent(key, ignored -> indices.size());
        }
        int oldBits = bits(values.size());
        long[] oldData = biomes.values().get("data") instanceof NbtValue.LongArrayValue data ? data.value() : null;
        if (oldBits > 0 && (oldData == null || oldData.length != words(oldBits))) {
            throw new IllegalArgumentException("invalid packed biome data");
        }
        int newBits = bits(indices.size());
        long[] packed = newBits == 0 ? null : new long[words(newBits)];
        for (int i = 0; i < CELLS; i++) {
            int oldIndex = oldBits == 0 ? 0 : (int) ((oldData[i / (64 / oldBits)]
                    >>> ((i % (64 / oldBits)) * oldBits)) & ((1L << oldBits) - 1));
            if (oldIndex >= remap.length) throw new IllegalArgumentException("invalid packed biome index");
            if (packed != null) packed[i / (64 / newBits)] |= (long) remap[oldIndex] << ((i % (64 / newBits)) * newBits);
        }

        var biomeFields = new LinkedHashMap<>(biomes.values());
        biomeFields.put("palette", NbtValue.list(NbtTagId.STRING, List.copyOf(indices.keySet())));
        if (packed == null) biomeFields.remove("data");
        else biomeFields.put("data", NbtValue.longArray(packed));
        var sectionFields = new LinkedHashMap<>(section.values());
        sectionFields.put("biomes", NbtValue.compound(biomeFields));
        return NbtValue.compound(sectionFields);
    }

    private static int bits(int entries) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(entries - 1);
    }

    private static int words(int bits) {
        return Math.ceilDiv(CELLS, 64 / bits);
    }
}
