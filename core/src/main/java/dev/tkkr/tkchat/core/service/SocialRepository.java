package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupLeaveResult;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.model.PlayerSocialState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public interface SocialRepository extends AutoCloseable {
    void setDefaultChannel(String defaultChannel);

    CompletionStage<PlayerSettings> settings(UUID playerId, String defaultChannel);

    default CompletionStage<PlayerSocialState> loadPlayerState(
            UUID playerId,
            String defaultChannel
    ) {
        CompletionStage<PlayerSettings> settingsStage = settings(playerId, defaultChannel);
        CompletionStage<Optional<GroupMembership>> membershipStage = groupForMember(playerId);
        CompletionStage<Set<UUID>> ignoredStage = ignoredPlayers(playerId);
        CompletionStage<Set<UUID>> membersStage = membershipStage.thenCompose(membership ->
                membership.isEmpty()
                        ? CompletableFuture.completedFuture(Set.of())
                        : groupMembers(membership.get().group().id()));
        return settingsStage.thenCombine(membershipStage, StateParts::new)
                .thenCombine(ignoredStage,
                        (parts, ignored) -> new StateParts(
                                parts.settings(), parts.membership(), ignored))
                .thenCombine(membersStage,
                        (parts, members) -> new PlayerSocialState(
                                parts.settings(), parts.membership(), parts.ignored(), members));
    }

    default CompletionStage<PlayerSocialState> loadPlayerState(
            UUID playerId,
            String username,
            String defaultChannel
    ) {
        return recordPlayerName(playerId, username)
                .thenCompose(ignored -> loadPlayerState(playerId, defaultChannel));
    }

    CompletionStage<Void> recordPlayerName(UUID playerId, String username);

    CompletionStage<Map<UUID, String>> playerNames(Set<UUID> playerIds);

    CompletionStage<Void> setActiveChannel(UUID playerId, String channelId);

    default CompletionStage<Void> setActiveGroupChannel(UUID playerId, UUID groupId) {
        return groupForMember(playerId).thenCompose(membership -> {
            if (membership.isEmpty() || !membership.get().group().id().equals(groupId)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Player is not in that group"));
            }
            return setActiveChannel(playerId, GroupChannels.id(groupId));
        });
    }

    CompletionStage<Boolean> compareAndSetActiveChannel(
            UUID playerId,
            String expectedChannelId,
            String channelId
    );

    CompletionStage<Void> setDirectMessagesEnabled(UUID playerId, boolean enabled);

    CompletionStage<Group> createGroup(
            UUID ownerId,
            String name,
            GroupVisibility visibility,
            String password
    );

    CompletionStage<Optional<Group>> groupByName(String name);

    CompletionStage<List<Group>> listGroups(boolean includePrivate);

    CompletionStage<Optional<GroupMembership>> groupForMember(UUID playerId);

    CompletionStage<Set<UUID>> groupMembers(UUID groupId);

    CompletionStage<Set<UUID>> groupInvitees(UUID groupId, Instant now);

    CompletionStage<Void> invite(UUID groupId, UUID inviterId, UUID invitedId, Instant expiresAt);

    CompletionStage<Group> acceptInvite(UUID invitedId, String groupName, Instant now);

    CompletionStage<Group> joinGroup(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            Instant now
    );

    CompletionStage<GroupLeaveResult> leaveGroup(UUID playerId);

    CompletionStage<Boolean> isIgnoring(UUID ownerId, UUID ignoredId);

    CompletionStage<Set<UUID>> ignoredPlayers(UUID ownerId);

    CompletionStage<Void> setIgnoring(UUID ownerId, UUID ignoredId, boolean ignored);

    @Override
    default void close() {
    }

    record StateParts(
            PlayerSettings settings,
            Optional<GroupMembership> membership,
            Set<UUID> ignored
    ) {
        StateParts(PlayerSettings settings, Optional<GroupMembership> membership) {
            this(settings, membership, Set.of());
        }
    }
}
