package com.subhub.app.detection;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A detector owns this optional input pool and drains every submitted channel before returning. */
final class BoundedRgbInput implements AutoCloseable {
    private static final float INV_255 = 1f / 255f;
    private final ExecutorService executor;
    private boolean closing;
    private boolean closed;

    BoundedRgbInput(int workers, ThreadFactory factory) {
        if (workers < 0 || workers > 2) throw new IllegalArgumentException("Input worker budget");
        Objects.requireNonNull(factory);
        executor = workers == 0 ? null : Executors.newFixedThreadPool(workers,
                action -> Objects.requireNonNull(factory.newThread(action), "Input worker factory returned no thread"));
    }

    synchronized void write(int[] pixels, float[] values, int plane) {
        if (closing || closed) throw new IllegalStateException("Bounded input is closed");
        Objects.requireNonNull(pixels); Objects.requireNonNull(values);
        if (plane < 0 || plane > Integer.MAX_VALUE / 3 || plane > pixels.length || (long) plane * 3 > values.length) {
            throw new IllegalArgumentException("RGB input bounds");
        }
        if (executor == null) {
            for (int channel = 0; channel < 3; channel++) fill(pixels, values, plane, channel);
            return;
        }
        CountDownLatch finished = new CountDownLatch(3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int submitted = 0;
        try {
            for (int channel = 0; channel < 3; channel++) {
                final int selected = channel;
                executor.execute(() -> {
                    try { fill(pixels, values, plane, selected); }
                    catch (RuntimeException | Error error) { failure.compareAndSet(null, error); }
                    finally { finished.countDown(); }
                });
                submitted++;
            }
        } catch (RuntimeException | Error error) {
            failure.compareAndSet(null, error);
            // Never wait for unsubmitted tasks, or release buffers used by submitted tasks early.
            for (; submitted < 3; submitted++) finished.countDown();
        }
        boolean interrupted = false;
        while (true) {
            try { finished.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
    }

    private static void fill(int[] pixels, float[] values, int plane, int channel) {
        int offset = channel * plane, shift = 16 - channel * 8;
        for (int index = 0; index < plane; index++) {
            values[offset + index] = ((pixels[index] >>> shift) & 0xff) * INV_255;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closing = true;
        if (executor == null) { closed = true; return; }
        executor.shutdown();
        boolean interrupted = false, terminated = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (!terminated) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Bounded input workers did not terminate");
                try { terminated = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            closed = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
