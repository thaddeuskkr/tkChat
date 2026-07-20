package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.ApprovedMessage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class LocalMessageTransport implements MessageTransport {
    private volatile Consumer<ApprovedMessage> listener = ignored -> {
    };

    @Override
    public void start(Consumer<ApprovedMessage> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public CompletionStage<Void> publish(ApprovedMessage message) {
        listener.accept(message);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        listener = ignored -> {
        };
    }
}
