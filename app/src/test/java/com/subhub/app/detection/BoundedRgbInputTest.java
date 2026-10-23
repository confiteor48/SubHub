package com.subhub.app.detection;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class BoundedRgbInputTest {
    @Test public void inlineAndTwoWorkerInputsMatchOriginalChannelFormulaBitForBit() throws Exception {
        int[] pixels = new int[4097];
        Random random = new Random(829L);
        for (int index = 0; index < pixels.length; index++) pixels[index] = random.nextInt();
        float[] expected = original(pixels);
        for (int workers : new int[]{0, 2}) {
            List<Thread> created = Collections.synchronizedList(new ArrayList<>());
            BoundedRgbInput writer = new BoundedRgbInput(workers, task -> {
                Thread thread = new Thread(task, "bounded-rgb-test"); created.add(thread); return thread;
            });
            try {
                float[] actual = new float[expected.length];
                writer.write(pixels, actual, pixels.length);
                assertBits(expected, actual);
                pixels[0] ^= 0xabcdef;
                writer.write(pixels, actual, pixels.length);
                assertBits(original(pixels), actual);
                pixels[0] ^= 0xabcdef;
                assertEquals(workers, created.size());
            } finally { writer.close(); }
            for (Thread thread : created) { thread.join(2000); assertFalse(thread.isAlive()); }
        }
    }

    @Test public void inlineQualityCreatesNoInputThreadsAndPreservesInterruption() {
        AtomicInteger creations = new AtomicInteger();
        try (BoundedRgbInput writer = new BoundedRgbInput(0, task -> {
            creations.incrementAndGet(); return new Thread(task);
        })) {
            Thread.currentThread().interrupt();
            try {
                writer.write(new int[]{0xffabcdef}, new float[3], 1);
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, creations.get());
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void pooledInputRestoresInterruptionOnlyAfterAllWritesFinish() {
        try (BoundedRgbInput writer = new BoundedRgbInput(2, Thread::new)) {
            int[] pixels = new int[]{0xff123456, 0xffabcdef};
            float[] actual = new float[6];
            Thread.currentThread().interrupt();
            try {
                writer.write(pixels, actual, pixels.length);
                assertTrue(Thread.currentThread().isInterrupted());
                assertBits(original(pixels), actual);
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void partialSubmissionFailureDoesNotReturnWhileAnAcceptedChannelOwnsBuffers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), rejected = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        BoundedRgbInput writer = new BoundedRgbInput(2, task -> {
            if (creations.incrementAndGet() == 2) {
                await(entered); rejected.countDown(); throw new RejectedExecutionException("Controlled second-worker refusal");
            }
            return new Thread(() -> { entered.countDown(); await(release); task.run(); }, "bounded-rgb-held");
        });
        try {
            Future<?> result = caller.submit(() -> writer.write(new int[]{0xff123456}, new float[3], 1));
            assertTrue(rejected.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
        } finally {
            release.countDown(); writer.close(); caller.shutdown(); assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void invalidBoundsBudgetsAndMissingThreadsFailRatherThanHanging() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedRgbInput(-1, Thread::new));
        assertThrows(IllegalArgumentException.class, () -> new BoundedRgbInput(3, Thread::new));
        try (BoundedRgbInput writer = new BoundedRgbInput(0, Thread::new)) {
            assertThrows(IllegalArgumentException.class, () -> writer.write(new int[1], new float[3], -1));
            assertThrows(IllegalArgumentException.class, () -> writer.write(new int[1], new float[3], Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> writer.write(new int[1], new float[3], 2));
            assertThrows(IllegalArgumentException.class, () -> writer.write(new int[1], new float[2], 1));
        }
        try (BoundedRgbInput writer = new BoundedRgbInput(2, task -> null)) {
            assertThrows(NullPointerException.class, () -> writer.write(new int[1], new float[3], 1));
        }
    }

    @Test public void closeIsIdempotentButClosedWriterCannotRenewItsBuffers() {
        BoundedRgbInput writer = new BoundedRgbInput(2, Thread::new);
        writer.write(new int[1], new float[3], 1);
        writer.close(); writer.close();
        assertThrows(IllegalStateException.class, () -> writer.write(new int[1], new float[3], 1));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Controlled input barrier expired"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static float[] original(int[] pixels) {
        float[] values = new float[pixels.length * 3];
        for (int index = 0; index < pixels.length; index++) {
            values[index] = ((pixels[index] >>> 16) & 0xff) * (1f / 255f);
            values[pixels.length + index] = ((pixels[index] >>> 8) & 0xff) * (1f / 255f);
            values[pixels.length * 2 + index] = (pixels[index] & 0xff) * (1f / 255f);
        }
        return values;
    }

    private static void assertBits(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals("float bits at " + index, Float.floatToRawIntBits(expected[index]), Float.floatToRawIntBits(actual[index]));
        }
    }
}
