package dev.tkkr.tkchat.velocity.state;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SocialSpyService {
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public boolean toggle(UUID playerId) {
        if (enabled.remove(playerId)) {
            return false;
        }
        enabled.add(playerId);
        return true;
    }

    public boolean set(UUID playerId, boolean value) {
        if (value) {
            enabled.add(playerId);
        } else {
            enabled.remove(playerId);
        }
        return value;
    }

    public boolean enabled(UUID playerId) {
        return enabled.contains(playerId);
    }

    public void remove(UUID playerId) {
        enabled.remove(playerId);
    }
}
