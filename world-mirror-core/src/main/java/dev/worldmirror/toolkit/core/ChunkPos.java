package dev.worldmirror.toolkit.core;

/** Absolute chunk coordinate in Minecraft chunk units. */
public record ChunkPos(int x, int z) implements Comparable<ChunkPos> {
    @Override
    public int compareTo(ChunkPos other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }

    public RegionPos region() {
        return new RegionPos(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
    }

    public int localIndex() {
        return (x & 31) + (z & 31) * 32;
    }
}
