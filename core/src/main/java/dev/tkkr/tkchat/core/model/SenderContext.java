package dev.tkkr.tkchat.core.model;

import java.net.InetAddress;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SenderContext(
        UUID playerId,
        String playerName,
        String serverId,
        InetAddress address,
        Map<String, String> backendContext,
        boolean bypassRateLimit
) {
    public SenderContext {
        playerId = Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        serverId = Objects.requireNonNull(serverId, "serverId");
        address = Objects.requireNonNull(address, "address");
        backendContext = Map.copyOf(backendContext == null ? Map.of() : backendContext);
    }
}
