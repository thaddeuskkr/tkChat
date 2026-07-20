package dev.tkkr.tkchat.core.model;

import java.util.Objects;

public record AccessDecision(boolean allowed, DenialReason denialReason, String prefix, String suffix) {
    public AccessDecision {
        if (!allowed) {
            denialReason = Objects.requireNonNull(denialReason, "denialReason");
        }
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
    }

    public static AccessDecision allow(String prefix, String suffix) {
        return new AccessDecision(true, null, prefix, suffix);
    }

    public static AccessDecision deny(DenialReason reason) {
        return new AccessDecision(false, reason, "", "");
    }
}
