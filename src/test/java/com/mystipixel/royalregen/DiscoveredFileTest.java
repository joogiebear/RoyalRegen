package com.mystipixel.royalregen;

import com.mystipixel.royalregen.util.DataFiles;
import com.mystipixel.royalregen.util.SnapshotWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredFileTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir
    Path dir;

    private static List<Path> corruptCopies(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("discovered.yml.corrupt-")).toList();
        }
    }

    private static Logger capturing(List<LogRecord> into) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                into.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    @Test
    void entriesRoundTripThroughTheWriter() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        DataFiles.write(file, DiscoveryService.serialise(Map.of(
                ALEX.toString(), List.of("farm", "mine"),
                SAM.toString(), List.of("farm"))));
        List<LogRecord> logs = new ArrayList<>();
        assertEquals(Map.of(ALEX, Set.of("farm", "mine"), SAM, Set.of("farm")),
                DiscoveryService.readEntries(file, capturing(logs)));
        assertTrue(logs.isEmpty());
    }

    @Test
    void aFileWrittenByTheOldInPlaceSaveStillLoads() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        Files.writeString(file.toPath(), ALEX + ":\n- farm\n- mine\n");
        assertEquals(Map.of(ALEX, Set.of("farm", "mine")),
                DiscoveryService.readEntries(file, Logger.getAnonymousLogger()));
    }

    @Test
    void sameStateSerialisesTheSame() {
        Map<String, List<String>> a = Map.of(ALEX.toString(), List.of("farm", "mine"), SAM.toString(), List.of("farm"));
        Map<String, List<String>> b = Map.of(SAM.toString(), List.of("farm"), ALEX.toString(), List.of("farm", "mine"));
        assertEquals(DiscoveryService.serialise(a), DiscoveryService.serialise(b));
    }

    @Test
    void anEmptyMapIsNotWrittenAsAnEmptyFile() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        DataFiles.write(file, DiscoveryService.serialise(Map.of()));
        assertTrue(file.length() > 0);
        List<LogRecord> logs = new ArrayList<>();
        assertEquals(Map.of(), DiscoveryService.readEntries(file, capturing(logs)));
        assertTrue(logs.isEmpty());
        assertTrue(corruptCopies(dir).isEmpty());
    }

    @Test
    void aDamagedFileIsKeptAsideAndLoggedClearly() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        String damaged = ALEX + ":\n- farm\n- \"mi";
        Files.writeString(file.toPath(), damaged);
        List<LogRecord> logs = new ArrayList<>();

        assertEquals(Map.of(), DiscoveryService.readEntries(file, capturing(logs)));

        List<Path> copies = corruptCopies(dir);
        assertEquals(1, copies.size());
        assertEquals(damaged, Files.readString(copies.get(0)));
        assertEquals(1, logs.size());
        assertEquals(Level.SEVERE, logs.get(0).getLevel());
        assertTrue(logs.get(0).getMessage().contains(copies.get(0).getFileName().toString()));
    }

    @Test
    void anEmptyFileLeftByTheOldInPlaceSaveIsTreatedAsDamaged() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        Files.createFile(file.toPath());
        List<LogRecord> logs = new ArrayList<>();

        assertEquals(Map.of(), DiscoveryService.readEntries(file, capturing(logs)));

        assertEquals(1, corruptCopies(dir).size());
        assertEquals(Level.SEVERE, logs.get(0).getLevel());
    }

    @Test
    void anEntryThatIsNotAPlayerIdIsSkippedWithAWarning() throws IOException {
        File file = dir.resolve("discovered.yml").toFile();
        Files.writeString(file.toPath(), "not-a-uuid:\n- farm\n" + SAM + ":\n- mine\n");
        List<LogRecord> logs = new ArrayList<>();

        assertEquals(Map.of(SAM, Set.of("mine")), DiscoveryService.readEntries(file, capturing(logs)));

        assertEquals(1, logs.size());
        assertEquals(Level.WARNING, logs.get(0).getLevel());
        assertTrue(corruptCopies(dir).isEmpty());
    }

    @Test
    void aMissingFileIsQuiet() {
        List<LogRecord> logs = new ArrayList<>();
        assertEquals(Map.of(), DiscoveryService.readEntries(dir.resolve("discovered.yml").toFile(), capturing(logs)));
        assertTrue(logs.isEmpty());
    }

    @Test
    void closeWritesTheFinalStateSynchronously() {
        File file = dir.resolve("discovered.yml").toFile();
        SnapshotWriter<Map<String, List<String>>> writer =
                new SnapshotWriter<>(file, Logger.getAnonymousLogger(), DiscoveryService::serialise);
        writer.close(Map.of(ALEX.toString(), List.of("farm")));
        assertEquals(Map.of(ALEX, Set.of("farm")), DiscoveryService.readEntries(file, Logger.getAnonymousLogger()));
    }

    @Test
    void anUnchangedSnapshotIsNotWrittenAgain() throws Exception {
        File file = dir.resolve("discovered.yml").toFile();
        SnapshotWriter<Map<String, List<String>>> writer =
                new SnapshotWriter<>(file, Logger.getAnonymousLogger(), DiscoveryService::serialise);
        writer.submit(Map.of(ALEX.toString(), List.of("farm", "mine"), SAM.toString(), List.of("farm")));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!file.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(file.exists());
        Files.delete(file.toPath());

        // equal content built in a different order: skipped, so the deleted file stays gone
        writer.close(Map.of(SAM.toString(), List.of("farm"), ALEX.toString(), List.of("farm", "mine")));
        assertFalse(file.exists());
    }
}
