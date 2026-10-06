package net.clanimg.worldsGUI.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Bounded, read-only NBT probe for diagnostics; never used to change or validate a restore. */
public final class LevelDatGenerationProbe {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ELEMENTS = 100_000;

    private LevelDatGenerationProbe() {
    }

    public static String describe(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "missing";
        }
        byte[] bytes;
        try (InputStream gzip = new GZIPInputStream(Files.newInputStream(file))) {
            bytes = gzip.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("NBT exceeds diagnostic size limit");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedByte() != 10) {
                throw new IOException("NBT root is not a compound");
            }
            in.readUTF();
            Map<?, ?> generation = scanRoot(in);
            if (generation == null) {
                return "WorldGenSettings=missing";
            }
            Object dimensionValue = generation.get("dimensions");
            if (!(dimensionValue instanceof Map<?, ?> dimensions)) {
                return "seed=" + generation.get("seed") + " dimensions=missing";
            }
            List<String> summaries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : dimensions.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> dimension)
                    || !(dimension.get("generator") instanceof Map<?, ?> generator)) {
                    summaries.add(entry.getKey() + "=missing-generator");
                    continue;
                }
                String summary = entry.getKey() + "=" + generator.get("type");
                Object settings = generator.get("settings");
                if (settings instanceof Map<?, ?> flatSettings) {
                    summary += "(biome=" + flatSettings.get("biome")
                        + ",layers=" + flatSettings.get("layers") + ")";
                } else if (settings != null) {
                    summary += "(settings=" + settings + ")";
                }
                summaries.add(summary);
            }
            return "seed=" + generation.get("seed") + " dimensions=" + summaries;
        }
    }

    private static Map<?, ?> scanRoot(DataInputStream in) throws IOException {
        Map<?, ?> found = null;
        for (int type; (type = in.readUnsignedByte()) != 0;) {
            String name = in.readUTF();
            if (name.equals("Data") && type == 10) {
                for (int dataType; (dataType = in.readUnsignedByte()) != 0;) {
                    String dataName = in.readUTF();
                    if (dataName.equals("WorldGenSettings") && dataType == 10) {
                        if (found != null) {
                            throw new IOException("Duplicate WorldGenSettings");
                        }
                        found = (Map<?, ?>) readValue(in, dataType, 2, new int[] {0});
                    } else {
                        skipValue(in, dataType, 2);
                    }
                }
            } else {
                skipValue(in, type, 1);
            }
        }
        return found;
    }

    private static Object readValue(DataInputStream in, int type, int depth, int[] elements) throws IOException {
        checkDepth(depth);
        if (++elements[0] > MAX_ELEMENTS) {
            throw new IOException("NBT exceeds diagnostic element limit");
        }
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 8 -> in.readUTF();
            case 9 -> {
                int childType = in.readUnsignedByte();
                int length = length(in, 1);
                if (length > MAX_ELEMENTS || (childType == 0 && length != 0)) {
                    throw new IOException("Invalid or oversized NBT list");
                }
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    values.add(readValue(in, childType, depth + 1, elements));
                }
                yield values;
            }
            case 10 -> {
                Map<String, Object> compound = new LinkedHashMap<>();
                for (int childType; (childType = in.readUnsignedByte()) != 0;) {
                    String name = in.readUTF();
                    if (compound.containsKey(name)) {
                        throw new IOException("Duplicate NBT key: " + name);
                    }
                    compound.put(name, readValue(in, childType, depth + 1, elements));
                }
                yield compound;
            }
            case 7, 11, 12 -> {
                int width = type == 7 ? 1 : type == 11 ? 4 : 8;
                int length = length(in, width);
                in.skipNBytes((long) length * width);
                yield "array[" + length + "]";
            }
            default -> throw new IOException("Unsupported NBT tag: " + type);
        };
    }

    private static void skipValue(DataInputStream in, int type, int depth) throws IOException {
        checkDepth(depth);
        switch (type) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 7, 11, 12 -> {
                int width = type == 7 ? 1 : type == 11 ? 4 : 8;
                in.skipNBytes((long) length(in, width) * width);
            }
            case 9 -> {
                int childType = in.readUnsignedByte();
                int length = length(in, 1);
                if (length > MAX_ELEMENTS || (childType == 0 && length != 0)) {
                    throw new IOException("Invalid or oversized NBT list");
                }
                for (int i = 0; i < length; i++) {
                    skipValue(in, childType, depth + 1);
                }
            }
            case 10 -> {
                for (int childType; (childType = in.readUnsignedByte()) != 0;) {
                    in.skipNBytes(in.readUnsignedShort());
                    skipValue(in, childType, depth + 1);
                }
            }
            default -> throw new IOException("Unsupported NBT tag: " + type);
        }
    }

    private static int length(DataInputStream in, int width) throws IOException {
        int length = in.readInt();
        if (length < 0 || (long) length * width > in.available()) {
            throw new IOException("Invalid NBT length: " + length);
        }
        return length;
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT exceeds diagnostic depth limit");
        }
    }
}
