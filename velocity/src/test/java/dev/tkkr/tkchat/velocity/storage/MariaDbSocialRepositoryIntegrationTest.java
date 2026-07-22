package dev.tkkr.tkchat.velocity.storage;

import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only when the TKCHAT_TEST_MARIADB_* environment variables are supplied. */
class MariaDbSocialRepositoryIntegrationTest {
    private AppConfig.MariaDb config;
    private MariaDbSocialRepository repository;

    @BeforeEach
    void connect() throws Exception {
        String jdbcUrl = System.getenv("TKCHAT_TEST_MARIADB_URL");
        assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                "MariaDB integration environment is not configured");
        config = new AppConfig.MariaDb();
        config.jdbcUrl = jdbcUrl;
        config.username = requiredEnvironment("TKCHAT_TEST_MARIADB_USERNAME");
        config.password = requiredEnvironment("TKCHAT_TEST_MARIADB_PASSWORD");
        config.tablePrefix = "tkchat_it_" + UUID.randomUUID().toString().substring(0, 8);
        config.maximumPoolSize = 4;
        config.minimumIdle = 0;
        config.workerThreads = 4;
        config.maxQueuedOperations = 64;
        repository = new MariaDbSocialRepository(config, "global");
    }

    @AfterEach
    void disconnectAndDropTemporaryTables() throws Exception {
        if (repository != null) {
            repository.close();
        }
        if (config == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                config.jdbcUrl, config.username, config.password);
             Statement statement = connection.createStatement()) {
            for (String suffix : List.of(
                    "group_invites", "group_members", "ignores", "groups", "player_settings",
                    "players")) {
                statement.executeUpdate("DROP TABLE IF EXISTS `"
                        + config.tablePrefix + "_" + suffix + "`");
            }
        }
    }

    @Test
    void loadsACompleteSocialSnapshotAndPersistsPreferences() {
        UUID player = UUID.randomUUID();
        UUID ignored = UUID.randomUUID();

        repository.settings(player, "global").toCompletableFuture().join();
        repository.setDirectMessagesEnabled(player, false).toCompletableFuture().join();
        repository.setActiveChannel(player, "local").toCompletableFuture().join();
        repository.setIgnoring(player, ignored, true).toCompletableFuture().join();
        var group = repository.createGroup(
                player, "Builders", GroupVisibility.PUBLIC, null).toCompletableFuture().join();

        var snapshot = repository.loadPlayerState(player, "BuilderOne", "global")
                .toCompletableFuture().join();

        assertEquals("local", snapshot.settings().activeChannel());
        assertFalse(snapshot.settings().directMessagesEnabled());
        assertTrue(snapshot.ignoredPlayers().contains(ignored));
        assertEquals(group.id(), snapshot.groupMembership().orElseThrow().group().id());
        assertEquals(Set.of(player), snapshot.groupMembers());
        assertEquals(Map.of(player, "BuilderOne"), repository.playerNames(Set.of(player, ignored))
                .toCompletableFuture().join());

        repository.recordPlayerName(player, "BuilderRenamed").toCompletableFuture().join();

        assertEquals(Map.of(player, "BuilderRenamed"), repository.playerNames(Set.of(player))
                .toCompletableFuture().join());
    }

    @Test
    void groupRulesAndDisbandAreAtomic() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID duplicateNameOwner = UUID.randomUUID();
        UUID expiredInvitee = UUID.randomUUID();
        UUID memberInvitee = UUID.randomUUID();
        var group = repository.createGroup(
                owner, "PrivateTeam", GroupVisibility.PRIVATE, "correct-horse")
                .toCompletableFuture().join();

        CompletionException duplicateName = assertThrows(CompletionException.class, () -> repository
                .createGroup(duplicateNameOwner, "PRIVATETEAM", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join());
        assertEquals(GroupFailure.NAME_TAKEN,
                assertInstanceOf(GroupException.class, duplicateName.getCause()).failure());
        CompletionException secondMembership = assertThrows(CompletionException.class, () -> repository
                .createGroup(owner, "MustRollback", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join());
        assertEquals(GroupFailure.ALREADY_MEMBER,
                assertInstanceOf(GroupException.class, secondMembership.getCause()).failure());
        assertTrue(repository.groupByName("mustrollback").toCompletableFuture().join().isEmpty());

        CompletionException badPassword = assertThrows(CompletionException.class, () -> repository
                .joinGroup(member, "privateteam", "wrong-password", false, Instant.now())
                .toCompletableFuture().join());
        assertEquals(GroupFailure.INVALID_PASSWORD,
                assertInstanceOf(GroupException.class, badPassword.getCause()).failure());

        repository.invite(group.id(), owner, expiredInvitee, Instant.now().minusSeconds(1))
                .toCompletableFuture().join();
        assertEquals(java.util.Set.of(), repository.groupInvitees(group.id(), Instant.now())
                .toCompletableFuture().join());
        CompletionException expiredInvite = assertThrows(CompletionException.class, () -> repository
                .acceptInvite(expiredInvitee, "privateteam", Instant.now())
                .toCompletableFuture().join());
        assertEquals(GroupFailure.INVITE_MISSING_OR_EXPIRED,
                assertInstanceOf(GroupException.class, expiredInvite.getCause()).failure());

        repository.invite(group.id(), owner, member, Instant.now().plusSeconds(60))
                .toCompletableFuture().join();
        assertEquals(java.util.Set.of(member), repository.groupInvitees(group.id(), Instant.now())
                .toCompletableFuture().join());
        repository.acceptInvite(member, "PRIVATEteam", Instant.now())
                .toCompletableFuture().join();
        assertEquals(java.util.Set.of(), repository.groupInvitees(group.id(), Instant.now())
                .toCompletableFuture().join());
        repository.invite(group.id(), member, memberInvitee, Instant.now().plusSeconds(60))
                .toCompletableFuture().join();
        assertEquals(java.util.Set.of(memberInvitee),
                repository.groupInvitees(group.id(), Instant.now()).toCompletableFuture().join());
        repository.setActiveChannel(owner, GroupChannels.id(group.id())).toCompletableFuture().join();
        repository.setActiveChannel(member, GroupChannels.id(group.id())).toCompletableFuture().join();

        var leave = repository.leaveGroup(owner).toCompletableFuture().join();

        assertTrue(leave.deleted());
        assertEquals(java.util.Set.of(owner, member), leave.affectedMembers());
        assertTrue(repository.groupByName("privateteam").toCompletableFuture().join().isEmpty());
        assertTrue(repository.groupForMember(member).toCompletableFuture().join().isEmpty());
        assertEquals("global", repository.settings(owner, "global")
                .toCompletableFuture().join().activeChannel());
        assertEquals("global", repository.settings(member, "global")
                .toCompletableFuture().join().activeChannel());
    }

    @Test
    void concurrentJoinsCannotPutAPlayerInTwoGroups() {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID joiningPlayer = UUID.randomUUID();
        repository.createGroup(firstOwner, "First", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.createGroup(secondOwner, "Second", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();

        var first = repository.joinGroup(
                joiningPlayer, "first", null, false, Instant.now()).toCompletableFuture();
        var second = repository.joinGroup(
                joiningPlayer, "second", null, false, Instant.now()).toCompletableFuture();
        int successes = (first.handle((value, error) -> error == null ? 1 : 0).join())
                + (second.handle((value, error) -> error == null ? 1 : 0).join());

        assertEquals(1, successes);
        CompletionException loser = assertThrows(CompletionException.class,
                () -> (first.isCompletedExceptionally() ? first : second).join());
        assertEquals(GroupFailure.ALREADY_MEMBER,
                assertInstanceOf(GroupException.class, loser.getCause()).failure());
        assertTrue(repository.groupForMember(joiningPlayer).toCompletableFuture().join().isPresent());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is not configured");
        return value;
    }
}
