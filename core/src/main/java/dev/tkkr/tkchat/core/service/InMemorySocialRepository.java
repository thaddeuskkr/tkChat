package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupLeaveResult;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySocialRepository implements SocialRepository {
    private record StoredGroup(Group group, String passwordHash) {
    }

    private record Invite(UUID groupId, UUID inviterId, UUID invitedId, Instant expiresAt) {
    }

    private final Map<UUID, PlayerSettings> settings = new ConcurrentHashMap<>();
    private final Map<UUID, String> knownPlayerNames = new ConcurrentHashMap<>();
    private final Map<UUID, StoredGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, UUID> groupNames = new ConcurrentHashMap<>();
    private final Map<UUID, GroupMembership> memberships = new ConcurrentHashMap<>();
    private final Map<String, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    private volatile String defaultChannel;

    public InMemorySocialRepository() {
        this("global");
    }

    public InMemorySocialRepository(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    @Override
    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    @Override
    public CompletionStage<PlayerSettings> settings(UUID playerId, String requestedDefaultChannel) {
        return completed(settings.computeIfAbsent(playerId,
                id -> new PlayerSettings(id, requestedDefaultChannel, true)));
    }

    @Override
    public CompletionStage<Void> recordPlayerName(UUID playerId, String username) {
        knownPlayerNames.put(playerId, username);
        return completed(null);
    }

    @Override
    public CompletionStage<Map<UUID, String>> playerNames(Set<UUID> playerIds) {
        Map<UUID, String> result = new java.util.HashMap<>();
        playerIds.forEach(playerId -> {
            String username = knownPlayerNames.get(playerId);
            if (username != null) {
                result.put(playerId, username);
            }
        });
        return completed(Map.copyOf(result));
    }

    @Override
    public CompletionStage<Void> setActiveChannel(UUID playerId, String channelId) {
        settings.compute(playerId, (id, previous) -> new PlayerSettings(id, channelId,
                previous == null || previous.directMessagesEnabled()));
        return completed(null);
    }

    @Override
    public synchronized CompletionStage<Void> setActiveGroupChannel(UUID playerId, UUID groupId) {
        GroupMembership membership = memberships.get(playerId);
        if (membership == null || !membership.group().id().equals(groupId)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Player is not in that group"));
        }
        settings.compute(playerId, (id, previous) -> new PlayerSettings(
                id, GroupChannels.id(groupId),
                previous == null || previous.directMessagesEnabled()));
        return completed(null);
    }

    @Override
    public CompletionStage<Boolean> compareAndSetActiveChannel(
            UUID playerId,
            String expectedChannelId,
            String channelId
    ) {
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        settings.computeIfPresent(playerId, (id, previous) -> {
            if (!previous.activeChannel().equals(expectedChannelId)) {
                return previous;
            }
            changed.set(true);
            return new PlayerSettings(id, channelId, previous.directMessagesEnabled());
        });
        return completed(changed.get());
    }

    @Override
    public CompletionStage<Void> setDirectMessagesEnabled(UUID playerId, boolean enabled) {
        settings.compute(playerId, (id, previous) -> new PlayerSettings(id,
                previous == null ? defaultChannel : previous.activeChannel(), enabled));
        return completed(null);
    }

    @Override
    public synchronized CompletionStage<Group> createGroup(
            UUID ownerId,
            String name,
            GroupVisibility visibility,
            String password
    ) {
        if (memberships.containsKey(ownerId)) {
            return failed(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
        }
        String normalizedName = GroupNames.normalize(name);
        if (groupNames.containsKey(normalizedName)) {
            return failed(GroupFailure.NAME_TAKEN, "A group with that name already exists");
        }
        String passwordHash = visibility == GroupVisibility.PRIVATE ? GroupPasswords.hash(password) : null;
        Group group = new Group(UUID.randomUUID(), name, normalizedName, ownerId, visibility,
                passwordHash != null);
        groups.put(group.id(), new StoredGroup(group, passwordHash));
        groupNames.put(normalizedName, group.id());
        memberships.put(ownerId, new GroupMembership(group, ownerId, GroupRole.OWNER));
        return completed(group);
    }

    @Override
    public CompletionStage<Optional<Group>> groupByName(String name) {
        StoredGroup stored = storedByName(name);
        return completed(Optional.ofNullable(stored).map(StoredGroup::group));
    }

    @Override
    public CompletionStage<List<Group>> listGroups(boolean includePrivate) {
        return completed(groups.values().stream()
                .map(StoredGroup::group)
                .filter(group -> includePrivate || group.visibility() == GroupVisibility.PUBLIC)
                .sorted(Comparator.comparing(Group::normalizedName))
                .toList());
    }

    @Override
    public CompletionStage<Optional<GroupMembership>> groupForMember(UUID playerId) {
        return completed(Optional.ofNullable(memberships.get(playerId)));
    }

    @Override
    public CompletionStage<Set<UUID>> groupMembers(UUID groupId) {
        return completed(memberships.values().stream()
                .filter(membership -> membership.group().id().equals(groupId))
                .map(GroupMembership::memberId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    public synchronized CompletionStage<Set<UUID>> groupInvitees(UUID groupId, Instant now) {
        invites.entrySet().removeIf(entry -> entry.getValue().groupId().equals(groupId)
                && !entry.getValue().expiresAt().isAfter(now));
        return completed(invites.values().stream()
                .filter(invite -> invite.groupId().equals(groupId))
                .map(Invite::invitedId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    public synchronized CompletionStage<Void> invite(
            UUID groupId,
            UUID inviterId,
            UUID invitedId,
        Instant expiresAt
    ) {
        GroupMembership inviter = memberships.get(inviterId);
        if (inviter == null || !inviter.group().id().equals(groupId)) {
            return failed(GroupFailure.NOT_MEMBER, "Inviter is not a member of that group");
        }
        if (memberships.containsKey(invitedId)) {
            return failed(GroupFailure.ALREADY_MEMBER, "That player is already in a group");
        }
        invites.put(inviteId(invitedId, groupId), new Invite(groupId, inviterId, invitedId, expiresAt));
        return completed(null);
    }

    @Override
    public synchronized CompletionStage<Group> acceptInvite(UUID invitedId, String groupName, Instant now) {
        StoredGroup stored = storedByName(groupName);
        if (stored == null) {
            return failed(GroupFailure.NOT_FOUND, "That group does not exist");
        }
        Invite invite = validInvite(invitedId, stored.group().id(), now);
        if (invite == null) {
            return failed(GroupFailure.INVITE_MISSING_OR_EXPIRED, "Invite is missing or expired");
        }
        return addMember(invitedId, stored, invite);
    }

    @Override
    public synchronized CompletionStage<Group> joinGroup(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            Instant now
    ) {
        StoredGroup stored = storedByName(groupName);
        if (stored == null) {
            return failed(GroupFailure.NOT_FOUND, "That group does not exist");
        }
        if (memberships.containsKey(playerId)) {
            return failed(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
        }
        Invite invite = validInvite(playerId, stored.group().id(), now);
        boolean allowed = stored.group().visibility() == GroupVisibility.PUBLIC || bypass || invite != null;
        if (!allowed && stored.passwordHash() == null) {
            return failed(GroupFailure.INVITE_REQUIRED, "This private group requires an invitation");
        }
        if (!allowed && !GroupPasswords.matches(password, stored.passwordHash())) {
            return failed(GroupFailure.INVALID_PASSWORD, "The group password is incorrect");
        }
        return addMember(playerId, stored, invite);
    }

    private CompletionStage<Group> addMember(UUID playerId, StoredGroup stored, Invite invite) {
        if (memberships.containsKey(playerId)) {
            return failed(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
        }
        Group group = stored.group();
        memberships.put(playerId, new GroupMembership(group, playerId, GroupRole.MEMBER));
        if (invite != null) {
            invites.remove(inviteId(playerId, group.id()));
        }
        return completed(group);
    }

    @Override
    public synchronized CompletionStage<GroupLeaveResult> leaveGroup(UUID playerId) {
        GroupMembership membership = memberships.get(playerId);
        if (membership == null) {
            return failed(GroupFailure.NOT_MEMBER, "Player is not in a group");
        }
        Group group = membership.group();
        Set<UUID> affected;
        boolean deleted = membership.role() == GroupRole.OWNER;
        if (deleted) {
            affected = memberships.values().stream()
                    .filter(value -> value.group().id().equals(group.id()))
                    .map(GroupMembership::memberId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            affected.forEach(memberships::remove);
            groups.remove(group.id());
            groupNames.remove(group.normalizedName());
            invites.entrySet().removeIf(entry -> entry.getValue().groupId().equals(group.id()));
        } else {
            affected = Set.of(playerId);
            memberships.remove(playerId);
        }
        resetGroupChannel(affected, group.id());
        return completed(new GroupLeaveResult(group, affected, deleted));
    }

    private void resetGroupChannel(Set<UUID> players, UUID groupId) {
        String groupChannel = GroupChannels.id(groupId);
        players.forEach(playerId -> settings.computeIfPresent(playerId, (id, previous) ->
                previous.activeChannel().equals(groupChannel)
                        ? new PlayerSettings(id, defaultChannel, previous.directMessagesEnabled())
                        : previous));
    }

    @Override
    public CompletionStage<Boolean> isIgnoring(UUID ownerId, UUID ignoredId) {
        return completed(ignores.getOrDefault(ownerId, Set.of()).contains(ignoredId));
    }

    @Override
    public CompletionStage<Set<UUID>> ignoredPlayers(UUID ownerId) {
        return completed(Set.copyOf(ignores.getOrDefault(ownerId, Set.of())));
    }

    @Override
    public CompletionStage<Void> setIgnoring(UUID ownerId, UUID ignoredId, boolean ignored) {
        ignores.compute(ownerId, (id, existing) -> {
            Set<UUID> copy = ConcurrentHashMap.newKeySet();
            if (existing != null) {
                copy.addAll(existing);
            }
            if (ignored) {
                copy.add(ignoredId);
            } else {
                copy.remove(ignoredId);
            }
            return copy;
        });
        return completed(null);
    }

    private StoredGroup storedByName(String name) {
        UUID groupId = groupNames.get(GroupNames.normalize(name));
        return groupId == null ? null : groups.get(groupId);
    }

    private Invite validInvite(UUID playerId, UUID groupId, Instant now) {
        Invite invite = invites.get(inviteId(playerId, groupId));
        if (invite == null || !invite.expiresAt().isAfter(now)) {
            if (invite != null) {
                invites.remove(inviteId(playerId, groupId));
            }
            return null;
        }
        return invite;
    }

    private static String inviteId(UUID invitedId, UUID groupId) {
        return invitedId + ":" + groupId;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(GroupFailure failure, String message) {
        return CompletableFuture.failedFuture(new GroupException(failure, message));
    }
}
