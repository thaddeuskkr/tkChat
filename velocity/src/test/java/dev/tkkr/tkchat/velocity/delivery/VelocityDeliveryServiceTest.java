package dev.tkkr.tkchat.velocity.delivery;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.service.PlayerFormattingService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VelocityDeliveryServiceTest {
    @Test
    void actionUsesMeFormatWithoutChangingItsExistingRouteKind() {
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.me = "action format";
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "global", "Global", "global", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "waves",
                Set.of(), null, Set.of()).asAction();

        String selected = VelocityDeliveryService.selectTemplate(
                message, UUID.randomUUID(), channels, formats);

        assertEquals(RouteKind.CHANNEL, message.routeKind());
        assertEquals("action format", selected);
        assertEquals("channel format", VelocityDeliveryService.selectTemplate(
                message.withFormatting(Set.of()), UUID.randomUUID(), channels, formats));
    }

    @Test
    void lifecycleMarkersSelectGlobalAndServerFormats() {
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.globalJoin = "global join format";
        formats.globalLeave = "global leave format";
        formats.join = "join format";
        formats.leave = "leave format";
        ApprovedMessage base = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.BROADCAST,
                "presence", "Presence", "broadcast", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "lobby", "", "", "fallback",
                Set.of(), null, Set.of());

        assertEquals("global join format", VelocityDeliveryService.selectTemplate(
                base.asGlobalJoinMessage(), UUID.randomUUID(), channels, formats));
        assertEquals("global leave format", VelocityDeliveryService.selectTemplate(
                base.asGlobalLeaveMessage(), UUID.randomUUID(), channels, formats));
        assertEquals("join format", VelocityDeliveryService.selectTemplate(
                base.asJoinMessage(), UUID.randomUUID(), channels, formats));
        assertEquals("leave format", VelocityDeliveryService.selectTemplate(
                base.asLeaveMessage(), UUID.randomUUID(), channels, formats));
    }

    @Test
    void lifecycleFormatsRespectServerScopeWithoutDuplicateGlobalMessages() {
        UUID senderId = UUID.randomUUID();
        AtomicReference<Component> localReceived = new AtomicReference<>();
        AtomicReference<Component> remoteReceived = new AtomicReference<>();
        AtomicReference<Component> joiningPlayerReceived = new AtomicReference<>();
        Player localViewer = playerOnServer(UUID.randomUUID(), "lobby", localReceived);
        Player remoteViewer = playerOnServer(UUID.randomUUID(), "survival", remoteReceived);
        Player joiningPlayer = playerOnServer(senderId, "lobby", joiningPlayerReceived);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> method.getName().equals("getAllPlayers")
                        ? List.of(localViewer, remoteViewer, joiningPlayer)
                        : defaultValue(method.getReturnType()));
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.join = "<green><name> joined <server>: <message></green>";
        formats.globalJoin = "<aqua>Global: <name> joined <server></aqua>";
        VelocityDeliveryService delivery = new VelocityDeliveryService(
                proxy, channels, null, formats, new AppConfig.Mentions(),
                new AppConfig.ItemLinks(), new PlayerFormattingService(), null, null,
                50, Duration.ofSeconds(30));
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "join", "join", "presence", ChannelScope.SERVER,
                senderId, "<red>Alice</red>", "lobby",
                "", "", "fallback", Set.of(), null, Set.of()).asJoinMessage();

        delivery.deliver(message);

        assertEquals("<red>Alice</red> joined lobby: fallback",
                plain(localReceived.get()));
        assertNull(remoteReceived.get());
        assertNull(joiningPlayerReceived.get());

        localReceived.set(null);
        ApprovedMessage globalMessage = new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_join", "global_join", "presence", ChannelScope.GLOBAL,
                message.senderId(), message.senderName(), message.senderServerId(),
                "", "", "fallback", Set.of(), null, Set.of()).asGlobalJoinMessage();

        delivery.deliver(globalMessage);

        assertNull(localReceived.get());
        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(remoteReceived.get()));
        assertNull(joiningPlayerReceived.get());

        formats.join = "";
        localReceived.set(null);
        remoteReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_join", "global_join", "presence", ChannelScope.GLOBAL,
                message.senderId(), message.senderName(), message.senderServerId(),
                "", "", "fallback", Set.of(), null, Set.of()).asGlobalJoinMessage());

        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(localReceived.get()));
        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(remoteReceived.get()));
        assertNull(joiningPlayerReceived.get());
    }

    private static Player playerOnServer(
            UUID playerId,
            String serverName,
            AtomicReference<Component> received
    ) {
        ServerInfo serverInfo = new ServerInfo(
                serverName, new InetSocketAddress("127.0.0.1", 25565));
        ServerConnection connection = (ServerConnection) Proxy.newProxyInstance(
                ServerConnection.class.getClassLoader(), new Class<?>[]{ServerConnection.class},
                (ignored, method, arguments) -> method.getName().equals("getServerInfo")
                        ? serverInfo
                        : defaultValue(method.getReturnType()));
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getCurrentServer" -> Optional.of(connection);
                    case "sendMessage" -> {
                        if (arguments != null) {
                            for (Object argument : arguments) {
                                if (argument instanceof Component component) {
                                    received.set(component);
                                }
                            }
                        }
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
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
}
