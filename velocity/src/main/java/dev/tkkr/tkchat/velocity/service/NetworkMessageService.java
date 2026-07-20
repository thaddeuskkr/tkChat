package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.MessageTransport;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class NetworkMessageService {
    private static final UUID CONSOLE_ID = new UUID(0, 0);

    private final MessageTransport transport;

    public NetworkMessageService(MessageTransport transport) {
        this.transport = transport;
    }

    public CompletionStage<Void> broadcast(CommandSource source, String content) {
        return transport.publish(message(source, RouteKind.BROADCAST,
                "broadcast", "Broadcast", "broadcast", ChannelScope.GLOBAL, content));
    }

    public CompletionStage<Void> clearChat(CommandSource source, ChannelDefinition channel) {
        return transport.publish(message(source, RouteKind.CHAT_CLEAR,
                "chat_clear:" + channel.id(), channel.displayName(),
                channel.id(), channel.scope(), ""));
    }

    private static ApprovedMessage message(
            CommandSource source,
            RouteKind kind,
            String routeId,
            String routeDisplayName,
            String channelId,
            ChannelScope channelScope,
            String content
    ) {
        Player player = source instanceof Player value ? value : null;
        UUID senderId = player == null ? CONSOLE_ID : player.getUniqueId();
        String senderName = player == null ? "Console" : player.getUsername();
        String serverId = player == null
                ? "proxy"
                : player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse("unknown");
        return new ApprovedMessage(
                UUID.randomUUID(), Instant.now(), kind, routeId, routeDisplayName,
                channelId, channelScope, senderId, senderName, serverId,
                "", "", content, Set.of(), null, Set.of());
    }
}
