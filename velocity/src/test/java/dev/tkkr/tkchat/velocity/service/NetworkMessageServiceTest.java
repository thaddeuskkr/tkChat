package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.model.ItemLink;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkMessageServiceTest {
    @Test
    void clearChatPublishesSelectedChannelAndScope() {
        CapturingTransport transport = new CapturingTransport();
        NetworkMessageService messages = new NetworkMessageService(
                transport, (player, message) -> CompletableFuture.completedFuture(message));
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

    @Test
    void playerBroadcastIsEnrichedBeforePublishing() {
        CapturingTransport transport = new CapturingTransport();
        ItemLink linkedItem = new ItemLink("minecraft:diamond", 2, "Diamond");
        NetworkMessageService messages = new NetworkMessageService(
                transport,
                (player, message) -> CompletableFuture.completedFuture(
                        message.withItemLink(linkedItem)));

        messages.broadcast(player(), "Look: [item]").toCompletableFuture().join();

        assertEquals(RouteKind.BROADCAST, transport.published.routeKind());
        assertEquals("Look: [item]", transport.published.content());
        assertEquals(linkedItem, transport.published.itemLink());
    }

    private static CommandSource console() {
        return permission -> Tristate.TRUE;
    }

    private static Player player() {
        UUID playerId = UUID.randomUUID();
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> "Broadcaster";
                    case "getCurrentServer" -> Optional.empty();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
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
