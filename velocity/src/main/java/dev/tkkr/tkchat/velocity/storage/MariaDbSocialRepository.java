package dev.tkkr.tkchat.velocity.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupLeaveResult;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.model.PlayerSocialState;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.core.service.GroupNames;
import dev.tkkr.tkchat.core.service.GroupPasswords;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.config.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** MariaDB-backed social state with transactional group mutations and database constraints. */
public final class MariaDbSocialRepository implements SocialRepository {
    private record StoredGroup(Group group, String passwordHash) {
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String playersTable;
    private final String settingsTable;
    private final String groupsTable;
    private final String membersTable;
    private final String invitesTable;
    private final String ignoresTable;
    private volatile String defaultChannel;

    public MariaDbSocialRepository(AppConfig.MariaDb config, String defaultChannel) throws SQLException {
        this.defaultChannel = defaultChannel;
        String prefix = config.tablePrefix;
        playersTable = table(prefix + "_players");
        settingsTable = table(prefix + "_player_settings");
        groupsTable = table(prefix + "_groups");
        membersTable = table(prefix + "_group_members");
        invitesTable = table(prefix + "_group_invites");
        ignoresTable = table(prefix + "_ignores");
        executor = new ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxQueuedOperations),
                Thread.ofPlatform().name("tkchat-mariadb-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("tkChat-MariaDB");
        pool.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        pool.setJdbcUrl(config.jdbcUrl);
        pool.setUsername(config.username);
        pool.setPassword(config.password);
        pool.setMaximumPoolSize(config.maximumPoolSize);
        pool.setMinimumIdle(config.minimumIdle);
        pool.setConnectionTimeout(config.connectionTimeoutMillis);
        pool.setValidationTimeout(config.validationTimeoutMillis);
        pool.setIdleTimeout(config.idleTimeoutMillis);
        pool.setMaxLifetime(config.maxLifetimeMillis);
        pool.setInitializationFailTimeout(config.connectionTimeoutMillis);
        pool.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        pool.addDataSourceProperty("connectTimeout", Long.toString(config.connectTimeoutMillis));
        pool.addDataSourceProperty("socketTimeout", Long.toString(config.socketTimeoutMillis));

        HikariDataSource created = null;
        try {
            created = new HikariDataSource(pool);
            dataSource = created;
            initializeSchema();
        } catch (RuntimeException | SQLException error) {
            if (created != null) {
                created.close();
            }
            executor.shutdownNow();
            throw error;
        }
    }

