package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.AccessDecision;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.RouteDecision;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.model.SenderContext;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRouterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void buildsApprovedGlobalMessageWithoutParsingMiniMessageInput() throws Exception {
        ChatRouter router = router((sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("<red>[Admin]</red> ", "")), 5);

        RouteDecision decision = router.routeChannel(sender(), "global", "<rainbow>Hello 👋</rainbow>")
                .toCompletableFuture().join();

        RouteDecision.Approved approved = assertInstanceOf(RouteDecision.Approved.class, decision);
        assertEquals(RouteKind.CHANNEL, approved.message().routeKind());
        assertEquals("<rainbow>Hello 👋</rainbow>", approved.message().content());
        assertEquals("<red>[Admin]</red> ", approved.message().senderPrefix());
    }

    @Test
    void deniesMutedPlayer() throws Exception {
        ChatRouter router = router((sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.deny(DenialReason.MUTED)), 5);

        RouteDecision.Denied denied = assertInstanceOf(RouteDecision.Denied.class,
                router.routeChannel(sender(), "global", "hello").toCompletableFuture().join());

        assertEquals(DenialReason.MUTED, denied.reason());
    }

    @Test
    void appliesSlidingWindowRateLimit() throws Exception {
        ChatRouter router = router((sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("", "")), 1);

        assertInstanceOf(RouteDecision.Approved.class,
                router.routeChannel(sender(), "global", "first").toCompletableFuture().join());
        RouteDecision.Denied denied = assertInstanceOf(RouteDecision.Denied.class,
                router.routeChannel(sender(), "global", "second").toCompletableFuture().join());
        assertEquals(DenialReason.RATE_LIMITED, denied.reason());
    }

    @Test
    void rateLimitBypassSkipsSlidingWindow() throws Exception {
        ChatRouter router = router((sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("", "")), 1);
        SenderContext bypassing = new SenderContext(
                ALICE, "Alice", "survival", InetAddress.getLoopbackAddress(), Map.of(), true);

        assertInstanceOf(RouteDecision.Approved.class,
                router.routeChannel(bypassing, "global", "first").toCompletableFuture().join());
        assertInstanceOf(RouteDecision.Approved.class,
                router.routeChannel(bypassing, "global", "second").toCompletableFuture().join());
    }

    @Test
    void clearsRateLimitStateWhenAPlayerLeaves() throws Exception {
        ChatRouter router = router((sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("", "")), 1);

        assertInstanceOf(RouteDecision.Approved.class,
                router.routeChannel(sender(), "global", "first").toCompletableFuture().join());
        router.remove(ALICE);

        assertInstanceOf(RouteDecision.Approved.class,
                router.routeChannel(sender(), "global", "after reconnect").toCompletableFuture().join());
    }

    @Test
    void channelAliasesResolveToCanonicalChannel() {
        ChannelDefinition channel = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL, "send", "receive", "bypass",
                List.of("g"), "<message>");
        ChannelRegistry registry = new ChannelRegistry(List.of(channel));

        assertEquals(channel, registry.find("g").orElseThrow());
        assertEquals(channel, registry.find("GLOBAL").orElseThrow());
    }

    @Test
    void targetsEveryGroupMember() throws Exception {
        InMemorySocialRepository repository = new InMemorySocialRepository();
        var group = repository.createGroup(ALICE, "Builders", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.invite(group.id(), ALICE, BOB, NOW.plusSeconds(60)).toCompletableFuture().join();
        repository.acceptInvite(BOB, "builders", NOW).toCompletableFuture().join();
        ChatRouter router = router(repository, (sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("", "")), 5);

        RouteDecision.Approved approved = assertInstanceOf(RouteDecision.Approved.class,
                router.routeGroup(sender(), "ready?").toCompletableFuture().join());

        assertEquals(RouteKind.GROUP, approved.message().routeKind());
        assertEquals(2, approved.message().recipients().size());
        assertTrue(approved.message().recipients().containsAll(List.of(ALICE, BOB)));
    }

    @Test
    void honorsRecipientIgnoreListForDirectMessages() throws Exception {
        InMemorySocialRepository repository = new InMemorySocialRepository();
        repository.setIgnoring(BOB, ALICE, true).toCompletableFuture().join();
        ChatRouter router = router(repository, (sender, permission, bypassPermission, containsLink) ->
                CompletableFuture.completedFuture(AccessDecision.allow("", "")), 5);

        RouteDecision.Denied denied = assertInstanceOf(RouteDecision.Denied.class,
                router.routeDirect(sender(), BOB, "Bob", "hello").toCompletableFuture().join());

        assertEquals(DenialReason.IGNORED, denied.reason());
    }

    @Test
    void groupNamesAreUniqueCaseInsensitively() {
        InMemorySocialRepository repository = new InMemorySocialRepository();
        repository.createGroup(ALICE, "Builders", GroupVisibility.PUBLIC, null).toCompletableFuture().join();

        CompletionException error = assertThrows(CompletionException.class, () -> repository
                .createGroup(BOB, "builders", GroupVisibility.PUBLIC, null).toCompletableFuture().join());

        GroupException groupError = assertInstanceOf(GroupException.class, error.getCause());
        assertEquals(GroupFailure.NAME_TAKEN, groupError.failure());
    }

    @Test
    void publicGroupCanBeJoinedByName() {
        InMemorySocialRepository repository = new InMemorySocialRepository();
        repository.createGroup(ALICE, "Builders", GroupVisibility.PUBLIC, null).toCompletableFuture().join();

        var joined = repository.joinGroup(BOB, "BUILDERS", null, false, NOW).toCompletableFuture().join();

        assertEquals("builders", joined.normalizedName());
        assertTrue(repository.groupForMember(BOB).toCompletableFuture().join().isPresent());
    }

    @Test
    void privateGroupAcceptsPasswordWithoutPersistingPlaintext() {
        InMemorySocialRepository repository = new InMemorySocialRepository();
        var group = repository.createGroup(ALICE, "Staff", GroupVisibility.PRIVATE, "correct-horse")
                .toCompletableFuture().join();

        assertTrue(group.passwordProtected());
        CompletionException error = assertThrows(CompletionException.class, () -> repository
                .joinGroup(BOB, "staff", "wrong-password", false, NOW).toCompletableFuture().join());
        assertEquals(GroupFailure.INVALID_PASSWORD,
                assertInstanceOf(GroupException.class, error.getCause()).failure());

        assertEquals(group.id(), repository.joinGroup(BOB, "staff", "correct-horse", false, NOW)
                .toCompletableFuture().join().id());
    }

    @Test
    void invitationAndAdminBypassPrivatePassword() {
        InMemorySocialRepository invitedRepository = new InMemorySocialRepository();
        var invitedGroup = invitedRepository.createGroup(
                ALICE, "InviteOnly", GroupVisibility.PRIVATE, "secret-value")
                .toCompletableFuture().join();
        invitedRepository.invite(invitedGroup.id(), ALICE, BOB, NOW.plusSeconds(60))
                .toCompletableFuture().join();

        assertEquals(invitedGroup.id(), invitedRepository.acceptInvite(BOB, "inviteonly", NOW)
                .toCompletableFuture().join().id());

        InMemorySocialRepository adminRepository = new InMemorySocialRepository();
        var adminGroup = adminRepository.createGroup(
                ALICE, "AdminOnly", GroupVisibility.PRIVATE, "secret-value")
                .toCompletableFuture().join();
        assertEquals(adminGroup.id(), adminRepository.joinGroup(BOB, "adminonly", null, true, NOW)
                .toCompletableFuture().join().id());
        assertFalse(adminRepository.listGroups(false).toCompletableFuture().join().contains(adminGroup));
    }

    private static ChatRouter router(AccessController accessController, int rateLimit) throws Exception {
        return router(new InMemorySocialRepository(), accessController, rateLimit);
    }

    private static ChatRouter router(
            SocialRepository repository,
            AccessController accessController,
            int rateLimit
    ) throws Exception {
        return new ChatRouter(
                new ChannelRegistry(List.of(new ChannelDefinition(
                        "global", "Global", ChannelScope.GLOBAL,
                        "tkchat.channels.global.send", "tkchat.channels.global.receive",
                        "tkchat.bypass.channel_restrictions", List.of("g"), "<message>"))),
                accessController,
                repository,
                new ChatPolicy(256, rateLimit, Duration.ofSeconds(5)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "tkchat.bypass.channel_restrictions");
    }

    private static SenderContext sender() throws Exception {
        return new SenderContext(
                ALICE, "Alice", "survival", InetAddress.getLoopbackAddress(), Map.of(), false);
    }
}
