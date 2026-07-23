package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.tkkr.tkchat.core.model.AccessDecision;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.ChatPolicy;
import dev.tkkr.tkchat.core.service.ChatRouter;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLifecycleListenerTest {
    @Test
    void serverSwitchPublishesOnlyLocalLeaveAndJoinMessages() {
        CapturingTransport transport = new CapturingTransport();
        NetworkMessageService messages = new NetworkMessageService(transport, null, null);
        PlayerLifecycleListener listener = new PlayerLifecycleListener(
                null, null, null, null, null, null, null, messages, null);
        Player player = playerOnServer("beta");

        listener.onServerPostConnect(new ServerPostConnectEvent(
                player, registeredServer("alpha")));

        assertEquals(2, transport.published.size());
        ApprovedMessage leave = transport.published.get(0);
        ApprovedMessage join = transport.published.get(1);
        assertTrue(leave.hasLeaveMarker());
        assertEquals("alpha", leave.senderServerId());
        assertTrue(join.hasJoinMarker());
        assertEquals("beta", join.senderServerId());
    }

    @Test
    void replacedConnectionDisconnectDoesNotClearTheCurrentPlayersState() {
        UUID playerId = UUID.randomUUID();
        UUID conversationPartner = UUID.randomUUID();
        Player oldPlayer = playerOnServer(playerId, "Player", "alpha");
        Player currentPlayer = playerOnServer(playerId, "Player", "beta");
        LifecycleFixture fixture = lifecycleFixture(proxyWithPlayer(currentPlayer));
        fixture.states.activate(playerId);
        fixture.states.load(playerId).toCompletableFuture().join();
        fixture.conversations.recordOutgoing(playerId, conversationPartner);
        fixture.spies.set(playerId, true);
        fixture.listener.onServerPostConnect(new ServerPostConnectEvent(oldPlayer, null));
        fixture.transport.published.clear();

        fixture.listener.onDisconnect(new DisconnectEvent(
                oldPlayer, DisconnectEvent.LoginStatus.CONFLICTING_LOGIN));

        assertTrue(fixture.states.isLoaded(playerId));
        assertEquals(Optional.of(conversationPartner), fixture.conversations.partner(playerId));
        assertTrue(fixture.spies.enabled(playerId));
        assertTrue(fixture.transport.published.isEmpty());
    }

    @Test
    void currentConnectionDisconnectStillClearsPlayerState() {
        UUID playerId = UUID.randomUUID();
        UUID conversationPartner = UUID.randomUUID();
        Player player = playerOnServer(playerId, "Player", "alpha");
        LifecycleFixture fixture = lifecycleFixture(proxyWithPlayer(null));
        fixture.states.activate(playerId);
        fixture.states.load(playerId).toCompletableFuture().join();
        fixture.conversations.recordOutgoing(playerId, conversationPartner);
        fixture.spies.set(playerId, true);
        fixture.listener.onServerPostConnect(new ServerPostConnectEvent(player, null));
        fixture.transport.published.clear();

        fixture.listener.onDisconnect(new DisconnectEvent(
                player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertFalse(fixture.states.isLoaded(playerId));
        assertTrue(fixture.conversations.partner(playerId).isEmpty());
        assertFalse(fixture.spies.enabled(playerId));
        assertEquals(2, fixture.transport.published.size());
        assertTrue(fixture.transport.published.stream().anyMatch(ApprovedMessage::hasGlobalLeaveMarker));
        assertTrue(fixture.transport.published.stream().anyMatch(ApprovedMessage::hasLeaveMarker));
    }

    private static Player playerOnServer(String serverName) {
        return playerOnServer(UUID.randomUUID(), "Switcher", serverName);
    }

    private static Player playerOnServer(UUID playerId, String username, String serverName) {
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (ignored, method, arguments) -> method.getName().equals("getServerInfo")
                        ? serverInfo(serverName)
                        : defaultValue(method.getReturnType()));
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> username;
                    case "getCurrentServer" -> Optional.of(connection);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ProxyServer proxyWithPlayer(Player currentPlayer) {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> {
                    if (method.getName().equals("getPlayer")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof UUID playerId) {
                        return currentPlayer != null && currentPlayer.getUniqueId().equals(playerId)
                                ? Optional.of(currentPlayer)
                                : Optional.empty();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static LifecycleFixture lifecycleFixture(ProxyServer proxy) {
        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of(), "<message>")));
        PlayerStateService states = new PlayerStateService(
                new InMemorySocialRepository("global"), channels, "global");
        ConversationTracker conversations = new ConversationTracker();
        SocialSpyService spies = new SocialSpyService();
        CapturingTransport transport = new CapturingTransport();
        ChatRouter router = new ChatRouter(
                channels,
                (sender, permission, bypassPermission, containsLink) ->
                        CompletableFuture.completedFuture(AccessDecision.allow("", "")),
                states,
                new ChatPolicy(256, 5, Duration.ofSeconds(10)),
                Clock.systemUTC(),
                "bypass");
        VelocityChatService chat = new VelocityChatService(
                proxy, router, transport, conversations, null, null, null,
                8, Duration.ofSeconds(30));
        NetworkMessageService messages = new NetworkMessageService(transport, null, null);
        PlayerLifecycleListener listener = new PlayerLifecycleListener(
                null, proxy, null, states, conversations, spies, chat, messages, null);
        return new LifecycleFixture(listener, states, conversations, spies, transport);
    }

    private static RegisteredServer registeredServer(String serverName) {
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(), new Class<?>[]{RegisteredServer.class},
                (ignored, method, arguments) -> method.getName().equals("getServerInfo")
                        ? serverInfo(serverName)
                        : defaultValue(method.getReturnType()));
    }

    private static ServerInfo serverInfo(String serverName) {
        return new ServerInfo(serverName, new InetSocketAddress("127.0.0.1", 25565));
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

    private record LifecycleFixture(
            PlayerLifecycleListener listener,
            PlayerStateService states,
            ConversationTracker conversations,
            SocialSpyService spies,
            CapturingTransport transport
    ) {
    }

    private static final class CapturingTransport implements MessageTransport {
        private final List<ApprovedMessage> published = new ArrayList<>();

        @Override
        public void start(Consumer<ApprovedMessage> listener) {
        }

        @Override
        public CompletionStage<Void> publish(ApprovedMessage message) {
            published.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
