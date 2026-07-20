package dev.tkkr.tkchat.velocity.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerSenderTaskQueueTest {
    @Test
    void serializesOneSendersTasksWithoutBlockingAnotherSender() {
        PerSenderTaskQueue queue = new PerSenderTaskQueue();
        UUID firstSender = UUID.randomUUID();
        UUID secondSender = UUID.randomUUID();
        CompletableFuture<String> firstGate = new CompletableFuture<>();
        List<String> started = new ArrayList<>();

        var first = queue.submit(firstSender, () -> {
            started.add("first");
            return firstGate;
        });
        var second = queue.submit(firstSender, () -> {
            started.add("second");
            return CompletableFuture.completedFuture("second");
        });
        var independent = queue.submit(secondSender, () -> {
            started.add("independent");
            return CompletableFuture.completedFuture("independent");
        });

        assertEquals(List.of("first", "independent"), started);
        assertFalse(first.toCompletableFuture().isDone());
        assertFalse(second.toCompletableFuture().isDone());
        assertTrue(independent.toCompletableFuture().isDone());

        firstGate.complete("first");

        assertEquals(List.of("first", "independent", "second"), started);
        assertEquals("first", first.toCompletableFuture().join());
        assertEquals("second", second.toCompletableFuture().join());
    }

    @Test
    void aFailedTaskDoesNotBlockTheNextTask() {
        PerSenderTaskQueue queue = new PerSenderTaskQueue();
        UUID sender = UUID.randomUUID();
        CompletableFuture<String> gate = new CompletableFuture<>();

        var first = queue.submit(sender, () -> gate);
        var second = queue.submit(sender,
                () -> CompletableFuture.completedFuture("delivered"));

        gate.completeExceptionally(new IllegalStateException("failed"));

        assertTrue(first.toCompletableFuture().isCompletedExceptionally());
        assertEquals("delivered", second.toCompletableFuture().join());
    }

    @Test
    void rejectsWorkBeyondThePerSenderLimit() {
        PerSenderTaskQueue queue = new PerSenderTaskQueue(
                1, Duration.ofSeconds(5), Clock.systemUTC());
        UUID sender = UUID.randomUUID();
        CompletableFuture<String> gate = new CompletableFuture<>();

        queue.submit(sender, () -> gate);
        queue.submit(sender, () -> CompletableFuture.completedFuture("waiting"));
        var rejected = queue.submit(sender, () -> CompletableFuture.completedFuture("overflow"));

        CompletionException error = assertThrows(CompletionException.class,
                () -> rejected.toCompletableFuture().join());
        PerSenderTaskQueue.QueueRejectedException cause = assertInstanceOf(
                PerSenderTaskQueue.QueueRejectedException.class, error.getCause());
        assertEquals(PerSenderTaskQueue.RejectionReason.FULL, cause.reason());
        gate.complete("first");
    }

    @Test
    void expiresMessagesBeforeStartingOldQueuedWork() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-20T10:00:00Z"));
        PerSenderTaskQueue queue = new PerSenderTaskQueue(2, Duration.ofSeconds(1), clock);
        UUID sender = UUID.randomUUID();
        CompletableFuture<String> gate = new CompletableFuture<>();
        AtomicBoolean staleOperationStarted = new AtomicBoolean();

        queue.submit(sender, () -> gate);
        var stale = queue.submit(sender, () -> {
            staleOperationStarted.set(true);
            return CompletableFuture.completedFuture("stale");
        });
        clock.advance(Duration.ofSeconds(2));
        gate.complete("first");

        CompletionException error = assertThrows(CompletionException.class,
                () -> stale.toCompletableFuture().join());
        PerSenderTaskQueue.QueueRejectedException cause = assertInstanceOf(
                PerSenderTaskQueue.QueueRejectedException.class, error.getCause());
        assertEquals(PerSenderTaskQueue.RejectionReason.EXPIRED, cause.reason());
        assertFalse(staleOperationStarted.get());
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
