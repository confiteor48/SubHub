package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Resource preparation only; never owns capture, scene geometry or retention clocks. There is one
 * running task and one latest pending descriptor. A descriptor owns no pixels until prepare runs.
 * At most two outputs coexist (current plus preparing/completed/retired). A held old read blocks
 * further preparation instead of accumulating generations. Disposers run synchronously outside the
 * lock, so callers must choose disposers appropriate to their thread. isDrained proves actual task
 * termination and managed resource cleanup, NOT Android native/GPU allocation disposal.
 */
public final class LatestResourceCoordinator<K, R extends AutoCloseable> implements AutoCloseable {
    public interface Preparation<R> {
        /** New exclusive output, or null for unavailable optional resources; clean partials on throw. */
        R prepare(BooleanSupplier obsolete) throws Exception;
    }
    public interface Observer {
        default void changed() { }
        default void drained() { }
    }
    public interface Delivery {
        /** False proves no action was queued; a throw is uncertain and its action may still arrive. */
        boolean post(Runnable action);
    }

    private final class Descriptor {
        final K key;
        final Preparation<R> preparation;
        final long serial;
        Descriptor(K key, Preparation<R> preparation, long serial) {
            this.key = key; this.preparation = preparation; this.serial = serial;
        }
    }
    private final class Node {
        final K key;
        final R resource;
        int references = 1; // coordinator ownership plus actual draw-read leases
        boolean owner = true, disposing;
        Node(K key, R resource) { this.key = key; this.resource = resource; }
    }
    private final class Task {
        final Descriptor descriptor;
        final AtomicBoolean obsolete = new AtomicBoolean(), started = new AtomicBoolean(), terminal = new AtomicBoolean();
        boolean completed, deliveryRejected;
        Node output;
        Task(Descriptor descriptor) { this.descriptor = descriptor; }
    }

