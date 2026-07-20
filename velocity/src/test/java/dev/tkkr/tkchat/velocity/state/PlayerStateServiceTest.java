package dev.tkkr.tkchat.velocity.state;

import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tkkr.tkchat.core.service.SocialRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateServiceTest {
    @Test
    void reconfigureRepairsAnActiveChannelThatWasRemoved() {
        UUID playerId = UUID.randomUUID();
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        PlayerStateService states = new PlayerStateService(repository,
                channels(channel("global"), channel("local")), "global");
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
                    if (java.util.Set.of("settings", "groupForMember", "groupMembers",
                            "ignoredPlayers", "isIgnoring").contains(method.getName())) {
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
        states.load(playerId).toCompletableFuture().join();
        int readsAfterLogin = chatStateReads.get();

        var direct = states.directState(playerId, otherPlayer, "global")
                .toCompletableFuture().join();
        var cachedGroup = states.groupState(playerId).toCompletableFuture().join().orElseThrow();

        assertTrue(direct.ignoringSender());
        assertEquals(group.id(), cachedGroup.group().id());
        assertTrue(cachedGroup.members().contains(playerId));
        assertEquals(readsAfterLogin, chatStateReads.get());
    }

    private static ChannelRegistry channels(ChannelDefinition... channels) {
        return new ChannelRegistry(List.of(channels));
    }

    private static ChannelDefinition channel(String id) {
        return new ChannelDefinition(id, id, ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of(), "<message>");
    }
}
