package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.Coordinates;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.model.ItemLink;
import dev.tkkr.tkchat.core.service.MessageTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        Coordinates coordinates = new Coordinates(10, 70, -30, "minecraft:overworld");
        NetworkMessageService messages = new NetworkMessageService(
                transport,
                (player, message) -> CompletableFuture.completedFuture(
                        message.withItemLink(linkedItem).withCoordinates(coordinates)));

        messages.broadcast(player(), "Look: [item] at [coords]").toCompletableFuture().join();

        assertEquals(RouteKind.BROADCAST, transport.published.routeKind());
        assertEquals("Look: [item] at [coords]", transport.published.content());
        assertEquals(linkedItem, transport.published.itemLink());
        assertEquals(coordinates, transport.published.findCoordinates().orElseThrow());
    }

    @Test
    void lifecycleMessagesPublishGlobalAndServerScopedVariants() {
        CapturingTransport transport = new CapturingTransport();
        NetworkMessageService messages = new NetworkMessageService(
                transport, (player, message) -> CompletableFuture.completedFuture(message));
        Player player = player();

        messages.playerJoined(player, "lobby").toCompletableFuture().join();

        ApprovedMessage globalJoin = transport.publishedMessages.get(0);
        ApprovedMessage localJoin = transport.publishedMessages.get(1);
        assertEquals(RouteKind.BROADCAST, globalJoin.routeKind());
        assertEquals(ChannelScope.GLOBAL, globalJoin.channelScope());
        org.junit.jupiter.api.Assertions.assertTrue(globalJoin.hasGlobalJoinMarker());
        assertEquals("Broadcaster joined the network.", globalJoin.content());
        assertEquals(RouteKind.CHANNEL, localJoin.routeKind());
        assertEquals(ChannelScope.SERVER, localJoin.channelScope());
        assertEquals("lobby", localJoin.senderServerId());
        org.junit.jupiter.api.Assertions.assertTrue(localJoin.hasJoinMarker());
        org.junit.jupiter.api.Assertions.assertFalse(localJoin.hasServerSwitchMarker());
        assertEquals("Broadcaster joined the server.", localJoin.content());

        messages.playerLeft(player, "survival").toCompletableFuture().join();

        ApprovedMessage globalLeave = transport.publishedMessages.get(2);
        ApprovedMessage localLeave = transport.publishedMessages.get(3);
        org.junit.jupiter.api.Assertions.assertTrue(globalLeave.hasGlobalLeaveMarker());
        assertEquals("Broadcaster left the network.", globalLeave.content());
        assertEquals("survival", localLeave.senderServerId());
        org.junit.jupiter.api.Assertions.assertTrue(localLeave.hasLeaveMarker());
        org.junit.jupiter.api.Assertions.assertFalse(localLeave.hasServerSwitchMarker());
        assertEquals("Broadcaster left the server.", localLeave.content());

        messages.playerSwitchedServers(
                player, "survival", "minigames").toCompletableFuture().join();

        ApprovedMessage switchLeave = transport.publishedMessages.get(4);
        ApprovedMessage switchJoin = transport.publishedMessages.get(5);
        ApprovedMessage switchSummary = transport.publishedMessages.get(6);
        assertEquals(7, transport.publishedMessages.size());
        assertEquals(ChannelScope.SERVER, switchLeave.channelScope());
        assertEquals("survival", switchLeave.senderServerId());
        org.junit.jupiter.api.Assertions.assertTrue(switchLeave.hasLeaveMarker());
        org.junit.jupiter.api.Assertions.assertFalse(switchLeave.hasServerSwitchMarker());
        assertEquals(ChannelScope.SERVER, switchJoin.channelScope());
        assertEquals("minigames", switchJoin.senderServerId());
        org.junit.jupiter.api.Assertions.assertTrue(switchJoin.hasJoinMarker());
        org.junit.jupiter.api.Assertions.assertFalse(switchJoin.hasServerSwitchMarker());
        assertEquals(RouteKind.CHANNEL, switchSummary.routeKind());
        assertEquals(ChannelScope.SERVER, switchSummary.channelScope());
        assertEquals("survival", switchSummary.routeId());
        assertEquals("minigames", switchSummary.routeDisplayName());
        assertEquals("", switchSummary.senderServerId());
        assertEquals("Broadcaster left survival and joined minigames.", switchSummary.content());
        org.junit.jupiter.api.Assertions.assertTrue(switchSummary.hasJoinMarker());
        org.junit.jupiter.api.Assertions.assertTrue(switchSummary.hasServerSwitchMarker());
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
        private final List<ApprovedMessage> publishedMessages = new ArrayList<>();

        @Override
        public void start(Consumer<ApprovedMessage> listener) {
        }

        @Override
        public CompletionStage<Void> publish(ApprovedMessage message) {
            published = message;
            publishedMessages.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
