package dev.tkkr.tkchat.velocity.service;

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
    private static final class SerialQueue {
        private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
        private boolean running;

        private void submit(Runnable task) {
            boolean start;
            synchronized (this) {
                start = !running;
                if (start) {
                    running = true;
                } else {
                    waiting.addLast(task);
                }
            }
            if (start) {
                task.run();
            }
        }

        private void finished() {
            Runnable next;
            synchronized (this) {
                next = waiting.pollFirst();
                if (next == null) {
                    running = false;
                }
            }
            if (next != null) {
                next.run();
            }
        }

        private synchronized boolean isIdle() {
            return !running && waiting.isEmpty();
        }
    }

    private final Map<UUID, SerialQueue> queues = new ConcurrentHashMap<>();

    <T> CompletionStage<T> submit(UUID senderId, Supplier<? extends CompletionStage<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SerialQueue queue = queues.computeIfAbsent(senderId, ignored -> new SerialQueue());
        queue.submit(() -> {
            CompletionStage<T> stage;
            try {
                stage = java.util.Objects.requireNonNull(
                        operation.get(), "Queued chat operation returned null");
            } catch (Throwable error) {
                result.completeExceptionally(error);
                queue.finished();
                return;
            }
            stage.whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(unwrap(error));
                }
                queue.finished();
            });
        });
        return result;
    }

    void remove(UUID senderId) {
        queues.computeIfPresent(senderId,
                (ignored, queue) -> queue.isIdle() ? null : queue);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

}
