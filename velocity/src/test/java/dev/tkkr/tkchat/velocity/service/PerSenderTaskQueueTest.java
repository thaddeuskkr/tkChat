package dev.tkkr.tkchat.velocity.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
