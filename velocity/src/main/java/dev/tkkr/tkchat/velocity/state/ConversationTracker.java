package dev.tkkr.tkchat.velocity.state;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConversationTracker {
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public void recordOutgoing(UUID senderId, UUID recipientId) {
        lastPartner.put(senderId, recipientId);
    }

    public void recordIncoming(UUID recipientId, UUID senderId) {
        lastPartner.put(recipientId, senderId);
    }

    public Optional<UUID> partner(UUID playerId) {
        return Optional.ofNullable(lastPartner.get(playerId));
    }

    public void remove(UUID playerId) {
        lastPartner.remove(playerId);
    }
}
