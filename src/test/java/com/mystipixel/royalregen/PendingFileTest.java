package com.mystipixel.royalregen;

import com.mystipixel.royalregen.util.DataFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingFileTest {

    @TempDir
    Path dir;

    private static List<Path> corruptCopies(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("pending.yml.corrupt-")).toList();
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
    void rowsRoundTripThroughTheWriter() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        List<String> rows = List.of("world|1|64|-3|1700000000000|minecraft:wheat[age=7]",
                "mine|0|10|0|1700000000001|minecraft:stone");
        DataFiles.write(file, RegenService.serialise(rows));
        assertEquals(rows, RegenService.readRows(file, Logger.getAnonymousLogger()));
    }

    @Test
    void aDamagedFileIsKeptAsideAndLoggedClearly() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        String damaged = "pending:\n- \"world|1|64|-3|1700000000000|minecraft:wh";
        Files.writeString(file.toPath(), damaged);
        List<LogRecord> logs = new java.util.ArrayList<>();

        assertEquals(List.of(), RegenService.readRows(file, capturing(logs)));

        List<Path> copies = corruptCopies(dir);
        assertEquals(1, copies.size());
        assertEquals(damaged, Files.readString(copies.get(0)));
        assertEquals(1, logs.size());
        assertEquals(Level.SEVERE, logs.get(0).getLevel());
        assertTrue(logs.get(0).getMessage().contains(copies.get(0).getFileName().toString()));
    }

    @Test
    void anEmptyFileLeftByTheOldInPlaceSaveIsTreatedAsDamaged() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        Files.createFile(file.toPath());
        List<LogRecord> logs = new java.util.ArrayList<>();

        assertEquals(List.of(), RegenService.readRows(file, capturing(logs)));

        assertEquals(1, corruptCopies(dir).size());
        assertEquals(Level.SEVERE, logs.get(0).getLevel());
    }

    @Test
    void aMissingFileIsQuiet() {
        List<LogRecord> logs = new java.util.ArrayList<>();
        assertEquals(List.of(), RegenService.readRows(dir.resolve("pending.yml").toFile(), capturing(logs)));
        assertTrue(logs.isEmpty());
    }
}
