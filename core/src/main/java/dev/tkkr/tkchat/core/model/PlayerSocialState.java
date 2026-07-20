package dev.tkkr.tkchat.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record PlayerSocialState(
        PlayerSettings settings,
        Optional<GroupMembership> groupMembership,
        Set<UUID> ignoredPlayers,
        Set<UUID> groupMembers
) {
    public PlayerSocialState {
        settings = Objects.requireNonNull(settings, "settings");
        groupMembership = Objects.requireNonNull(groupMembership, "groupMembership");
        ignoredPlayers = Set.copyOf(ignoredPlayers);
        groupMembers = Set.copyOf(groupMembers);
    }
}
