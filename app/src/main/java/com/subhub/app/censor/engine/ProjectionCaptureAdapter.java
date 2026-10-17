package com.subhub.app.censor.engine;

import java.util.Objects;

/** Engine-demand adapter for a continuous source; owns neither projection permission nor a timer. */
public final class ProjectionCaptureAdapter<R, P> implements CensorEngine.Capturer<P> {
    public interface TargetResolver<R> {
        /**
         * Exclusive to one engine/host. Return one stable instance only while its exact engine
         * ActiveScope AND reader generation are current; null otherwise. Equal numeric scope
         * values from another owner do not grant authority. Called again after copying/cleanup.
         * Invalidate the engine epoch BEFORE reader retirement/replacement: this lookup is not an
         * atomic publication fence, whereas the engine rejects old-epoch capture completion.
         * Throw ResourceFailure for a failed current source owner, not an ordinary unavailable scope.
         */
        Target<R> resolve(CensorEngine.CaptureRequest request);
    }

    public interface Copier<R, P> {
        /**
         * New exclusive pixels with display dimensions and pixelAtMillis=-1, or null. Clean all
         * partial outputs before an ordinary throw; use ResourceFailure if their cleanup failed.
         * Do not close or asynchronously borrow raw. The adapter closes it before publication.
         */
        CensorEngine.Captured<P> copy(Target<R> target, ProjectionFrameMailbox<R>.Read raw);
    }

    public static final class ResourceFailure extends RuntimeException {
        public ResourceFailure(Throwable cause) { super(cause); }
    }

    public static final class Target<R> {
        public final CensorEngine.ActiveScope scope;
        public final PixelGeometry geometry;
        public final ProjectionFrameMailbox<R> mailbox;
        /** Physical mapping must already be qualified by the platform; dimensions alone are not proof. */
        public Target(CensorEngine.ActiveScope scope, PixelGeometry geometry, ProjectionFrameMailbox<R> mailbox) {
            this.scope = Objects.requireNonNull(scope); this.geometry = Objects.requireNonNull(geometry);
            this.mailbox = Objects.requireNonNull(mailbox);
        }
        private boolean matches(CensorEngine.CaptureRequest request) {
            return scope.epoch == request.epoch && scope.geometry == request.geometry
                    && scope.protectionScope.equals(request.protectionScope);
        }
    }

    private final TargetResolver<R> targets;
    private final Copier<R, P> copier;

    public ProjectionCaptureAdapter(TargetResolver<R> targets, Copier<R, P> copier) {
        this.targets = Objects.requireNonNull(targets); this.copier = Objects.requireNonNull(copier);
    }

    @Override public void capture(CensorEngine.CaptureRequest request, CensorEngine.CaptureCallback<P> callback) {
        Target<R> target;
        try { target = targets.resolve(request); }
        catch (ResourceFailure failedSource) { callback.resourceFailure(); return; }
        catch (RuntimeException unavailableScope) { callback.failure(0); return; }
        catch (Error uncertainScope) { callback.resourceFailure(); return; }
        if (target == null || !target.matches(request)) { callback.failure(0); return; }
        // This is engine-demand admission, not the independently running ImageReader's pixel clock.
        if (!request.tryDispatch()) return;
        if (failed(target.mailbox)) { callback.resourceFailure(); return; }
        ProjectionFrameMailbox<R>.Read raw = target.mailbox.take();
        if (raw == null) {
            if (failed(target.mailbox)) callback.resourceFailure(); else callback.failure(0);
            return;
        }
        CensorEngine.Captured<P> prepared = null;
        boolean resourceFailure = false, eligible = false;
        try {
            prepared = copier.copy(target, raw);
        } catch (ResourceFailure failure) { resourceFailure = true; }
        catch (RuntimeException failure) { /* Copier cleaned its partials before returning terminal failure. */ }
        catch (Error uncertainCleanup) { resourceFailure = true; }
        finally { raw.close(); }
        resourceFailure |= failed(target.mailbox);
        try {
            eligible = !resourceFailure && !target.mailbox.state().closed && prepared != null && targets.resolve(request) == target
                    && prepared.width == target.geometry.displayWidth
                    && prepared.height == target.geometry.displayHeight && prepared.pixelAtMillis == -1;
        } catch (ResourceFailure failedSource) { resourceFailure = true; }
        catch (RuntimeException staleScope) { /* Release copied pixels before reporting failure. */ }
        catch (Error uncertainScope) { resourceFailure = true; }
        if (!eligible) {
            resourceFailure |= discard(prepared);
            if (resourceFailure) callback.resourceFailure(); else callback.failure(0);
            return;
        }
        try { callback.success(prepared); }
        finally {
            // Captured.close cannot dispose an input claimed by the engine, even if a callback threw.
            if (discard(prepared)) callback.resourceFailure();
        }
    }

    private static boolean failed(ProjectionFrameMailbox<?> mailbox) {
        ProjectionFrameMailbox.State state = mailbox.state();
        return state.resourceFailures != 0 || state.protocolFailures != 0 || state.retirementCallbackFailures != 0;
    }

    private static boolean discard(CensorEngine.Captured<?> prepared) {
        if (prepared == null) return false;
        try { prepared.close(); return false; }
        catch (RuntimeException | Error cleanupFailure) { return true; }
    }
}
