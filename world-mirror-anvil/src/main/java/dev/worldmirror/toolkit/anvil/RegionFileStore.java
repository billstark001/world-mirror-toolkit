package dev.worldmirror.toolkit.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads and rewrites MCA slots while preserving untouched chunk payloads verbatim. */
final class RegionFileStore {
    private static final int SECTOR = 4096;
    private final PortableNbtParser parser = new PortableNbtParser();

    record Slot(byte[] payload, int timestampSeconds) {}

    Map<Integer, Slot> read(Path path) throws IOException {
        Map<Integer, Slot> slots = new TreeMap<>();
        if (!Files.exists(path)) return slots;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() < SECTOR * 2) throw new IOException("truncated region header: " + path);
            byte[] header = new byte[SECTOR * 2];
            file.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int p = i * 4;
                int sector = ((header[p] & 255) << 16) | ((header[p + 1] & 255) << 8)
                        | (header[p + 2] & 255);
                int count = header[p + 3] & 255;
                if (sector == 0 && count == 0) continue;
                if (sector < 2 || count < 1 || ((long) sector + count) * SECTOR > file.length()) {
                    throw new IOException("invalid region slot " + i + " in " + path);
                }
                file.seek((long) sector * SECTOR);
                int length = file.readInt();
                if (length < 1 || length > count * SECTOR - 4) {
                    throw new IOException("invalid chunk length at slot " + i + " in " + path);
                }
                byte[] payload = new byte[length];
                file.readFully(payload);
                int timestamp = ((header[SECTOR + p] & 255) << 24)
                        | ((header[SECTOR + p + 1] & 255) << 16)
                        | ((header[SECTOR + p + 2] & 255) << 8) | (header[SECTOR + p + 3] & 255);
                slots.put(i, new Slot(payload, timestamp));
            }
        }
        return slots;
    }

    NbtValue.CompoundValue chunk(Slot slot) throws IOException {
        int codec = slot.payload()[0] & 255;
        if ((codec & 128) != 0) throw new IOException("external MCA chunk streams are unsupported");
        InputStream raw = new ByteArrayInputStream(slot.payload(), 1, slot.payload().length - 1);
        InputStream decoded = switch (codec) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IOException("unsupported MCA compression " + codec);
        };
        try (decoded; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            decoded.transferTo(bytes);
            return parser.parseDiskRoot(bytes.toByteArray());
        } catch (RuntimeException invalid) {
            throw new IOException("invalid chunk NBT", invalid);
        }
    }

    void write(Path path, Map<Integer, Slot> slots) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "region-", ".tmp");
        try {
            byte[] locations = new byte[SECTOR];
            byte[] timestamps = new byte[SECTOR];
            int sectorCursor = 2;
            try (RandomAccessFile out = new RandomAccessFile(temp.toFile(), "rw")) {
                out.write(new byte[SECTOR * 2]);
                for (Map.Entry<Integer, Slot> entry : new TreeMap<>(slots).entrySet()) {
                    int slot = entry.getKey();
                    if (slot < 0 || slot >= 1024) throw new IOException("invalid chunk slot " + slot);
                    byte[] payload = entry.getValue().payload();
                    int sectors = Math.ceilDiv(payload.length + 4, SECTOR);
                    if (sectors > 255 || sectorCursor > 0xFFFFFF) {
                        throw new IOException("region chunk exceeds MCA sector limit: " + path + " slot " + slot);
                    }
                    int p = slot * 4;
                    locations[p] = (byte) (sectorCursor >>> 16);
                    locations[p + 1] = (byte) (sectorCursor >>> 8);
                    locations[p + 2] = (byte) sectorCursor;
                    locations[p + 3] = (byte) sectors;
                    int time = entry.getValue().timestampSeconds();
                    timestamps[p] = (byte) (time >>> 24);
                    timestamps[p + 1] = (byte) (time >>> 16);
                    timestamps[p + 2] = (byte) (time >>> 8);
                    timestamps[p + 3] = (byte) time;
                    out.seek((long) sectorCursor * SECTOR);
                    out.writeInt(payload.length);
                    out.write(payload);
                    out.setLength((long) (sectorCursor + sectors) * SECTOR);
                    sectorCursor += sectors;
                }
                out.seek(0);
                out.write(locations);
                out.write(timestamps);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