    @Override
    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        username VARCHAR(64) NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (player_id)
                    ) ENGINE=InnoDB
                    """.formatted(playersTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        active_channel VARCHAR(96) NOT NULL,
                        direct_messages_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        PRIMARY KEY (player_id)
                    ) ENGINE=InnoDB
                    """.formatted(settingsTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        normalized_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        password_hash VARCHAR(255) NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (group_id),
                        UNIQUE KEY uq_normalized_name (normalized_name)
                    ) ENGINE=InnoDB
                    """.formatted(groupsTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        joined_at BIGINT NOT NULL,
                        PRIMARY KEY (player_id),
                        KEY idx_group_members_group (group_id),
                        FOREIGN KEY (group_id) REFERENCES %s (group_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB
                    """.formatted(membersTable, groupsTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        invited_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        inviter_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        expires_at BIGINT NOT NULL,
                        PRIMARY KEY (invited_id, group_id),
                        KEY idx_group_invites_group (group_id),
                        KEY idx_group_invites_expiry (expires_at),
                        FOREIGN KEY (group_id) REFERENCES %s (group_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB
                    """.formatted(invitesTable, groupsTable));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        ignored_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (owner_id, ignored_id),
                        KEY idx_ignores_ignored (ignored_id)
                    ) ENGINE=InnoDB
                    """.formatted(ignoresTable));
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM " + invitesTable + " WHERE expires_at <= ?")) {
                cleanup.setLong(1, System.currentTimeMillis());
                cleanup.executeUpdate();
            }
        }
    }

    @Override
    public CompletionStage<PlayerSettings> settings(UUID playerId, String requestedDefaultChannel) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                ensureSettings(connection, playerId, requestedDefaultChannel);
                return requireSettings(connection, playerId);
            }
        });
    }

    @Override
    public CompletionStage<PlayerSocialState> loadPlayerState(
            UUID playerId,
            String requestedDefaultChannel
    ) {
        return loadPlayerStateSnapshot(playerId, null, requestedDefaultChannel);
    }

    @Override
    public CompletionStage<PlayerSocialState> loadPlayerState(
            UUID playerId,
            String username,
            String requestedDefaultChannel
    ) {
        return loadPlayerStateSnapshot(playerId, username, requestedDefaultChannel);
    }

    private CompletionStage<PlayerSocialState> loadPlayerStateSnapshot(
            UUID playerId,
            String username,
            String requestedDefaultChannel
    ) {
        return supply(() -> transaction(connection -> {
            if (username != null) {
                upsertPlayerName(connection, playerId, username);
            }
            ensureSettings(connection, playerId, requestedDefaultChannel);
            PlayerSettings settings = requireSettings(connection, playerId);
            Optional<GroupMembership> membership = findMembership(connection, playerId, false);
            Set<UUID> members = membership.isEmpty()
                    ? Set.of()
                    : findGroupMembers(connection, membership.get().group().id());
            Set<UUID> ignored = findIgnoredPlayers(connection, playerId);
            return new PlayerSocialState(settings, membership, ignored, members);
        }));
    }

    @Override
    public CompletionStage<Void> recordPlayerName(UUID playerId, String username) {
        return run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                upsertPlayerName(connection, playerId, username);
            }
        });
    }

    @Override
    public CompletionStage<Map<UUID, String>> playerNames(Set<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<UUID> orderedIds = List.copyOf(playerIds);
        String placeholders = String.join(", ", java.util.Collections.nCopies(
                orderedIds.size(), "?"));
        return supply(() -> {
            Map<UUID, String> result = new LinkedHashMap<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT player_id, username FROM " + playersTable
                                 + " WHERE player_id IN (" + placeholders + ")")) {
                for (int index = 0; index < orderedIds.size(); index++) {
                    statement.setString(index + 1, orderedIds.get(index).toString());
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.put(UUID.fromString(rows.getString("player_id")),
                                rows.getString("username"));
                    }
                }
            }
            return Map.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Void> setActiveChannel(UUID playerId, String channelId) {
        return run(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO %s (player_id, active_channel, direct_messages_enabled)
                         VALUES (?, ?, TRUE)
                         ON DUPLICATE KEY UPDATE active_channel = VALUE(active_channel)
                         """.formatted(settingsTable))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, channelId);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Void> setActiveGroupChannel(UUID playerId, UUID groupId) {
        return run(() -> transaction(connection -> {
            lockPlayer(connection, playerId);
            Optional<GroupMembership> membership = findMembership(connection, playerId, true);
            if (membership.isEmpty() || !membership.get().group().id().equals(groupId)) {
                throw new IllegalArgumentException("Player is not in that group");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + settingsTable + " SET active_channel = ? WHERE player_id = ?")) {
                statement.setString(1, GroupChannels.id(groupId));
                statement.setString(2, playerId.toString());
                statement.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<Boolean> compareAndSetActiveChannel(
            UUID playerId,
            String expectedChannelId,
            String channelId
    ) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE " + settingsTable
                                 + " SET active_channel = ? WHERE player_id = ? AND active_channel = ?")) {
                statement.setString(1, channelId);
                statement.setString(2, playerId.toString());
                statement.setString(3, expectedChannelId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletionStage<Void> setDirectMessagesEnabled(UUID playerId, boolean enabled) {
        return run(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO %s (player_id, active_channel, direct_messages_enabled)
                         VALUES (?, ?, ?)
                         ON DUPLICATE KEY UPDATE direct_messages_enabled = VALUE(direct_messages_enabled)
                         """.formatted(settingsTable))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, defaultChannel);
                statement.setBoolean(3, enabled);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Group> createGroup(
            UUID ownerId,
            String name,
            GroupVisibility visibility,
            String password
    ) {
        return supply(() -> {
            String normalizedName = GroupNames.normalize(name);
            String passwordHash = visibility == GroupVisibility.PRIVATE
                    ? GroupPasswords.hash(password)
                    : null;
            UUID groupId = UUID.randomUUID();
            try {
                return transaction(connection -> {
                    lockPlayer(connection, ownerId);
                    try (PreparedStatement insertGroup = connection.prepareStatement("""
                            INSERT INTO %s
                                (group_id, name, normalized_name, owner_id, visibility, password_hash, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.formatted(groupsTable))) {
                        insertGroup.setString(1, groupId.toString());
                        insertGroup.setString(2, name);
                        insertGroup.setString(3, normalizedName);
                        insertGroup.setString(4, ownerId.toString());
                        insertGroup.setString(5, visibility.name());
                        insertGroup.setString(6, passwordHash);
                        insertGroup.setLong(7, System.currentTimeMillis());
                        insertGroup.executeUpdate();
                    }
                    insertMember(connection, ownerId, groupId, GroupRole.OWNER);
                    return new Group(groupId, name, normalizedName, ownerId, visibility,
                            passwordHash != null);
                });
            } catch (SQLException error) {
                if (isConstraintViolation(error)) {
                    if (memberExists(ownerId)) {
                        throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
                    }
                    if (groupNameExists(normalizedName)) {
                        throw failure(GroupFailure.NAME_TAKEN, "A group with that name already exists");
                    }
                }
                throw error;
            }
        });
    }

    @Override
    public CompletionStage<Optional<Group>> groupByName(String name) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return Optional.ofNullable(findStoredGroup(connection, GroupNames.normalize(name), false))
                        .map(StoredGroup::group);
            }
        });
    }

    @Override
    public CompletionStage<List<Group>> listGroups(boolean includePrivate) {
        return supply(() -> {
            String sql = "SELECT group_id, name, normalized_name, owner_id, visibility, password_hash FROM "
                    + groupsTable
                    + (includePrivate ? "" : " WHERE visibility = ?")
                    + " ORDER BY normalized_name";
            List<Group> result = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                if (!includePrivate) {
                    statement.setString(1, GroupVisibility.PUBLIC.name());
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readStoredGroup(rows).group());
                    }
                }
            }
            result.sort(Comparator.comparing(Group::normalizedName));
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Optional<GroupMembership>> groupForMember(UUID playerId) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return findMembership(connection, playerId, false);
            }
        });
    }

    @Override
    public CompletionStage<Set<UUID>> groupMembers(UUID groupId) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return findGroupMembers(connection, groupId);
            }
        });
    }

    @Override
    public CompletionStage<Set<UUID>> groupInvitees(UUID groupId, java.time.Instant now) {
        return supply(() -> {
            Set<UUID> result = new HashSet<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT invited_id FROM " + invitesTable
                                 + " WHERE group_id = ? AND expires_at > ?")) {
                statement.setString(1, groupId.toString());
                statement.setLong(2, now.toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(UUID.fromString(rows.getString("invited_id")));
                    }
                }
            }
            return Set.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Void> invite(
            UUID groupId,
            UUID inviterId,
            UUID invitedId,
            java.time.Instant expiresAt
    ) {
        return run(() -> transaction(connection -> {
            lockPlayer(connection, invitedId);
            Optional<GroupMembership> inviter = findMembership(connection, inviterId, true);
            if (inviter.isEmpty() || !inviter.get().group().id().equals(groupId)) {
                throw failure(GroupFailure.NOT_MEMBER, "Inviter is not a member of that group");
            }
            if (findMembership(connection, invitedId, true).isPresent()) {
                throw failure(GroupFailure.ALREADY_MEMBER, "That player is already in a group");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO %s (invited_id, group_id, inviter_id, expires_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        inviter_id = VALUE(inviter_id), expires_at = VALUE(expires_at)
                    """.formatted(invitesTable))) {
                statement.setString(1, invitedId.toString());
                statement.setString(2, groupId.toString());
                statement.setString(3, inviterId.toString());
                statement.setLong(4, expiresAt.toEpochMilli());
                statement.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<Group> acceptInvite(
            UUID invitedId,
            String groupName,
            java.time.Instant now
    ) {
        return join(invitedId, groupName, null, false, true, now);
    }

    @Override
    public CompletionStage<Group> joinGroup(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            java.time.Instant now
    ) {
        return join(playerId, groupName, password, bypass, false, now);
    }

    private CompletionStage<Group> join(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            boolean requireInvite,
            java.time.Instant now
    ) {
        return supply(() -> {
            try {
                return transaction(connection -> {
                    lockPlayer(connection, playerId);
                    StoredGroup stored = findStoredGroup(
                            connection, GroupNames.normalize(groupName), true);
                    if (stored == null) {
                        throw failure(GroupFailure.NOT_FOUND, "That group does not exist");
                    }
                    if (findMembership(connection, playerId, true).isPresent()) {
                        throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
                    }
                    boolean invited = validInvite(
                            connection, playerId, stored.group().id(), now.toEpochMilli());
                    if (requireInvite && !invited) {
                        throw failure(GroupFailure.INVITE_MISSING_OR_EXPIRED,
                                "Invite is missing or expired");
                    }
                    boolean allowed = stored.group().visibility() == GroupVisibility.PUBLIC
                            || bypass || invited;
                    if (!allowed && (stored.passwordHash() == null
                            || stored.passwordHash().isBlank())) {
                        throw failure(GroupFailure.INVITE_REQUIRED,
                                "This private group requires an invitation");
                    }
                    if (!allowed && !GroupPasswords.matches(password, stored.passwordHash())) {
                        throw failure(GroupFailure.INVALID_PASSWORD,
                                "The group password is incorrect");
                    }
                    insertMember(connection, playerId, stored.group().id(), GroupRole.MEMBER);
                    if (invited) {
                        deleteInvite(connection, playerId, stored.group().id());
                    }
                    return stored.group();
                });
            } catch (SQLException error) {
                if (isConstraintViolation(error) && memberExists(playerId)) {
                    throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
                }
                throw error;
            }
        });
    }

    @Override
    public CompletionStage<GroupLeaveResult> leaveGroup(UUID playerId) {
        return supply(() -> transaction(connection -> {
            lockPlayer(connection, playerId);
            Optional<GroupMembership> membership = findMembership(connection, playerId, true);
            if (membership.isEmpty()) {
                throw failure(GroupFailure.NOT_MEMBER, "Player is not in a group");
            }
            Group group = membership.get().group();
            boolean deleted = membership.get().role() == GroupRole.OWNER;
            Set<UUID> affected;
            if (deleted) {
                affected = findGroupMembers(connection, group.id());
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + groupsTable + " WHERE group_id = ?")) {
                    statement.setString(1, group.id().toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + settingsTable
                                + " SET active_channel = ? WHERE active_channel = ?")) {
                    statement.setString(1, defaultChannel);
                    statement.setString(2, GroupChannels.id(group.id()));
                    statement.executeUpdate();
                }
            } else {
                affected = Set.of(playerId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + membersTable + " WHERE player_id = ?")) {
                    statement.setString(1, playerId.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + settingsTable
                                + " SET active_channel = ? WHERE player_id = ? AND active_channel = ?")) {
                    statement.setString(1, defaultChannel);
                    statement.setString(2, playerId.toString());
                    statement.setString(3, GroupChannels.id(group.id()));
                    statement.executeUpdate();
                }
            }
            return new GroupLeaveResult(group, affected, deleted);
        }));
    }

    @Override
    public CompletionStage<Boolean> isIgnoring(UUID ownerId, UUID ignoredId) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT 1 FROM " + ignoresTable
                                 + " WHERE owner_id = ? AND ignored_id = ?")) {
                statement.setString(1, ownerId.toString());
                statement.setString(2, ignoredId.toString());
                try (ResultSet row = statement.executeQuery()) {
                    return row.next();
                }
            }
        });
    }

    @Override
    public CompletionStage<Set<UUID>> ignoredPlayers(UUID ownerId) {
        return supply(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return findIgnoredPlayers(connection, ownerId);
            }
        });
    }

    @Override
    public CompletionStage<Void> setIgnoring(UUID ownerId, UUID ignoredId, boolean ignored) {
        return run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                if (ignored) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO %s (owner_id, ignored_id, created_at)
                            VALUES (?, ?, ?)
                            ON DUPLICATE KEY UPDATE ignored_id = VALUE(ignored_id)
                            """.formatted(ignoresTable))) {
                        statement.setString(1, ownerId.toString());
                        statement.setString(2, ignoredId.toString());
                        statement.setLong(3, System.currentTimeMillis());
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM " + ignoresTable
                                    + " WHERE owner_id = ? AND ignored_id = ?")) {
                        statement.setString(1, ownerId.toString());
                        statement.setString(2, ignoredId.toString());
                        statement.executeUpdate();
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    private void ensureSettings(
            Connection connection,
            UUID playerId,
            String requestedDefaultChannel
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (player_id, active_channel, direct_messages_enabled)
                VALUES (?, ?, TRUE)
                ON DUPLICATE KEY UPDATE player_id = VALUE(player_id)
                """.formatted(settingsTable))) {
            statement.setString(1, playerId.toString());
            statement.setString(2, requestedDefaultChannel);
            statement.executeUpdate();
        }
    }

    private void upsertPlayerName(
            Connection connection,
            UUID playerId,
            String username
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s (player_id, username, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    username = VALUE(username), updated_at = VALUE(updated_at)
                """.formatted(playersTable))) {
            statement.setString(1, playerId.toString());
            statement.setString(2, username);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void lockPlayer(Connection connection, UUID playerId) throws SQLException {
        ensureSettings(connection, playerId, defaultChannel);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id FROM " + settingsTable + " WHERE player_id = ? FOR UPDATE")) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("MariaDB could not lock player state");
                }
            }
        }
    }

    private PlayerSettings requireSettings(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active_channel, direct_messages_enabled FROM " + settingsTable
                        + " WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("MariaDB did not return player settings after upsert");
                }
                return new PlayerSettings(
                        playerId, row.getString("active_channel"),
                        row.getBoolean("direct_messages_enabled"));
            }
        }
    }

    private Optional<GroupMembership> findMembership(
            Connection connection,
            UUID playerId,
            boolean lock
    ) throws SQLException {
        String sql = """
                SELECT g.group_id, g.name, g.normalized_name, g.owner_id, g.visibility,
                       g.password_hash, m.role
                FROM %s m
                JOIN %s g ON g.group_id = m.group_id
                WHERE m.player_id = ?%s
                """.formatted(membersTable, groupsTable, lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new GroupMembership(
                        readStoredGroup(row).group(), playerId,
                        GroupRole.valueOf(row.getString("role"))));
            }
        }
    }

    private Set<UUID> findGroupMembers(Connection connection, UUID groupId) throws SQLException {
        Set<UUID> result = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_id FROM " + membersTable + " WHERE group_id = ?")) {
            statement.setString(1, groupId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(UUID.fromString(rows.getString("player_id")));
                }
            }
        }
        return Set.copyOf(result);
    }

    private Set<UUID> findIgnoredPlayers(Connection connection, UUID ownerId) throws SQLException {
        Set<UUID> result = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ignored_id FROM " + ignoresTable + " WHERE owner_id = ?")) {
            statement.setString(1, ownerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(UUID.fromString(rows.getString("ignored_id")));
                }
            }
        }
        return Set.copyOf(result);
    }

    private StoredGroup findStoredGroup(
            Connection connection,
            String normalizedName,
            boolean lock
    ) throws SQLException {
        String sql = "SELECT group_id, name, normalized_name, owner_id, visibility, password_hash FROM "
                + groupsTable + " WHERE normalized_name = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedName);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readStoredGroup(row) : null;
            }
        }
    }

    private StoredGroup findStoredGroupById(
            Connection connection,
            UUID groupId,
            boolean lock
    ) throws SQLException {
        String sql = "SELECT group_id, name, normalized_name, owner_id, visibility, password_hash FROM "
                + groupsTable + " WHERE group_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readStoredGroup(row) : null;
            }
        }
    }

    private static StoredGroup readStoredGroup(ResultSet row) throws SQLException {
        String passwordHash = row.getString("password_hash");
        return new StoredGroup(new Group(
                UUID.fromString(row.getString("group_id")),
                row.getString("name"),
                row.getString("normalized_name"),
                UUID.fromString(row.getString("owner_id")),
                GroupVisibility.valueOf(row.getString("visibility")),
                passwordHash != null && !passwordHash.isBlank()), passwordHash);
    }

    private void insertMember(
            Connection connection,
            UUID playerId,
            UUID groupId,
            GroupRole role
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + membersTable
                        + " (player_id, group_id, role, joined_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, groupId.toString());
            statement.setString(3, role.name());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private boolean validInvite(
            Connection connection,
            UUID invitedId,
            UUID groupId,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + invitesTable
                        + " WHERE invited_id = ? AND group_id = ? AND expires_at > ? FOR UPDATE")) {
            statement.setString(1, invitedId.toString());
            statement.setString(2, groupId.toString());
            statement.setLong(3, now);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return true;
                }
            }
        }
        deleteInvite(connection, invitedId, groupId);
        return false;
    }

    private void deleteInvite(Connection connection, UUID playerId, UUID groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + invitesTable + " WHERE invited_id = ? AND group_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, groupId.toString());
            statement.executeUpdate();
        }
    }

    private boolean memberExists(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + membersTable + " WHERE player_id = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private boolean groupNameExists(String normalizedName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM " + groupsTable + " WHERE normalized_name = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private <T> T transaction(TransactionWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        }
    }

    private <T> CompletionStage<T> supply(SqlSupplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (CompletionException error) {
                    throw error;
                } catch (SQLException | RuntimeException error) {
                    throw new CompletionException(error);
                }
            }, executor);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "MariaDB work queue is full", error));
        }
    }

    private CompletionStage<Void> run(SqlRunnable operation) {
        return supply(() -> {
            operation.run();
            return null;
        });
    }

    private static boolean isConstraintViolation(SQLException error) {
        SQLException current = error;
        while (current != null) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private static GroupException failure(GroupFailure failure, String message) {
        return new GroupException(failure, message);
    }

    private static String table(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid MariaDB table name");
        }
        return "`" + name + "`";
    }
}
