package com.subhub.app.censor.engine;

import java.util.Objects;

/** Captured product provenance, not current global Lab state or a billing decision. */
public final class ProtectionScope {
    public enum Origin { UNSPECIFIED, NORMAL, LAB }
    public static final ProtectionScope UNSPECIFIED = new ProtectionScope(Origin.UNSPECIFIED, -1);
    public final Origin origin;
    /** Captured displayed-tap scope. Unspecified provenance never authorizes attribution. */
    public final long tapScope;

    private ProtectionScope(Origin origin, long tapScope) {
        this.origin = Objects.requireNonNull(origin);
        if (origin != Origin.UNSPECIFIED && tapScope < 0) throw new IllegalArgumentException("tap scope");
        this.tapScope = tapScope;
    }

    public static ProtectionScope normal(long tapScope) { return new ProtectionScope(Origin.NORMAL, tapScope); }
    public static ProtectionScope lab(long tapScope) { return new ProtectionScope(Origin.LAB, tapScope); }

    /** Eligibility metadata only; an actual visible draw/current tap scope is still required. */
    public boolean isNormal() { return origin == Origin.NORMAL; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ProtectionScope)) return false;
        ProtectionScope value = (ProtectionScope) other;
        return origin == value.origin && tapScope == value.tapScope;
    }

    @Override public int hashCode() { return 31 * origin.hashCode() + Long.hashCode(tapScope); }
}
