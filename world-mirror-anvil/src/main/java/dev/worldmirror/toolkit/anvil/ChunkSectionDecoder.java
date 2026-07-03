package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ByteCursor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChunkSectionDecoder {
    private static final int BLOCK_ENTRIES = 4096;
    private static final int BIOME_ENTRIES = 64;

    DecodeResult decode(byte[] raw, RegistryMappings mappings, int minSectionY) {
        ByteCursor cursor = new ByteCursor(raw);
        List<NbtValue.CompoundValue> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int sectionIndex = 0;
        while (cursor.hasRemaining()) {
            int sectionY = minSectionY + sectionIndex;
            int start = cursor.offset();
            try {
                int nonEmptyBlocks = cursor.readShort();
                cursor.readShort(); // non-empty fluid count
                int bits = cursor.readUnsignedByte();
                PaletteData blockData = readPalette(cursor, bits, BLOCK_ENTRIES, true);
                int biomeBits = cursor.readUnsignedByte();
                readPalette(cursor, biomeBits, BIOME_ENTRIES, false);
                NbtValue.CompoundValue section = buildSection(sectionY, nonEmptyBlocks, bits, blockData, mappings);
                if (section != null) {
                    sections.add(section);
                }
            } catch (RuntimeException ex) {
                warnings.add("section " + sectionIndex + " decode failed at byte " + cursor.offset() + ": " + ex.getMessage());
                cursor.offset(start);
                break;
            }
            sectionIndex++;
        }
        if (cursor.offset() != raw.length) {
            warnings.add("section buffer ended at " + cursor.offset() + ", length " + raw.length);
        }
        return new DecodeResult(sections, warnings);
    }

    private NbtValue.CompoundValue buildSection(int sectionY, int nonEmptyBlocks, int sourceBits, PaletteData source, RegistryMappings mappings) {
        List<NbtValue.CompoundValue> diskPalette = new ArrayList<>();
        List<Integer> diskValues = new ArrayList<>();
        long[] dataLongs = source.rawLongs();

        if (sourceBits < 9) {
            for (int globalId : source.palette()) {
                diskPalette.add(mappings.blockStateNbt(globalId));
            }
            if (diskPalette.size() == 1) {
                diskValues = List.of();
                dataLongs = new long[0];
            } else if (blockStorageBits(diskPalette.size()) != sourceBits) {
                diskValues = unpackBits(source.rawLongs(), sourceBits, BLOCK_ENTRIES);
                dataLongs = new long[0];
            }
        } else {
            Map<Integer, Integer> indexByGlobalId = new LinkedHashMap<>();
            dataLongs = new long[0];
            for (int globalId : source.values()) {
                Integer diskIndex = indexByGlobalId.get(globalId);
                if (diskIndex == null) {
                    diskIndex = diskPalette.size();
                    indexByGlobalId.put(globalId, diskIndex);
                    diskPalette.add(mappings.blockStateNbt(globalId));
                }
                diskValues.add(diskIndex);
            }
        }

        if (diskPalette.isEmpty()) {
            diskPalette.add(mappings.blockStateNbt(0));
        }
        if (nonEmptyBlocks == 0 && diskPalette.size() == 1 && "minecraft:air".equals(mappings.blockStateName(source.palette().isEmpty() ? 0 : source.palette().getFirst()))) {
            return null;
        }

        Map<String, NbtValue> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", NbtValue.list(NbtTagId.COMPOUND, List.copyOf(diskPalette)));
        int diskBits = blockStorageBits(diskPalette.size());
        if (diskBits > 0) {
            // 26.1.2 PalettedContainer.read uses SimpleBitStorage.getRaw() via readFixedSizeLongArray:
            // there is no length prefix; the long count is derived from entries and bits.
            blockStates.put("data", NbtValue.longArray(dataLongs.length > 0 && diskBits == sourceBits
                    ? dataLongs
                    : packBits(diskValues, diskBits)));
        }

        Map<String, NbtValue> biomes = new LinkedHashMap<>();
        // The 26.1.2 biome registry is dynamic in replay packets. Until that registry stream is
        // reconstructed, a single valid biome keeps vanilla and external Anvil tools able to read.
        biomes.put("palette", NbtValue.list(NbtTagId.STRING, List.of(NbtValue.stringValue("minecraft:plains"))));

        Map<String, NbtValue> section = new LinkedHashMap<>();
        section.put("Y", NbtValue.byteValue(sectionY));
        section.put("block_states", NbtValue.compound(blockStates));
        section.put("biomes", NbtValue.compound(biomes));
        return NbtValue.compound(section);
    }

    private PaletteData readPalette(ByteCursor cursor, int bits, int entries, boolean blockPalette) {
        int globalThreshold = blockPalette ? 9 : 4;
        List<Integer> palette = new ArrayList<>();
        if (bits == 0) {
            palette.add(cursor.readVarInt());
        } else if (bits < globalThreshold) {
            int paletteSize = cursor.readVarInt();
            if (paletteSize < 0 || paletteSize > entries || paletteSize > (1 << bits)) {
                throw new IllegalArgumentException("invalid local palette size " + paletteSize + " for " + bits + " bits");
            }
            for (int i = 0; i < paletteSize; i++) {
                palette.add(cursor.readVarInt());
            }
        }
        long[] rawLongs = new long[storageLen(bits, entries)];
        for (int i = 0; i < rawLongs.length; i++) {
            rawLongs[i] = cursor.readLong();
        }
        List<Integer> values = bits >= globalThreshold ? unpackBits(rawLongs, bits, entries) : List.of();
        return new PaletteData(List.copyOf(palette), values, rawLongs);
    }

    private int storageLen(int bits, int entries) {
        if (bits == 0) {
            return 0;
        }
        int valuesPerLong = 64 / bits;
        return Math.ceilDiv(entries, valuesPerLong);
    }

    private List<Integer> unpackBits(long[] data, int bits, int entries) {
        if (bits == 0) {
            return List.of();
        }
        long mask = (1L << bits) - 1L;
        int valuesPerLong = 64 / bits;
        List<Integer> out = new ArrayList<>(entries);
        for (int i = 0; i < entries; i++) {
            long word = data[i / valuesPerLong];
            out.add((int) ((word >>> ((i % valuesPerLong) * bits)) & mask));
        }
        return out;
    }

    private long[] packBits(List<Integer> values, int bits) {
        if (bits == 0) {
            return new long[0];
        }
        long mask = (1L << bits) - 1L;
        int valuesPerLong = 64 / bits;
        long[] out = new long[Math.ceilDiv(values.size(), valuesPerLong)];
        for (int i = 0; i < values.size(); i++) {
            out[i / valuesPerLong] |= ((long) values.get(i) & mask) << ((i % valuesPerLong) * bits);
        }
        return out;
    }

    private int blockStorageBits(int paletteSize) {
        // Based on 26.1.2 Strategy.createForBlockStates: 1-4 bits use the 4-bit local palette,
        // 5-8 use hash palette widths, larger palettes require repacking.
        if (paletteSize <= 1) {
            return 0;
        }
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    record DecodeResult(List<NbtValue.CompoundValue> sections, List<String> warnings) {}
    private record PaletteData(List<Integer> palette, List<Integer> values, long[] rawLongs) {}
}
