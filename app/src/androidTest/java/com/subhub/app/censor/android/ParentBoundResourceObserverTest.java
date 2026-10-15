package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Host-runnable contracts for the same helper used by the actual service fixture. */
public final class ParentBoundResourceObserverTest {
    private final ParentBoundResourceObserver<Object> observer = new ParentBoundResourceObserver<>();
    private final Object owner = new Object();

    private ParentBoundResourceObserver.Ticket<Object> begin() {
        observer.enable(100); return start(2, 1, 101, owner);
    }

    private ParentBoundResourceObserver.Ticket<Object> start(long epoch, long request, long at, Object value) {
        ParentBoundResourceObserver.Ticket<Object> ticket = observer.reserve(epoch, request, at);
        if (ticket != null) observer.bind(ticket, value);
        return ticket;
    }

    @Test public void cancelledTerminalAfterCutoffKeepsExactCapturedOwner() {
        ParentBoundResourceObserver.Ticket<Object> start = begin();
        observer.cutoff(110);
        assertNull(start(2, 2, 111, new Object()));
        assertSame(start, observer.terminal(2, 1)); assertSame(owner, start.owner());
        observer.finish(start, 120);
        ParentBoundResourceObserver.State state = observer.state();
        assertTrue(state.admissionsClosed); assertEquals(110, state.cutoffAtMillis);
        assertEquals(1, state.sampledParents); assertEquals(1, state.terminalParents); assertEquals(0, state.pendingParents);
        assertThrows(IllegalStateException.class, start::owner);
    }

    @Test public void retiredOrReplacedHostCannotSupplyTerminalOwner() {
        AtomicReference<Object> host = new AtomicReference<>(owner);
        observer.enable(100);
        ParentBoundResourceObserver.Ticket<Object> start = start(2, 1, 101, host.get());
        host.set(null); observer.cutoff(110);
        host.set(new Object());
        assertSame(owner, observer.terminal(2, 1).owner());
        observer.finish(start, 120);
    }

    @Test public void interiorMissingTerminalRemainsPendingNotZero() {
        begin(); observer.cutoff(110);
        ParentBoundResourceObserver.State state = observer.state();
        assertEquals(1, state.pendingParents); assertEquals(0, state.terminalParents);
    }

    @Test public void wrongParentCannotConsumePendingOwner() {
        ParentBoundResourceObserver.Ticket<Object> start = begin();
        assertThrows(IllegalStateException.class, () -> observer.terminal(2, 2));
        assertThrows(IllegalStateException.class, () -> observer.terminal(3, 1));
        assertSame(start, observer.terminal(2, 1)); assertSame(owner, start.owner());
    }

    @Test public void duplicateStartAndTerminalAreRejected() {
        ParentBoundResourceObserver.Ticket<Object> start = begin();
        assertThrows(IllegalStateException.class, () -> start(2, 1, 102, owner));
        observer.terminal(2, 1); observer.finish(start, 103);
        assertThrows(IllegalStateException.class, () -> start(2, 1, 104, owner));
        assertThrows(IllegalStateException.class, () -> observer.terminal(2, 1));
    }

    @Test public void overlappingParentCannotReplaceOwner() {
        begin(); assertThrows(IllegalStateException.class, () -> start(2, 2, 102, new Object()));
        assertSame(owner, observer.terminal(2, 1).owner());
    }

    @Test public void wrongThreadCannotInspectOrFinishOwner() throws Exception {
        ParentBoundResourceObserver.Ticket<Object> start = begin();
        AtomicReference<Throwable> read = new AtomicReference<>(), terminal = new AtomicReference<>(), finish = new AtomicReference<>();
        Thread wrong = new Thread(() -> {
            try { start.owner(); } catch (Throwable e) { read.set(e); }
            try { observer.terminal(2, 1); } catch (Throwable e) { terminal.set(e); }
            try { observer.finish(start, 120); } catch (Throwable e) { finish.set(e); }
        });
        wrong.start(); wrong.join(1000); assertFalse(wrong.isAlive());
        assertTrue(read.get() instanceof IllegalStateException); assertTrue(terminal.get() instanceof IllegalStateException);
        assertTrue(finish.get() instanceof IllegalStateException); assertEquals(1, observer.state().pendingParents);
        observer.terminal(2, 1); observer.finish(start, 120);
    }

