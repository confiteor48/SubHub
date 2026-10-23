package com.subhub.app.detection;

/** Explicit per-detector test budget, not provider independence or process-wide CPU isolation. */
final class CpuExecutionBudget {
    final boolean latencyPriority;
    final int maximumNativeThreads;
    final int inputWorkers;
    final boolean allowSpinning = false;

    private CpuExecutionBudget(boolean latencyPriority, int nativeThreads, int inputWorkers) {
        this.latencyPriority = latencyPriority;
        this.maximumNativeThreads = nativeThreads;
        this.inputWorkers = inputWorkers;
    }

    static CpuExecutionBudget fast() { return new CpuExecutionBudget(true, 2, 2); }
    static CpuExecutionBudget quality() { return new CpuExecutionBudget(false, 1, 0); }

    void validate(boolean requestedPriority, int availableProcessors) {
        if (requestedPriority != latencyPriority) throw new IllegalArgumentException("CPU budget lane mismatch");
        if (availableProcessors < 4) throw new IllegalStateException("CPU pair budget requires four available processors");
    }

    void requireProvider(String provider) {
        if (!"CPU".equals(provider)) throw new IllegalArgumentException("Bounded test requires the explicit CPU provider");
    }

    int nativeThreads(int configuredThreads, int availableProcessors) {
        validate(latencyPriority, availableProcessors);
        if (configuredThreads <= 0) throw new IllegalArgumentException("Positive configured thread count required");
        return Math.min(configuredThreads, maximumNativeThreads);
    }

    void requireSameSession(DetectorConfig current, DetectorConfig next) {
        if (current.getInferenceThreads() != next.getInferenceThreads()
                || current.getInferenceResolution() != next.getInferenceResolution()
                || !current.getModelFilename().equals(next.getModelFilename())) {
            throw new IllegalArgumentException("Bounded context requires a new detector for a session-key change");
        }
    }

    /** Attempt every owned retirement, retaining the first error and all distinct later errors. */
    static void closeAll(Runnable... steps) {
        Throwable failure = null;
        for (Runnable step : steps) {
            try { step.run(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
    }
}
