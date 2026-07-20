package dev.tkkr.tkchat.velocity.state;

import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.model.PlayerSocialState;
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
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerStateService implements ChatStateProvider {
    private static final class Session {
        private long revision;
        private boolean loaded;
    }

    private final SocialRepository repository;
    private volatile ChannelRegistry channels;
    private volatile String defaultChannel;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();
    private final Map<UUID, GroupMembership> groupCache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> groupMembersCache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignoreCache = new ConcurrentHashMap<>();
    private final AtomicLong membershipRevision = new AtomicLong();

    public PlayerStateService(SocialRepository repository, ChannelRegistry channels, String defaultChannel) {
        this.repository = repository;
        this.channels = channels;
        this.defaultChannel = defaultChannel;
    }

    public void activate(UUID playerId) {
        sessions.put(playerId, new Session());
        cache.remove(playerId);
        groupCache.remove(playerId);
        ignoreCache.remove(playerId);
    }

    @Override
    public boolean ready(UUID playerId) {
        return isLoaded(playerId);
    }

    public boolean isLoaded(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            return sessions.get(playerId) == session && session.loaded;
        }
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
            repairs.add(repository.compareAndSetActiveChannel(
                            playerId, settings.activeChannel(), defaultChannel)
                    .thenAccept(changed -> {
                        if (changed) {
                            updateSession(playerId, () -> cache.put(playerId, new PlayerSettings(
                                    playerId, defaultChannel, settings.directMessagesEnabled())));
                        }
                    })
                    .toCompletableFuture());
        });
        return CompletableFuture.allOf(repairs.toArray(CompletableFuture[]::new));
    }

    public CompletionStage<PlayerSettings> load(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Player session ended before state loading began"));
        }
        long revision;
        synchronized (session) {
            revision = session.revision;
        }
        long groupRevision = membershipRevision.get();
        return repository.loadPlayerState(playerId, defaultChannel)
                .thenCompose(snapshot -> applyLoadedState(
                        playerId, session, revision, groupRevision, snapshot));
    }

    private CompletionStage<PlayerSettings> applyLoadedState(
            UUID playerId,
            Session session,
            long expectedRevision,
            long expectedGroupRevision,
            PlayerSocialState snapshot
    ) {
        PlayerSettings settings = snapshot.settings();
        boolean staticChannel = channels.find(settings.activeChannel()).isPresent();
        boolean validGroupChannel = snapshot.groupMembership()
                .map(membership -> GroupChannels.id(membership.group().id())
                        .equals(settings.activeChannel()))
                .orElse(false);
        PlayerSettings valid = staticChannel || validGroupChannel
                ? settings
                : new PlayerSettings(playerId, defaultChannel, settings.directMessagesEnabled());
        CompletionStage<Boolean> repair = valid.activeChannel().equals(settings.activeChannel())
                ? CompletableFuture.completedFuture(true)
                : repository.compareAndSetActiveChannel(
                        playerId, settings.activeChannel(), defaultChannel);
        return repair.thenApply(ignored -> {
            synchronized (session) {
                if (sessions.get(playerId) != session || session.revision != expectedRevision) {
                    throw new IllegalStateException(
                            "Player state changed while a database load was in progress");
                }
                snapshot.groupMembership().ifPresentOrElse(
                        membership -> {
                            groupCache.put(playerId, membership);
                            if (membershipRevision.get() == expectedGroupRevision) {
                                groupMembersCache.put(
                                        membership.group().id(), snapshot.groupMembers());
                            }
                        },
                        () -> groupCache.remove(playerId));
                ignoreCache.put(playerId, snapshot.ignoredPlayers());
                cache.put(playerId, valid);
                session.loaded = true;
                return valid;
            }
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
        if (!isLoaded(ownerId)) {
            return notReady();
        }
        return repository.setIgnoring(ownerId, ignoredId, ignored).thenApply(nothing -> {
            updateSession(ownerId, () -> ignoreCache.compute(ownerId, (id, current) -> {
                java.util.HashSet<UUID> updated = new java.util.HashSet<>(
                        current == null ? Set.of() : current);
                if (ignored) {
                    updated.add(ignoredId);
                } else {
                    updated.remove(ignoredId);
                }
                return Set.copyOf(updated);
            }));
            return ignored;
        });
    }

    public void setGroupMembership(UUID playerId, Group group, GroupRole role) {
        if (!isLoaded(playerId)) {
            return;
        }
        membershipRevision.incrementAndGet();
        updateSession(playerId,
                () -> groupCache.put(playerId, new GroupMembership(group, playerId, role)));
        groupMembersCache.compute(group.id(), (ignored, current) -> {
            java.util.HashSet<UUID> updated = new java.util.HashSet<>(
                    current == null ? Set.of() : current);
            updated.add(playerId);
            return Set.copyOf(updated);
        });
    }

    public CompletionStage<Void> clearGroupMembership(UUID playerId, UUID groupId) {
        membershipRevision.incrementAndGet();
        updateSession(playerId, () -> {
            PlayerSettings previous = cache.get(playerId);
            groupCache.computeIfPresent(playerId,
                    (id, membership) -> membership.group().id().equals(groupId) ? null : membership);
            if (previous != null && GroupChannels.id(groupId).equals(previous.activeChannel())) {
                cache.put(playerId, new PlayerSettings(
                        playerId, defaultChannel, previous.directMessagesEnabled()));
            }
        });
        groupMembersCache.computeIfPresent(groupId, (id, current) -> {
            java.util.HashSet<UUID> updated = new java.util.HashSet<>(current);
            updated.remove(playerId);
            return updated.isEmpty() ? null : Set.copyOf(updated);
        });
        return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<Void> setActiveChannel(UUID playerId, String channelId) {
        if (!isLoaded(playerId)) {
            return notReady();
        }
        if (channels.find(channelId).isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown channel " + channelId));
        }
        return persistActiveChannel(playerId, channelId);
    }

    public CompletionStage<Void> setActiveGroup(UUID playerId, Group group) {
        if (!isLoaded(playerId)) {
            return notReady();
        }
        GroupMembership membership = groupCache.get(playerId);
        if (membership == null || !membership.group().id().equals(group.id())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player is not in that group"));
        }
        String channelId = GroupChannels.id(group.id());
        return repository.setActiveGroupChannel(playerId, group.id()).thenRun(() ->
                updateSession(playerId, () -> cache.compute(playerId,
                        (id, previous) -> new PlayerSettings(id, channelId,
                                previous == null || previous.directMessagesEnabled()))));
    }

    private CompletionStage<Void> persistActiveChannel(UUID playerId, String channelId) {
        return repository.setActiveChannel(playerId, channelId).thenRun(() ->
                updateSession(playerId, () -> cache.compute(playerId,
                        (id, previous) -> new PlayerSettings(id, channelId,
                                previous == null || previous.directMessagesEnabled()))));
    }

    public CompletionStage<Boolean> toggleDirectMessages(UUID playerId) {
        if (!isLoaded(playerId)) {
            return notReady();
        }
        PlayerSettings cached = cache.get(playerId);
        CompletionStage<PlayerSettings> current = cached == null
                ? repository.settings(playerId, defaultChannel)
                : CompletableFuture.completedFuture(cached);
        return current.thenCompose(settings -> {
            boolean enabled = !settings.directMessagesEnabled();
            return repository.setDirectMessagesEnabled(playerId, enabled).thenApply(ignored -> {
                updateSession(playerId, () -> cache.put(playerId, new PlayerSettings(
                        playerId, settings.activeChannel(), enabled)));
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
        Session recipientSession = sessions.get(recipientId);
        if (recipientSession != null && !isLoaded(recipientId)) {
            return notReady();
        }
        PlayerSettings cachedSettings = cache.get(recipientId);
        Set<UUID> cachedIgnores = ignoreCache.get(recipientId);
        if (isLoaded(recipientId) && cachedSettings != null && cachedIgnores != null) {
            return CompletableFuture.completedFuture(new DirectState(
                    cachedSettings, cachedIgnores.contains(senderId)));
        }
        return repository.settings(recipientId, defaultChannel)
                .thenCombine(repository.isIgnoring(recipientId, senderId), DirectState::new);
    }

    @Override
    public CompletionStage<Optional<GroupState>> groupState(UUID playerId) {
        GroupMembership membership = groupCache.get(playerId);
        if (isLoaded(playerId) && membership != null) {
            Set<UUID> members = groupMembersCache.get(membership.group().id());
            if (members != null) {
                return CompletableFuture.completedFuture(Optional.of(
                        new GroupState(membership.group(), members)));
            }
        }
        long revision = membershipRevision.get();
        return repository.groupForMember(playerId).thenCompose(stored -> {
            if (stored.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            Group group = stored.get().group();
            return repository.groupMembers(group.id()).thenApply(members -> {
                if (membershipRevision.get() == revision && sessions.containsKey(playerId)) {
                    groupCache.put(playerId, stored.get());
                    groupMembersCache.put(group.id(), members);
                }
                return Optional.of(new GroupState(group, members));
            });
        });
    }

    public void remove(UUID playerId) {
        sessions.remove(playerId);
        cache.remove(playerId);
        groupCache.remove(playerId);
        ignoreCache.remove(playerId);
    }

    private void updateSession(UUID playerId, Runnable update) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (sessions.get(playerId) != session) {
                return;
            }
            session.revision++;
            update.run();
        }
    }

    private static <T> CompletionStage<T> notReady() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Player social state is not loaded"));
    }
}
