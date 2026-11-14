package dev.worldmirror.toolkit.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requirePlainFile;
import static dev.worldmirror.toolkit.anvil.ImportSavePaths.requirePlainDirectory;

/** Region inputs shared by preview and verified installation. */
final class ImportRegion {
    private final RegionFileStore regions;

    ImportRegion(RegionFileStore regions) {
        this.regions = regions;
    }

    BitSet occupancy(Path path, Map<Path, BitSet> cache) {
        if (!cache.containsKey(path)) {
            try {
                requirePlainFile(path);
                cache.put(path, regions.occupiedSlots(path));
            } catch (IOException unreadable) {
                cache.put(path, null); // An unreadable neighboring region cannot prove an empty slot.
            }
        }
        return cache.get(path);
    }

    BitSet auxiliarySlots(Path region) throws IOException {
        var occupied = new BitSet(1024);
        Path dimension = region.getParent().getParent();
        for (String name : List.of("entities", "poi")) {
            Path directory = dimension.resolve(name);
            requirePlainDirectory(directory);
            Path sibling = directory.resolve(region.getFileName());
            requirePlainFile(sibling);
            occupied.or(regions.occupiedSlots(sibling));
        }
        return occupied;
    }

    static boolean matches(RegionFileStore.Slot actual, RegionFileStore.Slot expected) {
        return actual != null && actual.timestampSeconds() == expected.timestampSeconds()
                && Arrays.equals(actual.payload(), expected.payload());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    static String fingerprint(MessageDigest hasher, RegionFileStore.Slot slot) {
        if (slot == null)
            return null;
        int timestamp = slot.timestampSeconds();
        for (int shift = 24; shift >= 0; shift -= 8) hasher.update((byte) (timestamp >>> shift));
        return HexFormat.of().formatHex(hasher.digest(slot.payload()));
    }
}
