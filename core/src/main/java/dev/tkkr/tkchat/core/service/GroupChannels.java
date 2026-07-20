package dev.tkkr.tkchat.core.service;

import java.util.Optional;
import java.util.UUID;

public final class GroupChannels {
    private static final String PREFIX = "group:";

    private GroupChannels() {
    }

    public static String id(UUID groupId) {
        return PREFIX + groupId;
    }

    public static Optional<UUID> groupId(String channelId) {
        if (channelId == null || !channelId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(channelId.substring(PREFIX.length())));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
