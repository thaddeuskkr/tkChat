package dev.tkkr.tkchat.core.service;

import java.time.Duration;

public record ChatPolicy(
        int maxMessageLength,
        int rateLimitMessages,
        Duration rateLimitWindow
) {
    public ChatPolicy {
        if (maxMessageLength < 1 || rateLimitMessages < 1 || rateLimitWindow.isNegative() || rateLimitWindow.isZero()) {
            throw new IllegalArgumentException("Invalid chat policy values");
        }
    }
}
