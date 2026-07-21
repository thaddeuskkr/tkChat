package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Player playerOnServer(String serverName) {
        UUID playerId = UUID.randomUUID();
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (ignored, method, arguments) -> method.getName().equals("getServerInfo")
                        ? serverInfo(serverName)
                        : defaultValue(method.getReturnType()));
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getUsername" -> "Switcher";
                    case "getCurrentServer" -> Optional.of(connection);
                    default -> defaultValue(method.getReturnType());
                });
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
