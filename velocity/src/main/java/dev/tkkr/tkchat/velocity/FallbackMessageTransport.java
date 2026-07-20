package dev.tkkr.tkchat.velocity;

import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.service.LocalMessageTransport;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.slf4j.Logger;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class FallbackMessageTransport implements MessageTransport {
    private final MessageTransport primary;
    private final LocalMessageTransport fallback = new LocalMessageTransport();
    private final Logger logger;
    private volatile MessageTransport active;

    FallbackMessageTransport(MessageTransport primary, Logger logger) {
        this.primary = primary;
        this.logger = logger;
    }

    @Override
    public void start(Consumer<ApprovedMessage> listener) throws Exception {
        fallback.start(listener);
        try {
            primary.start(listener);
            active = primary;
        } catch (Exception error) {
            logger.error("RabbitMQ is unavailable; using local-only delivery as configured.", error);
            active = fallback;
        }
    }

    @Override
    public CompletionStage<Void> publish(ApprovedMessage message) {
        return active.publish(message);
    }

    @Override
    public void close() {
        primary.close();
        fallback.close();
    }
}
