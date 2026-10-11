package com.mystipixel.royalregen.util;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes snapshots of some state to one file on a single background thread. Only the newest snapshot
 * waiting is written, a snapshot equal to the last one written is skipped, and {@link #close} writes the
 * final state synchronously. Snapshots must be immutable copies taken on the caller's thread.
 */
public final class SnapshotWriter<T> {

    @FunctionalInterface
    interface WriteOp {
        void write(File file, String data) throws IOException;
    }

    private final File file;
    private final Logger logger;
    private final Function<T, String> serialiser;
    private final WriteOp op;
    private final ExecutorService executor;
    private final AtomicReference<T> latest = new AtomicReference<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Object writeLock = new Object();

    // guarded by writeLock
    private T lastWritten;
    private volatile boolean lastWriteFailed;
    private volatile boolean closed;

    public SnapshotWriter(File file, Logger logger, Function<T, String> serialiser) {
        this(file, logger, serialiser, DataFiles::write);
    }

    SnapshotWriter(File file, Logger logger, Function<T, String> serialiser, WriteOp op) {
        this.file = file;
        this.logger = logger;
        this.serialiser = serialiser;
        this.op = op;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RoyalRegen " + file.getName() + " writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queue a write. Returns at once; ignored after {@link #close}. */
    public void submit(T snapshot) {
        Objects.requireNonNull(snapshot);
        if (closed) {
            return;
        }
        latest.set(snapshot);
        if (queued.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    /** True when the most recent write failed, so the caller should submit again even if nothing changed. */
    public boolean lastWriteFailed() {
        return lastWriteFailed;
    }

    /**
     * Stop the background thread and write {@code finalSnapshot} on the calling thread. Any write already
     * running finishes first, so the final state always lands last.
     */
    public void close(T finalSnapshot) {
        closed = true;
        latest.set(null);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("The " + file.getName() + " writer is still busy after 10 seconds; writing the final state anyway.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        writeNow(finalSnapshot);
    }

    private void drain() {
        // reset before taking, so a submit from here on queues another run instead of being lost
        queued.set(false);
        T next = latest.getAndSet(null);
        if (next != null) {
            writeNow(next);
        }
    }

    private void writeNow(T snapshot) {
        synchronized (writeLock) {
            if (snapshot.equals(lastWritten) && !lastWriteFailed) {
                return;
            }
            try {
                op.write(file, serialiser.apply(snapshot));
                lastWritten = snapshot;
                lastWriteFailed = false;
            } catch (IOException e) {
                lastWriteFailed = true;
                logger.log(Level.WARNING, "Could not write " + file.getName()
                        + "; the previous copy on disk is kept and the write is retried", e);
            }
        }
    }

    // for tests: returns once every write queued so far has finished
    void awaitIdle() throws Exception {
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }
}
