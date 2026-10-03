package net.clanimg.worldsGUI.backup;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AnalysisChecks {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(fixtures);
        Path stateDirectory = Files.createTempDirectory(fixtures, "state-");
        RestoreStateStore store = new RestoreStateStore(stateDirectory);
        store.write("WrobelXXL-5051", 42L, RestoreStateStore.Phase.SWAPPING);
        check(store.read("WrobelXXL-5051").orElseThrow().phase() == RestoreStateStore.Phase.SWAPPING,
            "Persisted SWAPPING state survives a new read");
        check(store.hasState("wrobelxxl-5051"), "Busy detection finds differently cased state name");
        check(!store.hasState("OtherWorld"), "Unrelated world is not blocked");
        Files.writeString(stateDirectory.resolve("Broken.state"), "job-id=42\nphase=INVALID\n");
        boolean failedClosed = false;
        try { store.read("Broken"); } catch (BackupException expected) { failedClosed = true; }
        check(failedClosed, "Invalid persisted state fails closed instead of appearing absent");
        check(!WorldFingerprint.isExcludedFromArchive("level.dat"), "level.dat is included by archive filter");
        check(!WorldFingerprint.isExcludedFromArchive("level.dat_old"), "level.dat_old is included by archive filter");
        check(WorldFingerprint.isExcludedFromArchive("session.lock"), "session.lock remains excluded");
        check(WorldFingerprint.isExcludedFromArchive("uid.dat"), "uid.dat remains excluded");
        Path world = Files.createTempDirectory(fixtures, "world-");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "fixture");
        Files.writeString(world.resolve("level.dat"), "flat-fixture");
        String before = WorldFingerprint.compute(world).fingerprint();
        Files.writeString(world.resolve("level.dat"), "noise-fixture-different-size");
        check(before.equals(WorldFingerprint.compute(world).fingerprint()),
            "Confirmed existing limitation: level.dat-only changes do not affect fingerprint");
    }
}
