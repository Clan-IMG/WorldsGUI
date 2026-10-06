import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import net.clanimg.worldsGUI.diagnostics.LevelDatGenerationProbe;

public final class GenerationProbeChecks {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS: " + label);
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        Path flatFile = fixtures.resolve("flat.dat");
        byte[] before = Files.readAllBytes(flatFile);
        String flat = LevelDatGenerationProbe.describe(flatFile);
        check(flat.contains("minecraft:overworld=minecraft:flat"), "Exact Overworld FLAT path");
        check(flat.contains("minecraft:dirt") && flat.contains("height=64"), "Actual flat layers are read");
        check(flat.contains("seed=-1234567890123456"), "Signed 64-bit seed preserved");
        check(Arrays.equals(before, Files.readAllBytes(flatFile)), "Probe leaves file byte-for-byte unchanged");
        String noise = LevelDatGenerationProbe.describe(fixtures.resolve("noise.dat"));
        check(noise.contains("minecraft:overworld=minecraft:noise"), "Exact Overworld NOISE path");
        String netherFlat = LevelDatGenerationProbe.describe(fixtures.resolve("flat-only-nether.dat"));
        check(netherFlat.contains("minecraft:overworld=minecraft:noise")
            && netherFlat.contains("minecraft:the_nether=minecraft:flat"), "FLAT in another dimension does not identify Overworld as flat");
        check(LevelDatGenerationProbe.describe(fixtures.resolve("missing-generation.dat")).equals("WorldGenSettings=missing"),
            "Unrelated flat string is ignored");
        check(LevelDatGenerationProbe.describe(fixtures.resolve("absent.dat")).equals("missing"), "Missing file is explicit");
        for (String name : new String[] {"not-gzip.dat", "truncated.dat", "oversized.dat", "negative-array.dat", "duplicate-generation.dat"}) {
            boolean rejected = false;
            try { LevelDatGenerationProbe.describe(fixtures.resolve(name)); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "Malformed input detected: " + name);
        }
        System.out.println(checks + " checks passed");
    }
}
