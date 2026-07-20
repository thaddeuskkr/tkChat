package dev.tkkr.tkchat.velocity.storage;

import com.mongodb.ConnectionString;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.UpdateResult;
import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupLeaveResult;
import dev.tkkr.tkchat.core.model.GroupMembership;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.model.PlayerSettings;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.core.service.GroupNames;
import dev.tkkr.tkchat.core.service.GroupPasswords;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Updates.addToSet;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.pull;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * MongoDB-backed social state. Groups are stored as single documents so a standalone MongoDB
 * server can provide the required atomic membership update without replica-set transactions.
 */
public final class MongoSocialRepository implements SocialRepository {
    private final MongoClient client;
    private final MongoCollection<Document> settings;
    private final MongoCollection<Document> groups;
    private final MongoCollection<Document> invites;
    private final MongoCollection<Document> ignores;
    private volatile String defaultChannel;
    private final ExecutorService executor;

    public MongoSocialRepository(AppConfig.MongoDb config, String defaultChannel) {
        this.defaultChannel = defaultChannel;
        this.executor = new ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxQueuedOperations),
                Thread.ofPlatform().name("tkchat-mongodb-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        MongoClientSettings clientSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.connectionString))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(
                        config.serverSelectionTimeoutMillis, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                        .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS))
                .timeout(config.operationTimeoutMillis, TimeUnit.MILLISECONDS)
                .build();
        client = MongoClients.create(clientSettings);
        try {
            MongoDatabase database = client.getDatabase(config.database);
            String prefix = config.collectionPrefix + "_";
            settings = database.getCollection(prefix + "player_settings");
            groups = database.getCollection(prefix + "groups");
            invites = database.getCollection(prefix + "group_invites");
            ignores = database.getCollection(prefix + "ignores");
            database.runCommand(new Document("ping", 1));
            createIndexes();
        } catch (RuntimeException error) {
            client.close();
            executor.shutdownNow();
            throw error;
        }
    }

    @Override
    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    private void createIndexes() {
        groups.createIndex(ascending("normalizedName"), new IndexOptions().unique(true));
        groups.createIndex(ascending("members.playerId"), new IndexOptions().unique(true));
        invites.createIndex(ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        invites.createIndex(ascending("groupId"));
        invites.createIndex(new Document("invitedId", 1).append("groupId", 1),
                new IndexOptions().unique(true));
        ignores.createIndex(new Document("ownerId", 1).append("ignoredId", 1),
                new IndexOptions().unique(true));
    }

    @Override
    public CompletionStage<PlayerSettings> settings(UUID playerId, String requestedDefaultChannel) {
        return supply(() -> {
            Document document = settings.findOneAndUpdate(
                    eq("_id", playerId.toString()),
                    combine(
                            setOnInsert("activeChannel", requestedDefaultChannel),
                            setOnInsert("directMessagesEnabled", true)
                    ),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
            );
            if (document == null) {
                throw new IllegalStateException("MongoDB did not return player settings after upsert");
            }
            return playerSettings(document);
        });
    }

    @Override
    public CompletionStage<Void> setActiveChannel(UUID playerId, String channelId) {
        return run(() -> settings.updateOne(
                eq("_id", playerId.toString()),
                combine(set("activeChannel", channelId), setOnInsert("directMessagesEnabled", true)),
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        ));
    }

    @Override
    public CompletionStage<Void> setDirectMessagesEnabled(UUID playerId, boolean enabled) {
        return run(() -> settings.updateOne(
                eq("_id", playerId.toString()),
                combine(set("directMessagesEnabled", enabled), setOnInsert("activeChannel", defaultChannel)),
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        ));
    }

    @Override
    public CompletionStage<Group> createGroup(
            UUID ownerId,
            String name,
            GroupVisibility visibility,
            String password
    ) {
        return supply(() -> {
            if (groups.find(eq("members.playerId", ownerId.toString())).first() != null) {
                throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
            }
            String normalizedName = GroupNames.normalize(name);
            if (groups.find(eq("normalizedName", normalizedName)).first() != null) {
                throw failure(GroupFailure.NAME_TAKEN, "A group with that name already exists");
            }
            String passwordHash = visibility == GroupVisibility.PRIVATE ? GroupPasswords.hash(password) : null;
            UUID groupId = UUID.randomUUID();
            Document member = member(ownerId, GroupRole.OWNER);
            Document group = new Document("_id", groupId.toString())
                    .append("name", name)
                    .append("normalizedName", normalizedName)
                    .append("ownerId", ownerId.toString())
                    .append("visibility", visibility.name())
                    .append("passwordHash", passwordHash)
                    .append("createdAt", Date.from(Instant.now()))
                    .append("members", List.of(member));
            try {
                groups.insertOne(group);
            } catch (DuplicateKeyException error) {
                GroupFailure reason = groups.find(eq("normalizedName", normalizedName)).first() == null
                        ? GroupFailure.ALREADY_MEMBER
                        : GroupFailure.NAME_TAKEN;
                throw new GroupException(reason, "Group uniqueness check failed", error);
            }
            return group(group);
        });
    }

    @Override
    public CompletionStage<Optional<Group>> groupByName(String name) {
        return supply(() -> Optional.ofNullable(findGroup(name)).map(MongoSocialRepository::group));
    }

    @Override
    public CompletionStage<List<Group>> listGroups(boolean includePrivate) {
        return supply(() -> {
            List<Group> result = new ArrayList<>();
            var iterable = includePrivate ? groups.find() : groups.find(eq("visibility", GroupVisibility.PUBLIC.name()));
            for (Document document : iterable) {
                result.add(group(document));
            }
            result.sort(Comparator.comparing(Group::normalizedName));
            return List.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Optional<GroupMembership>> groupForMember(UUID playerId) {
        return supply(() -> Optional.ofNullable(groups.find(eq("members.playerId", playerId.toString())).first())
                .map(document -> membership(document, playerId)));
    }

    @Override
    public CompletionStage<Set<UUID>> groupMembers(UUID groupId) {
        return supply(() -> {
            Document group = groups.find(eq("_id", groupId.toString())).first();
            if (group == null) {
                return Set.of();
            }
            Set<UUID> result = new HashSet<>();
            for (Document member : group.getList("members", Document.class, List.of())) {
                result.add(UUID.fromString(member.getString("playerId")));
            }
            return Set.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Void> invite(UUID groupId, UUID inviterId, UUID invitedId, Instant expiresAt) {
        return run(() -> {
            Document group = groups.find(and(
                    eq("_id", groupId.toString()),
                    eq("ownerId", inviterId.toString()))).first();
            if (group == null) {
                throw failure(GroupFailure.NOT_OWNER, "Only the group owner can invite players");
            }
            if (groups.find(eq("members.playerId", invitedId.toString())).first() != null) {
                throw failure(GroupFailure.ALREADY_MEMBER, "That player is already in a group");
            }
            String id = inviteId(invitedId, groupId);
            invites.replaceOne(eq("_id", id),
                    new Document("_id", id)
                            .append("groupId", groupId.toString())
                            .append("inviterId", inviterId.toString())
                            .append("invitedId", invitedId.toString())
                            .append("expiresAt", Date.from(expiresAt)),
                    new ReplaceOptions().upsert(true));
        });
    }

    @Override
    public CompletionStage<Group> acceptInvite(UUID invitedId, String groupName, Instant now) {
        return supply(() -> joinInternal(invitedId, groupName, null, false, true, now));
    }

    @Override
    public CompletionStage<Group> joinGroup(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            Instant now
    ) {
        return supply(() -> joinInternal(playerId, groupName, password, bypass, false, now));
    }

    private Group joinInternal(
            UUID playerId,
            String groupName,
            String password,
            boolean bypass,
            boolean requireInvite,
            Instant now
    ) {
        Document groupDocument = findGroup(groupName);
        if (groupDocument == null) {
            throw failure(GroupFailure.NOT_FOUND, "That group does not exist");
        }
        if (groups.find(eq("members.playerId", playerId.toString())).first() != null) {
            throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
        }
        String groupId = groupDocument.getString("_id");
        Bson inviteFilter = and(
                eq("invitedId", playerId.toString()),
                eq("groupId", groupId),
                gt("expiresAt", Date.from(now)));
        Document invite = invites.find(inviteFilter).first();
        if (requireInvite && invite == null) {
            throw failure(GroupFailure.INVITE_MISSING_OR_EXPIRED, "Invite is missing or expired");
        }

        GroupVisibility visibility = GroupVisibility.valueOf(groupDocument.getString("visibility"));
        String passwordHash = groupDocument.getString("passwordHash");
        boolean allowed = visibility == GroupVisibility.PUBLIC || bypass || invite != null;
        if (!allowed && (passwordHash == null || passwordHash.isBlank())) {
            throw failure(GroupFailure.INVITE_REQUIRED, "This private group requires an invitation");
        }
        if (!allowed && !GroupPasswords.matches(password, passwordHash)) {
            throw failure(GroupFailure.INVALID_PASSWORD, "The group password is incorrect");
        }

        try {
            UpdateResult result = groups.updateOne(
                    and(eq("_id", groupId), ne("members.playerId", playerId.toString())),
                    addToSet("members", member(playerId, GroupRole.MEMBER)));
            if (result.getMatchedCount() == 0) {
                if (groups.find(eq("_id", groupId)).first() == null) {
                    throw failure(GroupFailure.NOT_FOUND, "Group no longer exists");
                }
                throw failure(GroupFailure.ALREADY_MEMBER, "Player is already in a group");
            }
        } catch (DuplicateKeyException error) {
            throw new GroupException(GroupFailure.ALREADY_MEMBER, "Player is already in a group", error);
        }
        if (invite != null) {
            invites.deleteOne(inviteFilter);
        }
        return group(groupDocument);
    }

    @Override
    public CompletionStage<GroupLeaveResult> leaveGroup(UUID playerId) {
        return supply(() -> {
            Document document = groups.find(eq("members.playerId", playerId.toString())).first();
            if (document == null) {
                throw failure(GroupFailure.NOT_MEMBER, "Player is not in a group");
            }
            Group group = group(document);
            boolean deleted = playerId.toString().equals(document.getString("ownerId"));
            Set<UUID> affected;
            if (deleted) {
                affected = memberIds(document);
                groups.deleteOne(eq("_id", group.id().toString()));
                invites.deleteMany(eq("groupId", group.id().toString()));
            } else {
                affected = Set.of(playerId);
                groups.updateOne(eq("_id", group.id().toString()),
                        pull("members", new Document("playerId", playerId.toString())));
            }
            List<String> ids = affected.stream().map(UUID::toString).toList();
            settings.updateMany(and(
                            in("_id", ids),
                            eq("activeChannel", GroupChannels.id(group.id()))),
                    set("activeChannel", defaultChannel));
            return new GroupLeaveResult(group, affected, deleted);
        });
    }

    @Override
    public CompletionStage<Boolean> isIgnoring(UUID ownerId, UUID ignoredId) {
        return supply(() -> ignores.find(eq("_id", ignoreId(ownerId, ignoredId))).first() != null);
    }

    @Override
    public CompletionStage<Set<UUID>> ignoredPlayers(UUID ownerId) {
        return supply(() -> {
            Set<UUID> result = new HashSet<>();
            for (Document document : ignores.find(eq("ownerId", ownerId.toString()))) {
                result.add(UUID.fromString(document.getString("ignoredId")));
            }
            return Set.copyOf(result);
        });
    }

    @Override
    public CompletionStage<Void> setIgnoring(UUID ownerId, UUID ignoredId, boolean ignored) {
        return run(() -> {
            String id = ignoreId(ownerId, ignoredId);
            if (ignored) {
                ignores.replaceOne(eq("_id", id),
                        new Document("_id", id)
                                .append("ownerId", ownerId.toString())
                                .append("ignoredId", ignoredId.toString())
                                .append("createdAt", Date.from(Instant.now())),
                        new ReplaceOptions().upsert(true));
            } else {
                ignores.deleteOne(eq("_id", id));
            }
        });
    }

    @Override
    public void close() {
        client.close();
        executor.shutdownNow();
    }

    private Document findGroup(String name) {
        return groups.find(eq("normalizedName", GroupNames.normalize(name))).first();
    }

    private static Document member(UUID playerId, GroupRole role) {
        return new Document("playerId", playerId.toString()).append("role", role.name());
    }

    private static Set<UUID> memberIds(Document document) {
        return document.getList("members", Document.class, List.of()).stream()
                .map(member -> UUID.fromString(member.getString("playerId")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static PlayerSettings playerSettings(Document document) {
        return new PlayerSettings(
                UUID.fromString(document.getString("_id")),
                document.getString("activeChannel"),
                document.getBoolean("directMessagesEnabled", true)
        );
    }

    private static GroupMembership membership(Document document, UUID playerId) {
        GroupRole role = document.getList("members", Document.class, List.of()).stream()
                .filter(member -> playerId.toString().equals(member.getString("playerId")))
                .map(member -> GroupRole.valueOf(member.getString("role")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MongoDB group membership is inconsistent"));
        return new GroupMembership(group(document), playerId, role);
    }

    private static Group group(Document document) {
        String passwordHash = document.getString("passwordHash");
        return new Group(
                UUID.fromString(document.getString("_id")),
                document.getString("name"),
                document.getString("normalizedName"),
                UUID.fromString(document.getString("ownerId")),
                GroupVisibility.valueOf(document.getString("visibility")),
                passwordHash != null && !passwordHash.isBlank()
        );
    }

    private static GroupException failure(GroupFailure failure, String message) {
        return new GroupException(failure, message);
    }

    private static String inviteId(UUID invitedId, UUID groupId) {
        return invitedId + ":" + groupId;
    }

    private static String ignoreId(UUID ownerId, UUID ignoredId) {
        return ownerId + ":" + ignoredId;
    }

    private <T> CompletionStage<T> supply(java.util.function.Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (CompletionException error) {
                    throw error;
                } catch (RuntimeException error) {
                    throw new CompletionException(error);
                }
            }, executor);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "MongoDB work queue is full", error));
        }
    }

    private CompletionStage<Void> run(Runnable runnable) {
        try {
            return CompletableFuture.runAsync(runnable, executor);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "MongoDB work queue is full", error));
        }
    }
}
