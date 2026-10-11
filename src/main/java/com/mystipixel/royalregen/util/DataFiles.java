package com.mystipixel.royalregen.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Crash-safe reads and writes for small data files (the RoyalMinions {@code util/DataFiles} pattern). Writes
 * go to a synced sibling that is moved into place, and failed reads throw instead of returning empty.
 */
public final class DataFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DataFiles() {
    }

    /** Throws rather than returning an empty config when the file is unreadable. */
    public static YamlConfiguration load(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    /** Writes a synced sibling and moves it over {@code file}, so a crash never leaves half a file. */
    public static void write(File file, String data) throws IOException {
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copies an unreadable file to {@code <name>.corrupt-<time>} beside it, before it gets overwritten. */
    public static File keepCorruptCopy(File file) throws IOException {
        Path copy = file.toPath().resolveSibling(file.getName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
        Files.copy(file.toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
        return copy.toFile();
    }
}
