package com.mystipixel.royalregen.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotWriterTest {

    private static final Logger LOG = Logger.getLogger("SnapshotWriterTest");

    @TempDir
    Path dir;

    private final List<String> writes = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();

    private SnapshotWriter<String> recording(File file, CountDownLatch gate) {
        return new SnapshotWriter<>(file, LOG, Function.identity(), (f, data) -> {
            threads.add(Thread.currentThread().getName());
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writes.add(data);
            DataFiles.write(f, data);
        });
    }

    @Test
    void writesOffTheCallingThread() throws Exception {
        File file = dir.resolve("pending.yml").toFile();
        SnapshotWriter<String> writer = recording(file, null);
        writer.submit("a");
        writer.awaitIdle();
        assertEquals(List.of("a"), writes);
        assertNotEquals(Thread.currentThread().getName(), threads.get(0));
        assertEquals("a", Files.readString(file.toPath()));
    }

    @Test
    void skipsASnapshotEqualToTheLastWrite() throws Exception {
        SnapshotWriter<String> writer = recording(dir.resolve("pending.yml").toFile(), null);
        writer.submit("a");
        writer.awaitIdle();
        writer.submit("a");
        writer.awaitIdle();
        writer.close("a");
        assertEquals(List.of("a"), writes);
    }

    @Test
    void onlyTheNewestWaitingSnapshotIsWritten() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        File file = dir.resolve("pending.yml").toFile();
        SnapshotWriter<String> writer = recording(file, gate);
        writer.submit("first");
        while (threads.isEmpty()) {
            Thread.onSpinWait();               // "first" is now being written and held at the gate
        }
        writer.submit("second");
        writer.submit("third");
        writer.submit("fourth");
        gate.countDown();
        writer.awaitIdle();
        assertEquals(List.of("first", "fourth"), writes);
        assertEquals("fourth", Files.readString(file.toPath()));
    }

    @Test
    void closeWritesTheFinalStateOnTheCallingThreadAfterAnyRunningWrite() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        File file = dir.resolve("pending.yml").toFile();
        SnapshotWriter<String> writer = recording(file, gate);
        writer.submit("busy");
        while (threads.isEmpty()) {
            Thread.onSpinWait();
        }
        writer.submit("queued");
        Thread closer = new Thread(() -> writer.close("final"), "closer");
        closer.start();
        Thread.sleep(100);
        assertTrue(closer.isAlive(), "close must wait for the running write");
        gate.countDown();
        closer.join(10_000);
        assertFalse(closer.isAlive());
        assertEquals(List.of("busy", "final"), writes);
        assertEquals("closer", threads.get(threads.size() - 1));
        assertEquals("final", Files.readString(file.toPath()));

        writer.submit("after close");
        assertEquals("final", Files.readString(file.toPath()));
    }

    @Test
    void closeWithNothingQueuedStillWritesSynchronously() throws Exception {
        File file = dir.resolve("pending.yml").toFile();
        SnapshotWriter<String> writer = recording(file, null);
        writer.close("final");
        assertEquals("final", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void aFailedWriteKeepsTheOldFileAndIsRetriedForTheSameSnapshot() throws Exception {
        File file = dir.resolve("pending.yml").toFile();
        Files.writeString(file.toPath(), "old");
        boolean[] fail = {true};
        SnapshotWriter<String> writer = new SnapshotWriter<>(file, LOG, Function.identity(), (f, data) -> {
            if (fail[0]) {
                throw new IOException("disk full");
            }
            DataFiles.write(f, data);
        });
        writer.submit("new");
        writer.awaitIdle();
        assertTrue(writer.lastWriteFailed());
        assertEquals("old", Files.readString(file.toPath()));

        fail[0] = false;
        writer.submit("new");
        writer.awaitIdle();
        assertFalse(writer.lastWriteFailed());
        assertEquals("new", Files.readString(file.toPath()));
    }
}