    @Test public void samplingExceptionStillDropsOnlyPassiveReferenceInFinally() {
        ParentBoundResourceObserver.Ticket<Object> start = begin(); observer.cutoff(110);
        try { observer.terminal(2, 1).owner(); throw new IllegalArgumentException("sample failed"); }
        catch (IllegalArgumentException expected) { assertEquals("sample failed", expected.getMessage()); }
        finally { observer.finish(start, 120); }
        assertThrows(IllegalStateException.class, start::owner); assertEquals(0, observer.state().pendingParents);
    }

    @Test public void parentHistoryIsFiniteAndDoesNotOwnResources() {
        observer.enable(100);
        for (int i = 1; i <= ParentBoundResourceObserver.MAXIMUM_PARENTS; i++) {
            ParentBoundResourceObserver.Ticket<Object> ticket = start(2, i, 100 + i, owner);
            observer.terminal(2, i); observer.finish(ticket, 100 + i);
        }
        assertThrows(IllegalStateException.class, () -> start(2, 513, 700, owner));
        assertEquals(0, observer.state().pendingParents);
    }

    @Test public void unadmittedTerminalAndNeverEnabledFixtureDoNotAcquireOwners() {
        assertNull(observer.terminal(2, 1)); observer.cutoff(110);
        assertNull(start(2, 1, 120, owner)); assertEquals(0, observer.state().sampledParents);
    }

    @Test public void epochIsPartOfParentIdentityAndCutoffCannotReopen() {
        ParentBoundResourceObserver.Ticket<Object> start = begin(); observer.terminal(2, 1); observer.finish(start, 102);
        ParentBoundResourceObserver.Ticket<Object> next = start(3, 1, 103, owner);
        assertNotEquals(start.identity, next.identity); observer.terminal(3, 1); observer.finish(next, 104);
        observer.cutoff(110); observer.cutoff(120); assertEquals(110, observer.state().cutoffAtMillis);
        assertThrows(IllegalStateException.class, () -> observer.enable(130));
    }

    @Test public void cutoffDuringCaptureCannotDiscardAnAcceptedReservation() {
        observer.enable(100);
        ParentBoundResourceObserver.Ticket<Object> ticket = observer.reserve(2, 1, 101);
        observer.cutoff(102); observer.bind(ticket, owner);
        assertSame(owner, observer.terminal(2, 1).owner()); observer.finish(ticket, 120);
        assertEquals(1, observer.state().terminalParents);
    }

    @Test public void captureFailureStaysPendingUntilActualTerminalWithoutHostFallback() {
        observer.enable(100);
        ParentBoundResourceObserver.Ticket<Object> ticket = observer.reserve(2, 1, 101);
        assertThrows(NullPointerException.class, () -> observer.bind(ticket, null));
        observer.cutoff(110); assertSame(ticket, observer.terminal(2, 1));
        assertThrows(IllegalStateException.class, ticket::owner); assertEquals(1, observer.state().pendingParents);
        observer.finish(ticket, 120); assertEquals(0, observer.state().pendingParents);
    }

    @Test public void boundOwnerCannotBeReplacedBeforeTerminal() {
        ParentBoundResourceObserver.Ticket<Object> ticket = begin();
        assertThrows(IllegalStateException.class, () -> observer.bind(ticket, new Object()));
        assertSame(owner, ticket.owner());
    }

    @Test public void prematureFinishCannotDropOwnerBeforeActualTerminal() {
        ParentBoundResourceObserver.Ticket<Object> ticket = begin();
        assertThrows(IllegalStateException.class, () -> observer.finish(ticket, 102));
        assertEquals(1, observer.state().pendingParents); assertSame(owner, ticket.owner());
        observer.terminal(2, 1); observer.finish(ticket, 120);
    }

    @Test public void duplicateTerminalBeforeSampleFinallyCannotBeSelectedAgain() {
        ParentBoundResourceObserver.Ticket<Object> ticket = begin(); observer.terminal(2, 1);
        assertThrows(IllegalStateException.class, () -> observer.terminal(2, 1));
        observer.finish(ticket, 120); assertEquals(1, observer.state().terminalParents);
    }
}
