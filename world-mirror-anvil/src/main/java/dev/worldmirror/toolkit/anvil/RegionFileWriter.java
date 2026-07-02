package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ChunkPos;
import dev.worldmirror.toolkit.core.IoUtil;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.io.ByteArrayOutputStream;

/** Streaming writer for Anvil region files. */
public final class RegionFileWriter {
    private static final int SECTOR_BYTES = 4096;

    private final PortableNbtWriter nbtWriter = new PortableNbtWriter();

    public void write(Path regionPath, Map<ChunkPos, NbtValue.CompoundValue> chunks) throws IOException {
        IoUtil.createParentDirectories(regionPath);
        byte[] locations = new byte[SECTOR_BYTES];
        byte[] timestamps = new byte[SECTOR_BYTES];
        int sectorCursor = 2;
        int now = (int) Instant.now().getEpochSecond();
        Map<ChunkPos, NbtValue.CompoundValue> sorted = new TreeMap<>(chunks);

        try (RandomAccessFile out = new RandomAccessFile(regionPath.toFile(), "rw")) {
            out.setLength(0);
            out.write(new byte[SECTOR_BYTES * 2]);
            for (Map.Entry<ChunkPos, NbtValue.CompoundValue> entry : sorted.entrySet()) {
                byte[] nbt = nbtWriter.writeUnnamedRoot(entry.getValue());
                byte[] compressed = zlib(nbt);
                int payloadLength = compressed.length + 1;
                int chunkBlobLength = payloadLength + 4;
                int sectorCount = Math.ceilDiv(chunkBlobLength, SECTOR_BYTES);
                if (sectorCount > 255) {
                    throw new IOException("chunk " + entry.getKey() + " requires " + sectorCount + " sectors, maximum is 255");
                }
                int tableIndex = entry.getKey().localIndex() * 4;
                locations[tableIndex] = (byte) ((sectorCursor >>> 16) & 0xFF);
                locations[tableIndex + 1] = (byte) ((sectorCursor >>> 8) & 0xFF);
                locations[tableIndex + 2] = (byte) (sectorCursor & 0xFF);
                locations[tableIndex + 3] = (byte) sectorCount;
                timestamps[tableIndex] = (byte) ((now >>> 24) & 0xFF);
                timestamps[tableIndex + 1] = (byte) ((now >>> 16) & 0xFF);
                timestamps[tableIndex + 2] = (byte) ((now >>> 8) & 0xFF);
                timestamps[tableIndex + 3] = (byte) (now & 0xFF);

                out.seek((long) sectorCursor * SECTOR_BYTES);
                out.writeInt(payloadLength);
                out.writeByte(2); // zlib
                out.write(compressed);
                int padding = sectorCount * SECTOR_BYTES - chunkBlobLength;
                if (padding > 0) {
                    out.write(new byte[padding]);
                }
                sectorCursor += sectorCount;
            }
            out.seek(0);
            out.write(locations);
            out.write(timestamps);
        }
    }

    private byte[] zlib(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream out = new DeflaterOutputStream(baos, deflater)) {
            out.write(input);
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }
}
