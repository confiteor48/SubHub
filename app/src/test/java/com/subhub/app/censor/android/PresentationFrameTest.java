package com.subhub.app.censor.android;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class PresentationFrameTest {
    @Test public void preferredFutureTimeIsOnlyAnAdvisoryPoseEstimate() {
        PresentationFrame frame = new PresentationFrame(100, 110, 116, 42);
        assertEquals(116, frame.sampleAt(105, 42));
        assertEquals(116, frame.sampleAt(110, 42));
    }

    @Test public void missedDeadlineDoesNotInventAnotherFrameOrBackdateTheWorker() {
        PresentationFrame frame = new PresentationFrame(100, 110, 116, 42);
        assertEquals(111, frame.sampleAt(111, 42));
        assertEquals(116, frame.sampleAt(116, 42));
        assertEquals(130, frame.sampleAt(130, 42));
    }

    @Test public void newerMovementCannotBorrowAnEarlierCallbacksExpectation() {
        assertEquals(105, new PresentationFrame(100, 110, 116, 42).sampleAt(105, 43));
    }

    @Test public void negativeAndExtremeRevisionTokensAreOrdinaryEqualityValues() {
        for (long revision : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            PresentationFrame frame = new PresentationFrame(100, 110, 116, revision);
            assertEquals(116, frame.sampleAt(105, revision));
            assertEquals(105, frame.sampleAt(105, revision ^ 1));
        }
    }

    @Test public void invalidAndFutureCallbackClocksUseActualTime() {
        assertEquals(105, new PresentationFrame(-1, 110, 116, 42).sampleAt(105, 42));
        assertEquals(105, new PresentationFrame(106, 110, 116, 42).sampleAt(105, 42));
        assertEquals(105, new PresentationFrame(100, 120, 116, 42).sampleAt(105, 42));
        assertEquals(105, new PresentationFrame(100, -1, 116, 42).sampleAt(105, 42));
        assertEquals(105, new PresentationFrame(100, 110, -1, 42).sampleAt(105, 42));
    }
}
