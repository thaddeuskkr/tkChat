package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.AccessDecision;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.core.model.RouteDecision;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.model.SenderContext;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

public final class ChatRouter {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private final ChannelRegistry channels;
    private final AccessController accessController;
    private final ChatStateProvider chatState;
    private final ChatPolicy policy;
    private final SlidingWindowRateLimiter rateLimiter;
    private final Clock clock;
    private final String groupBypassPermission;

    public ChatRouter(
            ChannelRegistry channels,
            AccessController accessController,
            SocialRepository socialRepository,
            ChatPolicy policy,
            Clock clock,
            String groupBypassPermission
    ) {
        this(channels, accessController, repositoryState(socialRepository), policy, clock,
                groupBypassPermission);
    }

    public ChatRouter(
            ChannelRegistry channels,
            AccessController accessController,
            ChatStateProvider chatState,
            ChatPolicy policy,
            Clock clock,
            String groupBypassPermission
    ) {
        this.channels = channels;
        this.accessController = accessController;
        this.chatState = chatState;
        this.policy = policy;
        this.rateLimiter = new SlidingWindowRateLimiter(policy.rateLimitMessages(), policy.rateLimitWindow());
        this.clock = clock;
        this.groupBypassPermission = groupBypassPermission;
    }

    public CompletionStage<RouteDecision> routeChannel(SenderContext sender, String channelId, String content) {
        ChannelDefinition channel = channels.find(channelId).orElse(null);
        if (channel == null) {
            return denied(DenialReason.UNKNOWN_CHANNEL, channelId);
        }
        RouteDecision invalid = validate(sender, content);
        if (invalid != null) {
            return CompletableFuture.completedFuture(invalid);
        }
        boolean containsLink = URL.matcher(content).find();
        return accessController.authorize(
                        sender, channel.sendPermission(), channel.bypassPermission(), containsLink)
                .<RouteDecision>thenApply(access -> access.allowed()
                        ? approved(sender, channel, RouteKind.CHANNEL, channel.id(), channel.displayName(), content,
                                Set.of(), access)
                        : new RouteDecision.Denied(access.denialReason(), ""))
                .exceptionally(error -> new RouteDecision.Denied(DenialReason.INTERNAL_ERROR, error.getMessage()));
    }

    public CompletionStage<RouteDecision> routeDirect(
            SenderContext sender,
            UUID recipientId,
            String recipientName,
            String content
    ) {
        RouteDecision invalid = validate(sender, content);
        if (invalid != null) {
            return CompletableFuture.completedFuture(invalid);
        }
        boolean containsLink = URL.matcher(content).find();
        return accessController.authorize(sender, "", "", containsLink)
                .thenCompose(access -> {
                    if (!access.allowed()) {
                        return CompletableFuture.completedFuture(
                                new RouteDecision.Denied(access.denialReason(), ""));
                    }
                    return chatState.directState(recipientId, sender.playerId(), "global")
                            .thenApply(state -> {
                                        if (!state.settings().directMessagesEnabled()) {
                                            return (RouteDecision) new RouteDecision.Denied(
                                                    DenialReason.DIRECT_MESSAGES_DISABLED, recipientName);
                                        }
                                        if (state.ignoringSender()) {
                                            return (RouteDecision) new RouteDecision.Denied(
                                                    DenialReason.IGNORED, recipientName);
                                        }
                                        ChannelDefinition direct = syntheticChannel(
                                                "direct", "Direct", "", "", "");
                                        return (RouteDecision) approved(sender, direct, RouteKind.DIRECT,
                                                recipientId.toString(),
                                                recipientName, content, Set.of(sender.playerId(), recipientId), access);
                                    });
                })
                .exceptionally(error -> new RouteDecision.Denied(DenialReason.STORAGE_UNAVAILABLE, error.getMessage()));
    }