    /** Never close resource directly. Releasing the lease retires it only after its last reader. */
    public final class Read implements AutoCloseable {
        public final K key;
        private final Node node;
        private final AtomicBoolean released = new AtomicBoolean();
        private Read(Node node) { this.node = node; key = node.key; }
        public R resource() {
            if (released.get()) throw new IllegalStateException("closed resource read");
            return node.resource;
        }
        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            List<Runnable> actions = new ArrayList<>();
            synchronized (LatestResourceCoordinator.this) {
                node.references--;
                if (!node.owner && node.references == 0) dispose(node, actions);
                launch(actions); reportDrain(actions);
            }
            run(actions);
        }
    }

    private final Executor worker;
    private final Delivery delivery;
    private final Observer observer;
    private final Map<R, Node> outputs = new IdentityHashMap<>();
    private Descriptor pending;
    private Task running;
    private Node current;
    private long serial, replacements, preparationFailures, deliveryRejections, deliveryFailures, resourceFailures, callbackFailures;
    private int disposing;
    private boolean closed, drainReported, changeQueued;

    public LatestResourceCoordinator(Executor worker, Delivery delivery, Observer observer) {
        this.worker = Objects.requireNonNull(worker); this.delivery = Objects.requireNonNull(delivery);
        this.observer = Objects.requireNonNull(observer);
    }

    /** Keep current while preparing. Epoch/appearance changes must additionally invalidate it. */
    public void request(K key, Preparation<R> preparation) {
        Objects.requireNonNull(key); Objects.requireNonNull(preparation);
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (closed) throw new IllegalStateException("closed resource coordinator");
            if (serial == Long.MAX_VALUE) throw new IllegalStateException("resource generation exhausted");
            if (pending != null) replacements++;
            pending = new Descriptor(key, preparation, ++serial);
            if (running != null) running.obsolete.set(true);
            launch(actions);
        }
        run(actions);
    }

    /** Scope/detach fence. Outstanding work/read leases keep their actual terminal ownership. */
    public void invalidate() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            pending = null;
            if (running != null) running.obsolete.set(true);
            retireCurrent(actions);
        }
        run(actions);
    }

    /** Also recovers a completed output when the delivery executor previously rejected its post. */
    public void poll() {
        Task task;
        synchronized (this) {
            // An accepted post retains the slot until its actual delivery. Stealing it here could
            // otherwise accumulate stale delivery closures while callers repeatedly poll.
            task = running != null && running.completed && running.deliveryRejected ? running : null;
        }
        if (task != null) deliver(task);
    }

    public Read acquire() {
        poll();
        synchronized (this) {
            if (closed || current == null) return null;
            current.references++; return new Read(current);
        }
    }

    @Override public void close() {
        List<Runnable> actions = new ArrayList<>();
        Task completed;
        synchronized (this) {
            if (closed) return;
            closed = true; pending = null;
            if (running != null) running.obsolete.set(true);
            completed = running != null && running.completed ? running : null;
            retireCurrent(actions); reportDrain(actions);
        }
        run(actions);
        if (completed != null) deliver(completed);
    }

    public synchronized boolean isDrained() { return drained(); }
    public synchronized int outputCount() { return outputs.size(); }
    public synchronized int pendingCount() { return pending == null ? 0 : 1; }
    public synchronized int runningCount() { return running == null ? 0 : 1; }
    public synchronized long replacedCount() { return replacements; }
    public synchronized long preparationFailures() { return preparationFailures; }
    public synchronized long deliveryRejections() { return deliveryRejections; }
    public synchronized long deliveryFailures() { return deliveryFailures; }
    public synchronized long resourceFailures() { return resourceFailures; }
    public synchronized long callbackFailures() { return callbackFailures; }

    private void launch(List<Runnable> actions) {
        if (closed || resourceFailures != 0 || running != null || pending == null || disposing != 0) return;
        // Any retired read owns the second output slot. Never prepare another generation behind it.
        for (Node node : outputs.values()) if (node != current) return;
        Task task = new Task(pending); pending = null; running = task;
        actions.add(() -> {
            try { worker.execute(() -> prepare(task)); }
            catch (RuntimeException failure) {
                // If execution actually began, that worker still owns terminal cleanup. If it was
                // merely queued before rejection, claim started so the late runnable is a no-op.
                if (task.started.compareAndSet(false, true)) complete(task, null, true);
            }
        });
    }

    private void prepare(Task task) {
        if (!task.started.compareAndSet(false, true)) return;
        R resource = null; boolean failed = false;
        try { if (!task.obsolete.get()) resource = task.descriptor.preparation.prepare(task.obsolete::get); }
        catch (Exception | Error failure) { failed = true; }
        complete(task, resource, failed);
    }

    private void complete(Task task, R resource, boolean failed) {
        if (!task.terminal.compareAndSet(false, true)) return;
        List<Runnable> actions = new ArrayList<>();
        boolean discard;
        synchronized (this) {
            if (failed) preparationFailures++;
            if (resource != null) {
                if (outputs.containsKey(resource)) {
                    // Reusing an owned output breaks exclusive ownership. Do not close the live alias.
                    resourceFailures++;
                } else {
                    task.output = new Node(task.descriptor.key, resource);
                    outputs.put(resource, task.output);
                }
            }
            task.completed = true;
            discard = closed || task.obsolete.get() || task.descriptor.serial != serial;
            if (!discard) actions.add(() -> {
                try {
                    if (!delivery.post(() -> deliver(task))) deliveryRejected(task);
                } catch (RuntimeException uncertain) {
                    boolean closing;
                    synchronized (LatestResourceCoordinator.this) { deliveryFailures++; closing = closed; }
                    // Keep the slot until the possibly accepted action actually arrives. poll must
                    // not admit another task behind an uncertain post and grow an unbounded queue.
                    if (closing) deliver(task);
                }
            });
        }
        run(actions);
        if (discard) deliver(task);
    }

    private void deliveryRejected(Task task) {
        boolean closing;
        synchronized (this) { deliveryRejections++; task.deliveryRejected = true; closing = closed; }
        if (closing) deliver(task); // Disposal only, never worker-thread installation.
    }

    private void deliver(Task task) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (running != task || !task.completed) return;
            boolean install = !closed && !task.obsolete.get() && task.descriptor.serial == serial
                    && resourceFailures == 0 && task.output != null;
            if (install) {
                retireCurrent(actions); current = task.output; task.output = null;
                notifyChanged(actions);
            } else if (task.output != null) {
                releaseOwner(task.output, actions); task.output = null;
            }
            running = null; launch(actions); reportDrain(actions);
        }
        run(actions);
    }

    private void retireCurrent(List<Runnable> actions) {
        if (current != null) { Node old = current; current = null; releaseOwner(old, actions); }
    }
    private void releaseOwner(Node node, List<Runnable> actions) {
        if (!node.owner) throw new IllegalStateException("resource owner released twice");
        node.owner = false; node.references--;
        if (node.references == 0) dispose(node, actions);
    }
    private void dispose(Node node, List<Runnable> actions) {
        if (node.disposing) return;
        node.disposing = true; disposing++;
        actions.add(() -> {
            boolean failed = false;
            try { node.resource.close(); } catch (Exception | Error failure) { failed = true; }
            List<Runnable> following = new ArrayList<>();
            synchronized (LatestResourceCoordinator.this) {
                disposing--;
                if (failed) resourceFailures++; else outputs.remove(node.resource);
                launch(following); reportDrain(following);
            }
            run(following);
        });
    }
    private boolean drained() { return closed && running == null && outputs.isEmpty() && disposing == 0 && resourceFailures == 0; }
    private void reportDrain(List<Runnable> actions) {
        if (drained() && !drainReported) { drainReported = true; actions.add(() -> notifyObserver(observer::drained)); }
    }
    private void notifyChanged(List<Runnable> actions) {
        if (changeQueued) return;
        changeQueued = true;
        actions.add(() -> {
            try {
                if (!delivery.post(() -> {
                    boolean notify;
                    synchronized (LatestResourceCoordinator.this) { changeQueued = false; notify = !closed && current != null; }
                    if (notify) invokeObserver(observer::changed);
                })) synchronized (LatestResourceCoordinator.this) { changeQueued = false; deliveryRejections++; }
            } catch (RuntimeException uncertain) {
                // The possibly accepted notice keeps its queue reservation until actual delivery.
                synchronized (LatestResourceCoordinator.this) { deliveryFailures++; }
            }
        });
    }
    private void notifyObserver(Runnable callback) {
        try { if (!delivery.post(() -> invokeObserver(callback))) synchronized (this) { deliveryRejections++; } }
        catch (RuntimeException uncertain) { synchronized (this) { deliveryFailures++; } }
    }
    private void invokeObserver(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException | Error failure) { synchronized (this) { callbackFailures++; } }
    }
    private static void run(List<Runnable> actions) { for (Runnable action : actions) action.run(); }
}
