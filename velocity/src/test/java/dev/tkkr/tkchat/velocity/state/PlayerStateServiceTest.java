package dev.tkkr.tkchat.velocity.state;

import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.model.PlayerSocialState;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tkkr.tkchat.core.service.SocialRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateServiceTest {
    @Test
    void namedLoginLoadRecordsThePlayersLatestUsername() {
        UUID playerId = UUID.randomUUID();
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        PlayerStateService states = new PlayerStateService(
                repository, channels(channel("global")), "global");

        states.activate(playerId);
        states.load(playerId, "FirstName").toCompletableFuture().join();
        states.load(playerId, "LatestName").toCompletableFuture().join();

        assertEquals(Map.of(playerId, "LatestName"), repository.playerNames(Set.of(playerId))
                .toCompletableFuture().join());
    }

    @Test
    void reconfigureRepairsAnActiveChannelThatWasRemoved() {
        UUID playerId = UUID.randomUUID();
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        PlayerStateService states = new PlayerStateService(repository,
                channels(channel("global"), channel("local")), "global");
        states.activate(playerId);
        states.load(playerId).toCompletableFuture().join();
        states.setActiveChannel(playerId, "local").toCompletableFuture().join();

        states.reconfigure(channels(channel("global")), "global")
                .toCompletableFuture().join();

        assertEquals("global", states.activeChannel(playerId));
        assertEquals("global", repository.settings(playerId, "global")
                .toCompletableFuture().join().activeChannel());
    }

    @Test
    void failedPersistenceDoesNotChangeTheCachedActiveChannel() {
        UUID playerId = UUID.randomUUID();
        InMemorySocialRepository delegate = new InMemorySocialRepository("global");
        SocialRepository repository = (SocialRepository) java.lang.reflect.Proxy.newProxyInstance(
                SocialRepository.class.getClassLoader(),
                new Class<?>[]{SocialRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setActiveChannel")) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("database unavailable"));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        PlayerStateService states = new PlayerStateService(repository,
                channels(channel("global"), channel("local")), "global");
        states.activate(playerId);
        states.load(playerId).toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> states.setActiveChannel(playerId, "local")
                .toCompletableFuture().join());

        assertEquals("global", states.activeChannel(playerId));
    }

    @Test
    void loadedPlayersUseCachedStateForDirectAndGroupChatRouting() {
        UUID playerId = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        InMemorySocialRepository delegate = new InMemorySocialRepository("global");
        var group = delegate.createGroup(
                playerId, "Builders", GroupVisibility.PUBLIC, null).toCompletableFuture().join();
        delegate.setIgnoring(playerId, otherPlayer, true).toCompletableFuture().join();
        AtomicInteger chatStateReads = new AtomicInteger();
        SocialRepository repository = (SocialRepository) java.lang.reflect.Proxy.newProxyInstance(
                SocialRepository.class.getClassLoader(),
                new Class<?>[]{SocialRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("loadPlayerState")) {
                        chatStateReads.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        PlayerStateService states = new PlayerStateService(
                repository, channels(channel("global")), "global");
        states.activate(playerId);
        states.load(playerId).toCompletableFuture().join();
        int readsAfterLogin = chatStateReads.get();

        var direct = states.directState(playerId, otherPlayer, "global")
                .toCompletableFuture().join();
        var cachedGroup = states.groupState(playerId).toCompletableFuture().join().orElseThrow();

        assertTrue(direct.ignoringSender());
        assertEquals(group.id(), cachedGroup.group().id());
        assertTrue(cachedGroup.members().contains(playerId));
        assertEquals(1, readsAfterLogin);
        assertEquals(readsAfterLogin, chatStateReads.get());
    }

    @Test
    void disconnectPreventsALateLoginLoadFromRepopulatingState() {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<PlayerSocialState> pending = new CompletableFuture<>();
        SocialRepository repository = repositoryWithLoad(pending);
        PlayerStateService states = new PlayerStateService(
                repository, channels(channel("global")), "global");

        states.activate(playerId);
        CompletableFuture<PlayerSettings> load = states.load(playerId).toCompletableFuture();
        states.remove(playerId);
        pending.complete(emptyState(playerId));

        assertThrows(CompletionException.class, load::join);
        assertFalse(states.isLoaded(playerId));
    }

    @Test
    void reconnectRejectsThePreviousSessionsLateLoad() {
        UUID playerId = UUID.randomUUID();
        CompletableFuture<PlayerSocialState> first = new CompletableFuture<>();
        CompletableFuture<PlayerSocialState> second = new CompletableFuture<>();
        AtomicInteger loads = new AtomicInteger();
        SocialRepository repository = repositoryWithLoadSelector(() ->
                loads.getAndIncrement() == 0 ? first : second);
        PlayerStateService states = new PlayerStateService(
                repository, channels(channel("global")), "global");

        states.activate(playerId);
        CompletableFuture<PlayerSettings> oldLoad = states.load(playerId).toCompletableFuture();
        states.activate(playerId);
        CompletableFuture<PlayerSettings> newLoad = states.load(playerId).toCompletableFuture();
        first.complete(emptyState(playerId));
        second.complete(emptyState(playerId));

        assertThrows(CompletionException.class, oldLoad::join);
        newLoad.join();
        assertTrue(states.isLoaded(playerId));
    }

    @Test
    void failedLoadCanBeRetriedWithoutUsingDefaults() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        SocialRepository repository = repositoryWithLoadSelector(() ->
                loads.getAndIncrement() == 0
                        ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                        : CompletableFuture.completedFuture(emptyState(playerId)));
        PlayerStateService states = new PlayerStateService(
                repository, channels(channel("global")), "global");

        states.activate(playerId);
        assertThrows(CompletionException.class,
                () -> states.load(playerId).toCompletableFuture().join());
        assertFalse(states.isLoaded(playerId));

        states.load(playerId).toCompletableFuture().join();
        assertTrue(states.isLoaded(playerId));
        assertEquals(2, loads.get());
    }

    private static SocialRepository repositoryWithLoad(
            CompletableFuture<PlayerSocialState> load
    ) {
        return repositoryWithLoadSelector(() -> load);
    }

    private static SocialRepository repositoryWithLoadSelector(
            java.util.function.Supplier<CompletableFuture<PlayerSocialState>> loads
    ) {
        InMemorySocialRepository delegate = new InMemorySocialRepository("global");
        return (SocialRepository) java.lang.reflect.Proxy.newProxyInstance(
                SocialRepository.class.getClassLoader(),
                new Class<?>[]{SocialRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("loadPlayerState")) {
                        return loads.get();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private static PlayerSocialState emptyState(UUID playerId) {
        return new PlayerSocialState(
                new PlayerSettings(playerId, "global", true),
                java.util.Optional.empty(), java.util.Set.of(), java.util.Set.of());
    }

    private static ChannelRegistry channels(ChannelDefinition... channels) {
        return new ChannelRegistry(List.of(channels));
    }

    private static ChannelDefinition channel(String id) {
        return new ChannelDefinition(id, id, ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of(), "<message>");
    }
}
