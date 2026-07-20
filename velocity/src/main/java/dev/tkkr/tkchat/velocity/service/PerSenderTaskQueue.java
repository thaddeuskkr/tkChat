package dev.tkkr.tkchat.velocity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Runs asynchronous outbound chat work in submission order for each sender. */
final class PerSenderTaskQueue {
    enum RejectionReason {
        FULL,
        EXPIRED
    }

    static final class QueueRejectedException extends RuntimeException {
        private final RejectionReason reason;

        private QueueRejectedException(RejectionReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        RejectionReason reason() {
            return reason;
        }
    }

    private record QueuedTask(Runnable operation) {
    }

    private static final class SerialQueue {
        private final ArrayDeque<QueuedTask> waiting = new ArrayDeque<>();
        private boolean running;
        private boolean removeWhenIdle;

        private boolean submit(QueuedTask task, int maxPending) {
            boolean start;
            synchronized (this) {
                start = !running;
                if (start) {
                    running = true;
                } else {
                    if (waiting.size() >= maxPending) {
                        return false;
                    }
                    waiting.addLast(task);
                }
            }
            if (start) {
                task.operation().run();
            }
            return true;
        }

        private boolean finished() {
            QueuedTask next;
            boolean remove;
            synchronized (this) {
                next = waiting.pollFirst();
                if (next == null) {
                    running = false;
                }
                remove = next == null && removeWhenIdle;
            }
            if (next != null) {
                next.operation().run();
            }
            return remove;
        }

        private synchronized boolean removeWhenIdle() {
            removeWhenIdle = true;
            return !running && waiting.isEmpty();
        }
    }

    private final Map<UUID, SerialQueue> queues = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile int maxPending;
    private volatile Duration maxAge;

    PerSenderTaskQueue() {
        this(8, Duration.ofSeconds(5), Clock.systemUTC());
    }

    PerSenderTaskQueue(int maxPending, Duration maxAge, Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        reconfigure(maxPending, maxAge);
    }

    void reconfigure(int maxPending, Duration maxAge) {
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        this.maxPending = maxPending;
        this.maxAge = maxAge;
    }

    <T> CompletionStage<T> submit(UUID senderId, Supplier<? extends CompletionStage<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SerialQueue queue = queues.computeIfAbsent(senderId, ignored -> new SerialQueue());
        Instant submittedAt = clock.instant();
        QueuedTask queued = new QueuedTask(() -> {
            if (clock.instant().isAfter(submittedAt.plus(maxAge))) {
                result.completeExceptionally(new QueueRejectedException(
                        RejectionReason.EXPIRED, "Queued chat message expired before processing"));
                finish(senderId, queue);
                return;
            }
            CompletionStage<T> stage;
            try {
                stage = java.util.Objects.requireNonNull(
                        operation.get(), "Queued chat operation returned null");
            } catch (Throwable error) {
                result.completeExceptionally(error);
                finish(senderId, queue);
                return;
            }
            stage.whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(unwrap(error));
                }
                finish(senderId, queue);
            });
        });
        if (!queue.submit(queued, maxPending)) {
            result.completeExceptionally(new QueueRejectedException(
                    RejectionReason.FULL, "Too many chat messages are already queued for this sender"));
        }
        return result;
    }

    void remove(UUID senderId) {
        queues.computeIfPresent(senderId,
                (ignored, queue) -> queue.removeWhenIdle() ? null : queue);
    }

    private void finish(UUID senderId, SerialQueue queue) {
        if (queue.finished()) {
            queues.remove(senderId, queue);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

}
