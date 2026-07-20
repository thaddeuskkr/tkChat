package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.PlayerSettings;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Supplies the small piece of social state required while routing a chat message. */
public interface ChatStateProvider {
    record DirectState(PlayerSettings settings, boolean ignoringSender) {
    }

    record GroupState(Group group, Set<UUID> members) {
        public GroupState {
            members = Set.copyOf(members);
        }
    }

    CompletionStage<DirectState> directState(
            UUID recipientId,
            UUID senderId,
            String defaultChannel
    );

    CompletionStage<Optional<GroupState>> groupState(UUID playerId);
}
