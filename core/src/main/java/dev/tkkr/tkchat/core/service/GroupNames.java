package dev.tkkr.tkchat.core.service;

import java.util.Locale;
import java.util.Objects;

public final class GroupNames {
    private GroupNames() {
    }

    public static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Group names must match [A-Za-z0-9_-]{1,32}");
        }
        return normalized;
    }
}
