package com.subhub.app.popup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** One query-time metadata supplier; an old owner cannot clear or return a successor's hints. */
public final class ScopedDetectionHints<T> {
    public final class Binding implements AutoCloseable {
        private final Supplier<List<T>> source;
        private Binding(Supplier<List<T>> source) { this.source = source; }
        @Override public void close() {
            synchronized (ScopedDetectionHints.this) { if (current == this) current = null; }
        }
    }
    private Binding current;

    public synchronized Binding bind(Supplier<List<T>> source) {
        return current = new Binding(Objects.requireNonNull(source));
    }

    /** No callback under the lock, no stored region copy and no effect participation mutation. */
    public List<T> read(List<T> legacy) {
        Binding selected;
        synchronized (this) { selected = current; }
        if (selected == null) return legacy;
        List<T> result;
        try { result = new ArrayList<>(Objects.requireNonNull(selected.source.get())); }
        catch (RuntimeException unavailable) { return Collections.emptyList(); }
        synchronized (this) {
            return current == selected ? Collections.unmodifiableList(result) : Collections.emptyList();
        }
    }
}
