package dev.worldmirror.toolkit.core;

/** Absolute Anvil region coordinate. One region stores 32 x 32 chunks. */
public record RegionPos(int x, int z) implements Comparable<RegionPos> {
    @Override
    public int compareTo(RegionPos other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }

    public String fileName() {
        return "r." + x + "." + z + ".mca";
    }
}
