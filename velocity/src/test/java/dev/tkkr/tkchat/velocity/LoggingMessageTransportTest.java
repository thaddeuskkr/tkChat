package dev.tkkr.tkchat.velocity;

import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingMessageTransportTest {
    @Test
    void logsEveryChatRouteOnceAndStillPublishesIt() {
        CapturingTransport delegate = new CapturingTransport();
        List<String> logs = new ArrayList<>();
        LoggingMessageTransport transport = new LoggingMessageTransport(delegate, logs::add);
        List<ApprovedMessage> messages = List.of(
                message(RouteKind.CHANNEL, "global", "Global", "hello"),
                message(RouteKind.GROUP, "group", "Builders", "group secret"),
                message(RouteKind.DIRECT, "direct", "Bob", "direct secret"),
                message(RouteKind.BROADCAST, "broadcast", "Broadcast", "network notice"),
                message(RouteKind.CHANNEL, "global", "Global", "waves").asAction());

        messages.forEach(message -> transport.publish(message).toCompletableFuture().join());

        assertEquals(List.of(
                "[Chat/channel:global] Alice@lobby: hello",
                "[Chat/group:Builders] Alice@lobby: group secret",
                "[Chat/direct:Bob] Alice@lobby: direct secret",
                "[Chat/broadcast] Alice@lobby: network notice",
                "[Chat/action/channel:global] Alice@lobby: waves"), logs);
        assertEquals(messages, delegate.published);
    }

    @Test
    void doesNotLogLifecycleOrChatClearControlMessages() {
        CapturingTransport delegate = new CapturingTransport();
        List<String> logs = new ArrayList<>();
        LoggingMessageTransport transport = new LoggingMessageTransport(delegate, logs::add);
        List<ApprovedMessage> messages = List.of(
                message(RouteKind.CHANNEL, "presence", "Presence", "joined").asJoinMessage(),
                message(RouteKind.CHANNEL, "presence", "Presence", "left").asLeaveMessage(),
                message(RouteKind.BROADCAST, "presence", "Presence", "joined").asGlobalJoinMessage(),
                message(RouteKind.BROADCAST, "presence", "Presence", "left").asGlobalLeaveMessage(),
                message(RouteKind.CHAT_CLEAR, "global", "Global", ""));

        messages.forEach(message -> transport.publish(message).toCompletableFuture().join());

        assertTrue(logs.isEmpty());
        assertEquals(messages, delegate.published);
    }

    @Test
    void receivingAForwardedMessageDoesNotLogItAgain() throws Exception {
        CapturingTransport delegate = new CapturingTransport();
        List<String> logs = new ArrayList<>();
        LoggingMessageTransport transport = new LoggingMessageTransport(delegate, logs::add);
        List<ApprovedMessage> received = new ArrayList<>();
        transport.start(received::add);
        ApprovedMessage forwarded = message(RouteKind.CHANNEL, "global", "Global", "remote");

        delegate.listener.accept(forwarded);

        assertEquals(List.of(forwarded), received);
        assertTrue(logs.isEmpty());
        transport.close();
        assertTrue(delegate.closed);
    }

    @Test
    void logFormattingCannotInjectAdditionalConsoleLines() {
        ApprovedMessage message = message(
                RouteKind.CHANNEL, "global\nspoofed", "Global", "first\r\nsecond");

        String formatted = LoggingMessageTransport.formatChatMessage(message);

        assertEquals("[Chat/channel:global spoofed] Alice@lobby: first  second", formatted);
        assertNull(LoggingMessageTransport.formatChatMessage(
                message(RouteKind.CHAT_CLEAR, "global", "Global", "")));
    }

    private static ApprovedMessage message(
            RouteKind kind,
            String channelId,
            String routeDisplayName,
            String content
    ) {
        return new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-26T12:00:00Z"), kind,
                kind.name().toLowerCase(), routeDisplayName, channelId, ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "lobby", "", "", content,
                Set.of(), null, Set.of());
    }

    private static final class CapturingTransport implements MessageTransport {
        private final List<ApprovedMessage> published = new ArrayList<>();
        private Consumer<ApprovedMessage> listener;
        private boolean closed;

        @Override
        public void start(Consumer<ApprovedMessage> listener) {
            this.listener = listener;
        }

        @Override
        public CompletionStage<Void> publish(ApprovedMessage message) {
            published.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
