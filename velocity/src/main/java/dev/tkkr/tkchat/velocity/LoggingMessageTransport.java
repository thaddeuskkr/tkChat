package dev.tkkr.tkchat.velocity;

import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Logs approved chat once on its originating Velocity instance before transport fan-out. */
final class LoggingMessageTransport implements MessageTransport {
    private final MessageTransport delegate;
    private final Consumer<String> log;

    LoggingMessageTransport(MessageTransport delegate, Logger logger) {
        this(delegate, line -> logger.info("{}", line));
    }

    LoggingMessageTransport(MessageTransport delegate, Consumer<String> log) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void start(Consumer<ApprovedMessage> listener) throws Exception {
        delegate.start(listener);
    }

    @Override
    public CompletionStage<Void> publish(ApprovedMessage message) {
        String line = formatChatMessage(message);
        if (line != null) {
            try {
                log.accept(line);
            } catch (RuntimeException ignored) {
                // Console logging must never prevent an approved message from being delivered.
            }
        }
        return delegate.publish(message);
    }

    @Override
    public void close() {
        delegate.close();
    }

    static String formatChatMessage(ApprovedMessage message) {
        if (message.routeKind() == RouteKind.CHAT_CLEAR || isLifecycleMessage(message)) {
            return null;
        }
        String route = switch (message.routeKind()) {
            case CHANNEL -> "channel:" + message.channelId();
            case GROUP -> "group:" + message.routeDisplayName();
            case DIRECT -> "direct:" + message.routeDisplayName();
            case BROADCAST -> "broadcast";
            case CHAT_CLEAR -> throw new IllegalStateException("Chat clear was filtered above");
        };
        if (message.hasActionMarker()) {
            route = "action/" + route;
        }
        return "[Chat/" + singleLine(route) + "] "
                + singleLine(message.senderName()) + "@" + singleLine(message.senderServerId())
                + ": " + singleLine(message.content());
    }

    private static boolean isLifecycleMessage(ApprovedMessage message) {
        return message.hasJoinMarker() || message.hasLeaveMarker()
                || message.hasGlobalJoinMarker() || message.hasGlobalLeaveMarker();
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
