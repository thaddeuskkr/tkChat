package dev.tkkr.tkchat.velocity.state;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.service.ChatStateProvider;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.core.service.SocialRepository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStateService implements ChatStateProvider {
    private record Loaded(
            PlayerSettings settings,
            Optional<GroupMembership> membership,
            Set<UUID> ignoredPlayers,
            Set<UUID> groupMembers
    ) {
    }

    private final SocialRepository repository;
    private volatile ChannelRegistry channels;
    private volatile String defaultChannel;
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();
    private final Map<UUID, GroupMembership> groupCache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> groupMembersCache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoreCache = new ConcurrentHashMap<>();

    public PlayerStateService(SocialRepository repository, ChannelRegistry channels, String defaultChannel) {
        this.repository = repository;
        this.channels = channels;
        this.defaultChannel = defaultChannel;
    }

    public CompletionStage<Void> reconfigure(ChannelRegistry channels, String defaultChannel) {
        this.channels = channels;
        this.defaultChannel = defaultChannel;
        repository.setDefaultChannel(defaultChannel);

        java.util.ArrayList<CompletableFuture<Void>> repairs = new java.util.ArrayList<>();
        cache.forEach((playerId, settings) -> {
            boolean staticChannel = channels.find(settings.activeChannel()).isPresent();
            GroupMembership membership = groupCache.get(playerId);
            boolean validGroupChannel = membership != null
                    && GroupChannels.id(membership.group().id()).equals(settings.activeChannel());
            if (staticChannel || validGroupChannel) {
                return;
            }
            repairs.add(repository.setActiveChannel(playerId, defaultChannel)
                    .thenRun(() -> cache.put(playerId, new PlayerSettings(
                            playerId, defaultChannel, settings.directMessagesEnabled())))
                    .toCompletableFuture());
        });
        return CompletableFuture.allOf(repairs.toArray(CompletableFuture[]::new));
    }

    public CompletionStage<PlayerSettings> load(UUID playerId) {
        CompletionStage<PlayerSettings> settingsStage = repository.settings(playerId, defaultChannel);
        CompletionStage<Optional<GroupMembership>> membershipStage = repository.groupForMember(playerId);
        CompletionStage<Set<UUID>> ignoredStage = repository.ignoredPlayers(playerId);
        CompletionStage<Set<UUID>> membersStage = membershipStage.thenCompose(membership ->
                membership.isEmpty()
                        ? CompletableFuture.completedFuture(Set.of())
                        : repository.groupMembers(membership.get().group().id()));
        CompletionStage<Loaded> loadedStage = settingsStage
                .thenCombine(membershipStage,
                        (settings, membership) -> new Loaded(settings, membership, Set.of(), Set.of()))
                .thenCombine(ignoredStage,
                        (loaded, ignored) -> new Loaded(
                                loaded.settings(), loaded.membership(), ignored, Set.of()))
                .thenCombine(membersStage,
                        (loaded, members) -> new Loaded(
                                loaded.settings(), loaded.membership(), loaded.ignoredPlayers(), members));
        return loadedStage
                .thenCompose(loaded -> {
                    loaded.membership().ifPresentOrElse(
                            membership -> {
                                groupCache.put(playerId, membership);
                                groupMembersCache.put(membership.group().id(), loaded.groupMembers());
                            },
                            () -> groupCache.remove(playerId));
                    ignoreCache.put(playerId, loaded.ignoredPlayers());
                    PlayerSettings settings = loaded.settings();
                    boolean staticChannel = channels.find(settings.activeChannel()).isPresent();
                    boolean validGroupChannel = loaded.membership()
                            .map(membership -> GroupChannels.id(membership.group().id())
                                    .equals(settings.activeChannel()))
                            .orElse(false);
                    PlayerSettings valid = staticChannel || validGroupChannel
                            ? settings
                            : new PlayerSettings(playerId, defaultChannel, settings.directMessagesEnabled());
                    CompletionStage<Void> repair = valid.activeChannel().equals(settings.activeChannel())
                            ? CompletableFuture.completedFuture(null)
                            : repository.setActiveChannel(playerId, defaultChannel);
                    return repair.thenApply(ignored -> {
                        cache.put(playerId, valid);
                        return valid;
                    });
                });
    }

    public String activeChannel(UUID playerId) {
        String active = cache.getOrDefault(playerId,
                new PlayerSettings(playerId, defaultChannel, true)).activeChannel();
        if (channels.find(active).isPresent()) {
            return active;
        }
        GroupMembership membership = groupCache.get(playerId);
        return membership != null && GroupChannels.id(membership.group().id()).equals(active)
                ? active
                : defaultChannel;
    }

    public String activeDisplayName(UUID playerId) {
        String active = activeChannel(playerId);
        Optional<UUID> groupId = GroupChannels.groupId(active);
        if (groupId.isPresent()) {
            GroupMembership membership = groupCache.get(playerId);
            if (membership != null && membership.group().id().equals(groupId.get())) {
                return membership.group().name();
            }
        }
        return channels.find(active).map(channel -> channel.displayName()).orElse(active);
    }

    public Optional<Group> group(UUID playerId) {
        return Optional.ofNullable(groupCache.get(playerId)).map(GroupMembership::group);
    }

    public boolean isIgnoring(UUID ownerId, UUID ignoredId) {
        return ignoreCache.getOrDefault(ownerId, Set.of()).contains(ignoredId);
    }

    public CompletionStage<Boolean> setIgnoring(UUID ownerId, UUID ignoredId, boolean ignored) {
        return repository.setIgnoring(ownerId, ignoredId, ignored).thenApply(nothing -> {
            ignoreCache.compute(ownerId, (id, current) -> {
                java.util.HashSet<UUID> updated = new java.util.HashSet<>(
                        current == null ? Set.of() : current);
                if (ignored) {
                    updated.add(ignoredId);
                } else {
                    updated.remove(ignoredId);
                }
                return Set.copyOf(updated);
            });
            return ignored;
        });
    }

    public void setGroupMembership(UUID playerId, Group group, GroupRole role) {
        groupCache.put(playerId, new GroupMembership(group, playerId, role));
        groupMembersCache.compute(group.id(), (ignored, current) -> {
            java.util.HashSet<UUID> updated = new java.util.HashSet<>(
                    current == null ? Set.of() : current);
            updated.add(playerId);
            return Set.copyOf(updated);
        });
    }

    public CompletionStage<Void> clearGroupMembership(UUID playerId, UUID groupId) {
        PlayerSettings previousSettings = cache.get(playerId);
        boolean groupWasActive = previousSettings != null
                && GroupChannels.id(groupId).equals(previousSettings.activeChannel());
        groupCache.computeIfPresent(playerId,
                (id, membership) -> membership.group().id().equals(groupId) ? null : membership);
        groupMembersCache.computeIfPresent(groupId, (id, current) -> {
            java.util.HashSet<UUID> updated = new java.util.HashSet<>(current);
            updated.remove(playerId);
            return updated.isEmpty() ? null : Set.copyOf(updated);
        });
        if (!groupWasActive) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.setActiveChannel(playerId, defaultChannel).thenRun(() ->
                cache.compute(playerId, (id, previous) -> new PlayerSettings(id, defaultChannel,
                        previous == null || previous.directMessagesEnabled())));
    }

    public CompletionStage<Void> setActiveChannel(UUID playerId, String channelId) {
        if (channels.find(channelId).isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown channel " + channelId));
        }
        return persistActiveChannel(playerId, channelId);
    }

    public CompletionStage<Void> setActiveGroup(UUID playerId, Group group) {
        GroupMembership membership = groupCache.get(playerId);
        if (membership == null || !membership.group().id().equals(group.id())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player is not in that group"));
        }
        return persistActiveChannel(playerId, GroupChannels.id(group.id()));
    }

    private CompletionStage<Void> persistActiveChannel(UUID playerId, String channelId) {
        return repository.setActiveChannel(playerId, channelId).thenRun(() ->
                cache.compute(playerId, (id, previous) -> new PlayerSettings(id, channelId,
                        previous == null || previous.directMessagesEnabled())));
    }

    public CompletionStage<Boolean> toggleDirectMessages(UUID playerId) {
        PlayerSettings cached = cache.get(playerId);
        CompletionStage<PlayerSettings> current = cached == null
                ? repository.settings(playerId, defaultChannel)
                : CompletableFuture.completedFuture(cached);
        return current.thenCompose(settings -> {
            boolean enabled = !settings.directMessagesEnabled();
            return repository.setDirectMessagesEnabled(playerId, enabled).thenApply(ignored -> {
                cache.put(playerId, new PlayerSettings(
                        playerId, settings.activeChannel(), enabled));
                return enabled;
            });
        });
    }

    @Override
    public CompletionStage<DirectState> directState(
            UUID recipientId,
            UUID senderId,
            String requestedDefaultChannel
    ) {
        PlayerSettings cachedSettings = cache.get(recipientId);
        Set<UUID> cachedIgnores = ignoreCache.get(recipientId);
        if (cachedSettings != null && cachedIgnores != null) {
            return CompletableFuture.completedFuture(new DirectState(
                    cachedSettings, cachedIgnores.contains(senderId)));
        }
        return repository.settings(recipientId, defaultChannel)
                .thenCombine(repository.isIgnoring(recipientId, senderId), DirectState::new);
    }

    @Override
    public CompletionStage<Optional<GroupState>> groupState(UUID playerId) {
        GroupMembership membership = groupCache.get(playerId);
        if (membership != null) {
            Set<UUID> members = groupMembersCache.get(membership.group().id());
            if (members != null) {
                return CompletableFuture.completedFuture(Optional.of(
                        new GroupState(membership.group(), members)));
            }
        }
        return repository.groupForMember(playerId).thenCompose(stored -> {
            if (stored.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Group group = stored.get().group();
            return repository.groupMembers(group.id()).thenApply(members -> {
                groupCache.put(playerId, stored.get());
                groupMembersCache.put(group.id(), members);
                return Optional.of(new GroupState(group, members));
            });
        });
    }

    public void remove(UUID playerId) {
        cache.remove(playerId);
        groupCache.remove(playerId);
        ignoreCache.remove(playerId);
    }
}
