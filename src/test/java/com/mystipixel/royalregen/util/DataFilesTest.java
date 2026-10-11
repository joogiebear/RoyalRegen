package com.mystipixel.royalregen.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFilesTest {

    @TempDir
    Path dir;

    @Test
    void writeReplacesTheFileAndLeavesNoTempBehind() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        Files.writeString(file.toPath(), "pending:\n- old\n");
        DataFiles.write(file, "pending:\n- new\n");
        assertEquals("pending:\n- new\n", Files.readString(file.toPath()));
        assertFalse(Files.exists(dir.resolve("pending.yml.tmp")));
    }

    @Test
    void aWriteThatFailsBeforeTheMoveLeavesTheOldFileWhole() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        Files.writeString(file.toPath(), "pending:\n- old\n");
        // a directory where the temp file goes makes the write fail part way, as a full disk would
        Files.createDirectory(dir.resolve("pending.yml.tmp"));
        assertThrows(IOException.class, () -> DataFiles.write(file, "pending:\n- new\n"));
        assertEquals("pending:\n- old\n", Files.readString(file.toPath()));
    }

    @Test
    void loadThrowsOnABrokenFileInsteadOfReturningEmpty() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        Files.writeString(file.toPath(), "pending:\n- \"world|1|2|3|4|minecraft:whe");
        assertThrows(InvalidConfigurationException.class, () -> DataFiles.load(file));
    }

    @Test
    void keepCorruptCopyLeavesTheOriginalAndAnIdenticalCopy() throws IOException {
        File file = dir.resolve("pending.yml").toFile();
        Files.writeString(file.toPath(), "broken");
        File copy = DataFiles.keepCorruptCopy(file);
        assertTrue(copy.getName().startsWith("pending.yml.corrupt-"));
        assertEquals("broken", Files.readString(copy.toPath()));
        assertEquals("broken", Files.readString(file.toPath()));
    }
}
