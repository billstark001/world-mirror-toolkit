package dev.worldmirror.toolkit.anvil;

import dev.worldmirror.toolkit.core.ByteCursor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LightDataDecoder {
    DecodeResult decode(byte[] raw, int minLightSectionY) {
        ByteCursor cursor = new ByteCursor(raw);
        List<String> warnings = new ArrayList<>();
        try {
            List<Integer> skyMask = setBits(readLongArray(cursor));
            List<Integer> blockMask = setBits(readLongArray(cursor));
            readLongArray(cursor);
            readLongArray(cursor);
            List<byte[]> skyUpdates = readByteArrayList(cursor);
            List<byte[]> blockUpdates = readByteArrayList(cursor);
            Map<Integer, byte[]> sky = attach(minLightSectionY, skyMask, skyUpdates);
            Map<Integer, byte[]> block = attach(minLightSectionY, blockMask, blockUpdates);
            if (cursor.offset() != raw.length) {
                warnings.add("light buffer ended at " + cursor.offset() + ", length " + raw.length);
            }
            return new DecodeResult(sky, block, warnings);
        } catch (RuntimeException ex) {
            warnings.add("light decode failed at byte " + cursor.offset() + ": " + ex.getMessage());
            return new DecodeResult(Map.of(), Map.of(), warnings);
        }
    }

    private Map<Integer, byte[]> attach(int minLightSectionY, List<Integer> mask, List<byte[]> updates) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int count = Math.min(mask.size(), updates.size());
        for (int i = 0; i < count; i++) {
            byte[] data = updates.get(i);
            if (data.length == 2048) {
                out.put(minLightSectionY + mask.get(i), data);
            }
        }
        return out;
    }

    private long[] readLongArray(ByteCursor cursor) {
        int size = cursor.readVarInt();
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            values[i] = cursor.readLong();
        }
        return values;
    }

    private List<byte[]> readByteArrayList(ByteCursor cursor) {
        int size = cursor.readVarInt();
        List<byte[]> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(cursor.readBytes(cursor.readVarInt()));
        }
        return values;
    }

    private List<Integer> setBits(long[] values) {
        List<Integer> out = new ArrayList<>();
        for (int wordIndex = 0; wordIndex < values.length; wordIndex++) {
            long word = values[wordIndex];
            for (int bit = 0; bit < 64; bit++) {
                if (((word >>> bit) & 1L) != 0) {
                    out.add(wordIndex * 64 + bit);
                }
            }
        }
        return out;
    }

    record DecodeResult(Map<Integer, byte[]> sky, Map<Integer, byte[]> block, List<String> warnings) {}
}
