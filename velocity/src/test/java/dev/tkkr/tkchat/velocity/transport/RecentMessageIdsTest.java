package dev.tkkr.tkchat.velocity.transport;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentMessageIdsTest {
    @Test
    void rejectsDuplicatesAndEvictsTheOldestEntryAtTheBound() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T10:00:00Z"));
        RecentMessageIds ids = new RecentMessageIds(Duration.ofMinutes(1), clock, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertTrue(ids.first(first));
        assertFalse(ids.first(first));
        assertTrue(ids.first(second));
        assertTrue(ids.first(third));
        assertTrue(ids.first(first));
    }

    @Test
    void acceptsAnIdAgainAfterRetentionExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T10:00:00Z"));
        RecentMessageIds ids = new RecentMessageIds(Duration.ofSeconds(5), clock, 10);
        UUID message = UUID.randomUUID();

        assertTrue(ids.first(message));
        clock.advance(Duration.ofSeconds(6));
        assertTrue(ids.first(message));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
