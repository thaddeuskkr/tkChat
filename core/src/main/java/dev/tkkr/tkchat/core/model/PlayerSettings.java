package dev.tkkr.tkchat.core.model;

import java.util.Objects;
import java.util.UUID;

public record PlayerSettings(UUID playerId, String activeChannel, boolean directMessagesEnabled) {
    public PlayerSettings {
        playerId = Objects.requireNonNull(playerId, "playerId");
        activeChannel = Objects.requireNonNull(activeChannel, "activeChannel");
    }
}
