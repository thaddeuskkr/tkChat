package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupLeaveResult;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface SocialRepository extends AutoCloseable {
    void setDefaultChannel(String defaultChannel);

    CompletionStage<PlayerSettings> settings(UUID playerId, String defaultChannel);

    CompletionStage<Void> setActiveChannel(UUID playerId, String channelId);

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
}