    public CompletionStage<RouteDecision> routeGroup(SenderContext sender, String content) {
        RouteDecision invalid = validate(sender, content);
        if (invalid != null) {
            return CompletableFuture.completedFuture(invalid);
        }
        boolean containsLink = URL.matcher(content).find();
        return accessController.authorize(
                        sender, "tkchat.channel.group.send", groupBypassPermission, containsLink)
                .thenCompose(access -> {
                    if (!access.allowed()) {
                        return CompletableFuture.completedFuture(
                                new RouteDecision.Denied(access.denialReason(), ""));
                    }
                    return chatState.groupState(sender.playerId()).thenApply(state -> {
                        if (state.isEmpty()) {
                            return (RouteDecision) new RouteDecision.Denied(
                                    DenialReason.NOT_IN_GROUP, "");
                        }
                        ChatStateProvider.GroupState groupState = state.get();
                        ChannelDefinition group = syntheticChannel(
                                "group", "Group", "tkchat.channel.group.send",
                                "tkchat.channel.group.receive",
                                groupBypassPermission);
                        return (RouteDecision) approved(sender, group, RouteKind.GROUP,
                                groupState.group().id().toString(), groupState.group().name(),
                                content, groupState.members(), access);
                    });
                })
                .exceptionally(error -> new RouteDecision.Denied(DenialReason.STORAGE_UNAVAILABLE, error.getMessage()));
    }

    public void remove(UUID playerId) {
        rateLimiter.remove(playerId);
    }

    private RouteDecision validate(SenderContext sender, String content) {
        if (!chatState.ready(sender.playerId())) {
            return new RouteDecision.Denied(DenialReason.NOT_READY, "");
        }
        if (content == null || content.isBlank() || content.length() > policy.maxMessageLength()) {
            return new RouteDecision.Denied(DenialReason.INVALID_MESSAGE, "");
        }
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isISOControl(character)) {
                return new RouteDecision.Denied(DenialReason.INVALID_MESSAGE, "");
            }
        }
        if (!sender.bypassRateLimit() && !rateLimiter.tryAcquire(sender.playerId(), clock.instant())) {
            return new RouteDecision.Denied(DenialReason.RATE_LIMITED, "");
        }
        return null;
    }

    private RouteDecision.Approved approved(
            SenderContext sender,
            ChannelDefinition channel,
            RouteKind routeKind,
            String routeId,
            String routeDisplayName,
            String content,
            Set<UUID> recipients,
            AccessDecision access
    ) {
        Instant now = clock.instant();
        return new RouteDecision.Approved(new ApprovedMessage(
                UUID.randomUUID(), now, routeKind, routeId, routeDisplayName, channel.id(), channel.scope(),
                sender.playerId(), sender.playerName(), sender.serverId(), access.prefix(), access.suffix(),
                content, Set.of(), null, recipients));
    }

    private static ChannelDefinition syntheticChannel(
            String id,
            String name,
            String sendPermission,
            String receivePermission,
            String bypassPermission
    ) {
        return new ChannelDefinition(id, name, ChannelScope.GLOBAL, sendPermission, receivePermission,
                bypassPermission, List.of(),
                "<gray>[" + name + "]</gray> <prefix><name><suffix><dark_gray>: </dark_gray><message>");
    }

    private static CompletionStage<RouteDecision> denied(DenialReason reason, String detail) {
        return CompletableFuture.completedFuture(new RouteDecision.Denied(reason, detail));
    }

    private static ChatStateProvider repositoryState(SocialRepository repository) {
        return new ChatStateProvider() {
            @Override
            public CompletionStage<DirectState> directState(
                    UUID recipientId,
                    UUID senderId,
                    String defaultChannel
            ) {
                return repository.settings(recipientId, defaultChannel)
                        .thenCombine(repository.isIgnoring(recipientId, senderId), DirectState::new);
            }

            @Override
            public CompletionStage<java.util.Optional<GroupState>> groupState(UUID playerId) {
                return repository.groupForMember(playerId).thenCompose(membership -> {
                    if (membership.isEmpty()) {
                        return CompletableFuture.completedFuture(java.util.Optional.empty());
                    }
                    return repository.groupMembers(membership.get().group().id())
                            .thenApply(members -> java.util.Optional.of(
                                    new GroupState(membership.get().group(), members)));
                });
            }
        };
    }
}
