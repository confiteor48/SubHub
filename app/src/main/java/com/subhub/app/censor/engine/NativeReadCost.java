package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Actual reader-terminal diagnostics only. Not a Source, admission, pose or evidence lease. */
public final class NativeReadCost {
    public static final class Start {
        public final long request, epoch, geometry, hardGeneration, invokedAtMillis;
        public final NativeTextResult.ReadMode readMode;
        public Start(long request, long epoch, long geometry, long hardGeneration, long invokedAtMillis) {
            this(request, epoch, geometry, hardGeneration, invokedAtMillis, NativeTextResult.ReadMode.FULL_DISCOVERY);
        }
        public Start(long request, long epoch, long geometry, long hardGeneration, long invokedAtMillis, NativeTextResult.ReadMode readMode) {
            if (request <= 0 || epoch < 0 || geometry < 0 || hardGeneration < 0 || invokedAtMillis < 0)
                throw new IllegalArgumentException("native cost start");
            this.request = request; this.epoch = epoch; this.geometry = geometry; this.hardGeneration = hardGeneration;
            this.invokedAtMillis = invokedAtMillis;
            this.readMode = Objects.requireNonNull(readMode);
        }
    }
    public enum Terminal { SUCCESS, FAILURE, CANCELLED, RESOURCE_FAILURE }
    public enum Stage { PROVIDER_OPEN, PROVIDER_CURRENT, TRAVERSAL_CURRENT, NODE_REFRESH, CHILD_FETCH, CLASSIFY, GLYPH_QUERY, GEOMETRY_EXCLUSIVE, DISPOSE }
    public static final class Cost {
        public final Stage stage;
        public final long calls, nanos, maximumNanos, failedCalls;
        public Cost(Stage stage, long calls, long nanos, long maximumNanos, long failedCalls) {
            this.stage = Objects.requireNonNull(stage);
            if (calls < 0 || calls > 65536 || nanos < 0 || maximumNanos < 0 || maximumNanos > nanos
                    || failedCalls < 0 || failedCalls > calls || (calls == 0 && (nanos != 0 || maximumNanos != 0))
                    || (calls > 0 && maximumNanos < nanos / calls + (nanos % calls == 0 ? 0 : 1)))
                throw new IllegalArgumentException("native cost");
            this.calls = calls; this.nanos = nanos; this.maximumNanos = maximumNanos; this.failedCalls = failedCalls;
        }
    }
    public final long request, epoch, geometry, hardGeneration, scanGeneration;
    /** Uptime clocks; negative read clocks mean beginRead was refused, not a zero-cost read. */
    public final long invokedAtMillis, readStartedAtMillis, readFinishedAtMillis, terminalAtMillis;
    /** Independent monotonic elapsed duration, including scheduled wait, not an absolute source clock. */
    public final long elapsedNanos, unattributedNanos;
    public final Terminal terminal;
    public final NativeTextResult.ReadMode readMode;
    public final List<Cost> costs;

    public NativeReadCost(long request, long epoch, long geometry, long hardGeneration, long scanGeneration,
            long invokedAt, long started, long finished, long terminalAt, long elapsedNanos, Terminal terminal, List<Cost> costs) {
        this(request, epoch, geometry, hardGeneration, scanGeneration, invokedAt, started, finished, terminalAt,
                elapsedNanos, terminal, costs, NativeTextResult.ReadMode.FULL_DISCOVERY);
    }
    public NativeReadCost(long request, long epoch, long geometry, long hardGeneration, long scanGeneration,
            long invokedAt, long started, long finished, long terminalAt, long elapsedNanos, Terminal terminal, List<Cost> costs,
            NativeTextResult.ReadMode readMode) {
        if (request <= 0 || epoch < 0 || geometry < 0 || hardGeneration < 0 || scanGeneration < -1
                || invokedAt < 0 || terminalAt < invokedAt || elapsedNanos < 0 || started < -1 || finished < -1
                || (started == -1 ? finished != -1 || scanGeneration != -1
                    : started < invokedAt || finished < started || terminalAt < finished || scanGeneration < 0)
                || (terminal == Terminal.SUCCESS && started < 0)) throw new IllegalArgumentException("native cost provenance");
        this.terminal = Objects.requireNonNull(terminal);
        this.readMode = Objects.requireNonNull(readMode);
        if (costs.size() != Stage.values().length) throw new IllegalArgumentException("native cost inventory");
        long measured = 0; List<Cost> copy = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            Cost cost = Objects.requireNonNull(costs.get(stage.ordinal()));
            if (cost.stage != stage || cost.nanos > elapsedNanos - measured) throw new IllegalArgumentException("overlapping native costs");
            measured += cost.nanos; copy.add(cost);
        }
        this.request = request; this.epoch = epoch; this.geometry = geometry; this.hardGeneration = hardGeneration;
        this.scanGeneration = scanGeneration; readStartedAtMillis = started; readFinishedAtMillis = finished;
        invokedAtMillis = invokedAt;
        terminalAtMillis = terminalAt; this.elapsedNanos = elapsedNanos; unattributedNanos = elapsedNanos - measured;
        this.costs = Collections.unmodifiableList(copy);
    }
}
