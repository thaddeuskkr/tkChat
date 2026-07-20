package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkMessageServiceTest {
    @Test
    void clearChatPublishesSelectedChannelAndScope() {
        CapturingTransport transport = new CapturingTransport();
        NetworkMessageService messages = new NetworkMessageService(transport);
        ChannelDefinition channel = new ChannelDefinition(
                "local", "Local", ChannelScope.SERVER,
                "send", "receive", "bypass", List.of("l"), "<message>");

        messages.clearChat(console(), channel).toCompletableFuture().join();

        ApprovedMessage published = transport.published;
        assertEquals(RouteKind.CHAT_CLEAR, published.routeKind());
        assertEquals("chat_clear:local", published.routeId());
        assertEquals("local", published.channelId());
        assertEquals("Local", published.routeDisplayName());
        assertEquals(ChannelScope.SERVER, published.channelScope());
    }

    private static CommandSource console() {
        return permission -> Tristate.TRUE;
    }

    private static final class CapturingTransport implements MessageTransport {
        private ApprovedMessage published;

        @Override
        public void start(Consumer<ApprovedMessage> listener) {
        }

        @Override
        public CompletionStage<Void> publish(ApprovedMessage message) {
            published = message;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
