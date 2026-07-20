package dev.tkkr.tkchat.velocity.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecentMessageIds {
    private record SeenMessage(UUID id, Instant seenAt) {
    }

    private final Map<UUID, Instant> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SeenMessage> insertionOrder = new ConcurrentLinkedQueue<>();
    private final Duration retention;
    private final Clock clock;
    private final int maximumEntries;

    public RecentMessageIds(Duration retention, Clock clock) {
        this(retention, clock, 10_000);
    }

    RecentMessageIds(Duration retention, Clock clock, int maximumEntries) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.retention = retention;
        this.clock = clock;
        this.maximumEntries = maximumEntries;
    }

    public boolean first(UUID messageId) {
        Instant now = clock.instant();
        evict(now);
        Instant previous = seen.putIfAbsent(messageId, now);
        if (previous != null) {
            return false;
        }
        insertionOrder.add(new SeenMessage(messageId, now));
        evict(now);
        return true;
    }

    private void evict(Instant now) {
        Instant cutoff = now.minus(retention);
        while (true) {
            SeenMessage oldest = insertionOrder.peek();
            if (oldest == null
                    || (seen.size() <= maximumEntries && oldest.seenAt().isAfter(cutoff))) {
                return;
            }
            insertionOrder.poll();
            seen.remove(oldest.id(), oldest.seenAt());
        }
    }
}
