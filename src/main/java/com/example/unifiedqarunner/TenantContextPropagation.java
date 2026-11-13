package com.example.unifiedqarunner;

import java.util.Objects;
import java.util.function.Function;

/** Explicit, one-shot propagation for background work; no ThreadLocal or shared mutable context is used. */
final class TenantContextPropagation {
    private TenantContextPropagation() { }

    static Runnable wrap(TenantContext captured, Function<TenantContext, TenantContext> revalidate, Runnable action) {
        Objects.requireNonNull(captured);
        Objects.requireNonNull(revalidate);
        Objects.requireNonNull(action);
        return () -> {
            TenantContext current = revalidate.apply(captured);
            if (!captured.equals(current)) throw TenantAuthorizationException.forbidden();
            action.run();
        };
    }
}
