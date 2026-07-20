package dev.tkkr.tkchat.velocity.state;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.PlayerSettings;
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

public final class PlayerStateService {
    private record Loaded(PlayerSettings settings, Optional<Group> group, Set<UUID> ignoredPlayers) {
    }

    private final SocialRepository repository;
    private volatile ChannelRegistry channels;
    private volatile String defaultChannel;
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Group> groupCache = new ConcurrentHashMap<>();
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
            Group group = groupCache.get(playerId);
            boolean validGroupChannel = group != null
                    && GroupChannels.id(group.id()).equals(settings.activeChannel());
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
        CompletionStage<Loaded> loadedStage = repository.settings(playerId, defaultChannel)
                .thenCombine(repository.groupForMember(playerId),
                        (settings, membership) -> new Loaded(
                                settings, membership.map(value -> value.group()), Set.of()))
                .thenCombine(repository.ignoredPlayers(playerId),
                        (loaded, ignored) -> new Loaded(loaded.settings(), loaded.group(), ignored));
        return loadedStage
                .thenCompose(loaded -> {
                    loaded.group().ifPresentOrElse(
                            group -> groupCache.put(playerId, group),
                            () -> groupCache.remove(playerId));
                    ignoreCache.put(playerId, loaded.ignoredPlayers());
                    PlayerSettings settings = loaded.settings();
                    boolean staticChannel = channels.find(settings.activeChannel()).isPresent();
                    boolean validGroupChannel = loaded.group()
                            .map(group -> GroupChannels.id(group.id()).equals(settings.activeChannel()))
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
        Group group = groupCache.get(playerId);
        return group != null && GroupChannels.id(group.id()).equals(active)
                ? active
                : defaultChannel;
    }

    public String activeDisplayName(UUID playerId) {
        String active = activeChannel(playerId);
        Optional<UUID> groupId = GroupChannels.groupId(active);
        if (groupId.isPresent()) {
            Group group = groupCache.get(playerId);
            if (group != null && group.id().equals(groupId.get())) {
                return group.name();
            }
        }
        return channels.find(active).map(channel -> channel.displayName()).orElse(active);
    }

    public Optional<Group> group(UUID playerId) {
        return Optional.ofNullable(groupCache.get(playerId));
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

    public void setGroupMembership(UUID playerId, Group group) {
        groupCache.put(playerId, group);
    }

    public CompletionStage<Void> clearGroupMembership(UUID playerId, UUID groupId) {
        PlayerSettings previousSettings = cache.get(playerId);
        boolean groupWasActive = previousSettings != null
                && GroupChannels.id(groupId).equals(previousSettings.activeChannel());
        groupCache.computeIfPresent(playerId, (id, group) -> group.id().equals(groupId) ? null : group);
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
        Group membership = groupCache.get(playerId);
        if (membership == null || !membership.id().equals(group.id())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player is not in that group"));
        }
        return persistActiveChannel(playerId, GroupChannels.id(group.id()));
    }

    private CompletionStage<Void> persistActiveChannel(UUID playerId, String channelId) {
        return repository.setActiveChannel(playerId, channelId).thenRun(() ->
                cache.compute(playerId, (id, previous) -> new PlayerSettings(id, channelId,
                        previous == null || previous.directMessagesEnabled())));
    }

    public void remove(UUID playerId) {
        cache.remove(playerId);
        groupCache.remove(playerId);
        ignoreCache.remove(playerId);
    }
}
