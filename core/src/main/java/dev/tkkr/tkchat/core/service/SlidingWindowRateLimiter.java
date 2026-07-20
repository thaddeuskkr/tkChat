package dev.tkkr.tkchat.core.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SlidingWindowRateLimiter {
    private final int maximum;
    private final Duration window;
    private final Map<UUID, ArrayDeque<Instant>> attempts = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int maximum, Duration window) {
        this.maximum = maximum;
        this.window = window;
    }

    public boolean tryAcquire(UUID playerId, Instant now) {
        ArrayDeque<Instant> timestamps = attempts.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            Instant cutoff = now.minus(window);
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maximum) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    public void remove(UUID playerId) {
        attempts.remove(playerId);
    }
}
