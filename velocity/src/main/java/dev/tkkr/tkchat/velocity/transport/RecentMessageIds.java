package dev.tkkr.tkchat.velocity.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecentMessageIds {
    private final Map<UUID, Instant> seen = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Clock clock;

    public RecentMessageIds(Duration retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    public boolean first(UUID messageId) {
        Instant now = clock.instant();
        Instant previous = seen.putIfAbsent(messageId, now);
        if (seen.size() > 10_000) {
            Instant cutoff = now.minus(retention);
            seen.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        return previous == null;
    }
}
