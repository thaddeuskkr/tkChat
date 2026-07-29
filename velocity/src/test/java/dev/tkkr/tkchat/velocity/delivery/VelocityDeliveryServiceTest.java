package dev.tkkr.tkchat.velocity.delivery;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.Coordinates;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.Permissions;
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
        formats.serverSwitch = "server switch format";
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
        assertEquals("server switch format", VelocityDeliveryService.selectTemplate(
                base.asJoinMessage().asServerSwitchMessage(),
                UUID.randomUUID(), channels, formats));
    }

    @Test
    void lifecycleFormatsRespectServerScopeWithoutDuplicateGlobalMessages() {
        UUID senderId = UUID.randomUUID();
        AtomicReference<Component> localReceived = new AtomicReference<>();
        AtomicReference<Component> remoteReceived = new AtomicReference<>();
        AtomicReference<Component> bypassReceived = new AtomicReference<>();
        AtomicReference<Component> joiningPlayerReceived = new AtomicReference<>();
        Player localViewer = playerOnServer(UUID.randomUUID(), "lobby", localReceived);
        Player remoteViewer = playerOnServer(UUID.randomUUID(), "survival", remoteReceived);
        Player bypassViewer = playerOnServer(
                UUID.randomUUID(), "lobby", bypassReceived, true);
        Player joiningPlayer = playerOnServer(senderId, "lobby", joiningPlayerReceived);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> method.getName().equals("getAllPlayers")
                        ? List.of(localViewer, remoteViewer, bypassViewer, joiningPlayer)
                        : defaultValue(method.getReturnType()));
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.join = "<green><name> joined <server>: <message></green>";
        formats.globalJoin = "<aqua>Global: <name> joined <server></aqua>";
        AppConfig.Notifications notifications = new AppConfig.Notifications();
        notifications.globalJoin = true;
        VelocityDeliveryService delivery = new VelocityDeliveryService(
                proxy, channels, null, formats, notifications, new AppConfig.Mentions(),
                new AppConfig.ItemLinks(), new AppConfig.Coordinates(),
                new PlayerFormattingService(), null, null,
                50, Duration.ofSeconds(30));
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "join", "join", "presence", ChannelScope.SERVER,
                senderId, "<red>Alice</red>", "lobby",
                "", "", "fallback", Set.of(), null, Set.of()).asJoinMessage();

        delivery.deliver(message);

        assertEquals("<red>Alice</red> joined lobby: fallback",
                plain(localReceived.get()));
        assertNull(bypassReceived.get());
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
                plain(bypassReceived.get()));
        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(remoteReceived.get()));
        assertNull(joiningPlayerReceived.get());

        notifications.localJoin = false;
        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_join", "global_join", "presence", ChannelScope.GLOBAL,
                message.senderId(), message.senderName(), message.senderServerId(),
                "", "", "fallback", Set.of(), null, Set.of()).asGlobalJoinMessage());

        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(localReceived.get()));
        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(remoteReceived.get()));
        assertEquals("Global: <red>Alice</red> joined lobby",
                plain(bypassReceived.get()));
        assertNull(joiningPlayerReceived.get());

        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "join", "join", "presence", ChannelScope.SERVER,
                senderId, "Alice", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asJoinMessage());

        assertNull(localReceived.get());
        assertNull(remoteReceived.get());
        assertNull(bypassReceived.get());

        notifications.localJoin = true;
        notifications.globalJoin = false;
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_join", "global_join", "presence", ChannelScope.GLOBAL,
                senderId, "Alice", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asGlobalJoinMessage());

        assertNull(localReceived.get());
        assertNull(remoteReceived.get());
        assertEquals("Global: Alice joined lobby", plain(bypassReceived.get()));

        formats.leave = "<green><name> left <server></green>";
        formats.globalLeave = "<aqua>Global: <name> left <server></aqua>";
        notifications.localLeave = true;
        notifications.globalLeave = true;
        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        UUID leavingPlayerId = UUID.randomUUID();
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "leave", "leave", "presence", ChannelScope.SERVER,
                leavingPlayerId, "Bob", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asLeaveMessage());

        assertEquals("Bob left lobby", plain(localReceived.get()));
        assertNull(remoteReceived.get());
        assertNull(bypassReceived.get());

        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_leave", "global_leave", "presence", ChannelScope.GLOBAL,
                leavingPlayerId, "Bob", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asGlobalLeaveMessage());

        assertNull(localReceived.get());
        assertEquals("Global: Bob left lobby", plain(remoteReceived.get()));
        assertEquals("Global: Bob left lobby", plain(bypassReceived.get()));

        notifications.localLeave = false;
        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_leave", "global_leave", "presence", ChannelScope.GLOBAL,
                leavingPlayerId, "Bob", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asGlobalLeaveMessage());

        assertEquals("Global: Bob left lobby", plain(localReceived.get()));
        assertEquals("Global: Bob left lobby", plain(remoteReceived.get()));
        assertEquals("Global: Bob left lobby", plain(bypassReceived.get()));

        notifications.localLeave = true;
        notifications.globalLeave = false;
        localReceived.set(null);
        remoteReceived.set(null);
        bypassReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "global_leave", "global_leave", "presence", ChannelScope.GLOBAL,
                leavingPlayerId, "Bob", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asGlobalLeaveMessage());

        assertNull(localReceived.get());
        assertNull(remoteReceived.get());
        assertEquals("Global: Bob left lobby", plain(bypassReceived.get()));
    }

    @Test
    void serverSwitchSummaryIsPermissionOnlyWhileOrdinaryNoticesStayLocal() {
        UUID switchingPlayerId = UUID.randomUUID();
        AtomicReference<Component> oldServerReceived = new AtomicReference<>();
        AtomicReference<Component> newServerReceived = new AtomicReference<>();
        AtomicReference<Component> unrelatedReceived = new AtomicReference<>();
        AtomicReference<Component> permittedReceived = new AtomicReference<>();
        AtomicReference<Component> switchingPlayerReceived = new AtomicReference<>();
        Player oldServerViewer = playerOnServer(
                UUID.randomUUID(), "lobby", oldServerReceived);
        Player newServerViewer = playerOnServer(
                UUID.randomUUID(), "survival", newServerReceived);
        Player unrelatedViewer = playerOnServer(
                UUID.randomUUID(), "minigames", unrelatedReceived);
        Player permittedViewer = playerOnServer(
                UUID.randomUUID(), "minigames", permittedReceived, true);
        Player switchingPlayer = playerOnServer(
                switchingPlayerId, "survival", switchingPlayerReceived, true);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> method.getName().equals("getAllPlayers")
                        ? List.of(oldServerViewer, newServerViewer, unrelatedViewer,
                        permittedViewer, switchingPlayer)
                        : defaultValue(method.getReturnType()));
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.join = "<green><name> joined <server></green>";
        formats.leave = "<green><name> left <server></green>";
        formats.serverSwitch = "<yellow><user> left <old_server> and joined <new_server></yellow>";
        AppConfig.Notifications notifications = new AppConfig.Notifications();
        VelocityDeliveryService delivery = new VelocityDeliveryService(
                proxy, channels, null, formats, notifications, new AppConfig.Mentions(),
                new AppConfig.ItemLinks(), new AppConfig.Coordinates(),
                new PlayerFormattingService(), null, null,
                50, Duration.ofSeconds(30));

        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "leave", "leave", "presence", ChannelScope.SERVER,
                switchingPlayerId, "Bob", "lobby", "", "", "fallback",
                Set.of(), null, Set.of()).asLeaveMessage());

        assertEquals("Bob left lobby", plain(oldServerReceived.get()));
        assertNull(newServerReceived.get());
        assertNull(unrelatedReceived.get());
        assertNull(permittedReceived.get());
        assertNull(switchingPlayerReceived.get());

        oldServerReceived.set(null);
        newServerReceived.set(null);
        unrelatedReceived.set(null);
        permittedReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "join", "join", "presence", ChannelScope.SERVER,
                switchingPlayerId, "Bob", "survival", "", "", "fallback",
                Set.of(), null, Set.of()).asJoinMessage());

        assertNull(oldServerReceived.get());
        assertEquals("Bob joined survival", plain(newServerReceived.get()));
        assertNull(unrelatedReceived.get());
        assertNull(permittedReceived.get());
        assertNull(switchingPlayerReceived.get());

        oldServerReceived.set(null);
        newServerReceived.set(null);
        unrelatedReceived.set(null);
        permittedReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "lobby", "survival", "presence", ChannelScope.SERVER,
                switchingPlayerId, "Bob", "", "", "",
                "Bob left lobby and joined survival.", Set.of(), null, Set.of())
                .asJoinMessage()
                .asServerSwitchMessage());

        assertNull(oldServerReceived.get());
        assertNull(newServerReceived.get());
        assertNull(unrelatedReceived.get());
        assertEquals("Bob left lobby and joined survival", plain(permittedReceived.get()));
        assertNull(switchingPlayerReceived.get());

        notifications.localJoin = false;
        newServerReceived.set(null);
        permittedReceived.set(null);
        delivery.deliver(new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.CHANNEL,
                "join", "join", "presence", ChannelScope.SERVER,
                switchingPlayerId, "Bob", "survival", "", "", "fallback",
                Set.of(), null, Set.of()).asJoinMessage());

        assertNull(newServerReceived.get());
        assertNull(permittedReceived.get());
        assertNull(switchingPlayerReceived.get());
    }

    @Test
    void coordinatePlaceholdersRenderInBroadcastsWithConfiguredFormat() {
        AtomicReference<Component> received = new AtomicReference<>();
        Player viewer = playerOnServer(UUID.randomUUID(), "beta", received);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class},
                (ignored, method, arguments) -> method.getName().equals("getAllPlayers")
                        ? List.of(viewer)
                        : defaultValue(method.getReturnType()));
        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "<message>")));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.broadcast = "<message>";
        AppConfig.Mentions mentions = new AppConfig.Mentions();
        mentions.enabled = false;
        AppConfig.Coordinates coordinateConfig = new AppConfig.Coordinates();
        coordinateConfig.format = "<gold><world> <x>/<y>/<z> on <server></gold>";
        VelocityDeliveryService delivery = new VelocityDeliveryService(
                proxy, channels, null, formats, new AppConfig.Notifications(), mentions,
                new AppConfig.ItemLinks(), coordinateConfig, new PlayerFormattingService(),
                null, null, 50, Duration.ofSeconds(30));
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), RouteKind.BROADCAST,
                "broadcast", "Broadcast", "broadcast", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "",
                "Meet at [coords] or <coords>", Set.of(), null, Set.of())
                .withCoordinates(new Coordinates(-12, 64, 345, "minecraft:overworld"));

        delivery.deliver(message);

        assertEquals("Meet at minecraft:overworld -12/64/345 on alpha or "
                + "minecraft:overworld -12/64/345 on alpha", plain(received.get()));
    }

    private static Player playerOnServer(
            UUID playerId,
            String serverName,
            AtomicReference<Component> received
    ) {
        return playerOnServer(playerId, serverName, received, false);
    }

    private static Player playerOnServer(
            UUID playerId,
            String serverName,
            AtomicReference<Component> received,
            boolean globalPlayerNotifications
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
                    case "hasPermission" -> globalPlayerNotifications
                            && arguments != null
                            && Permissions.BYPASS_GLOBAL_PLAYER_NOTIFICATIONS
                            .equals(arguments[0]);
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
