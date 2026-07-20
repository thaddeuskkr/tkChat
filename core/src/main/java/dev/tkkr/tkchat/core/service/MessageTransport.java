package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.ApprovedMessage;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface MessageTransport extends AutoCloseable {
    void start(Consumer<ApprovedMessage> listener) throws Exception;

    CompletionStage<Void> publish(ApprovedMessage message);

    @Override
    void close();
}
