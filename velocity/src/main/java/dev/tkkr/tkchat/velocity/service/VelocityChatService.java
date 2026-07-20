package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.core.model.RouteDecision;
import dev.tkkr.tkchat.core.model.SenderContext;
import dev.tkkr.tkchat.core.service.ChatRouter;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.config.ResponseKey;

import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class VelocityChatService {
    private final ProxyServer proxy;
    private volatile ChatRouter router;
    private final MessageTransport transport;
    private final ConversationTracker conversations;
    private final ItemLinkService itemLinks;
    private final PlayerFormattingService formatting;
    private final ResponseService responses;
    private final PerSenderTaskQueue outbound;

    public VelocityChatService(
            ProxyServer proxy,
            ChatRouter router,
            MessageTransport transport,
            ConversationTracker conversations,
            ItemLinkService itemLinks,
            PlayerFormattingService formatting,
            ResponseService responses,
            int maxPendingMessagesPerSender,
            Duration maxMessageAge
    ) {
        this.proxy = proxy;
        this.router = router;
        this.transport = transport;
        this.conversations = conversations;
        this.itemLinks = itemLinks;
        this.formatting = formatting;
        this.responses = responses;
        this.outbound = new PerSenderTaskQueue(
                maxPendingMessagesPerSender, maxMessageAge, java.time.Clock.systemUTC());
    }

    public void reconfigure(
            ChatRouter router,
            int maxPendingMessagesPerSender,
            Duration maxMessageAge
    ) {
        this.router = router;
        outbound.reconfigure(maxPendingMessagesPerSender, maxMessageAge);
    }

    public CompletionStage<Void> channel(Player sender, String channelId, String content) {
        return send(sender, () -> router.routeChannel(context(sender), channelId, content));
    }

    public CompletionStage<RouteDecision> interceptChannel(
            Player sender,
            String channelId,
            String content
    ) {
        return intercept(sender, () -> router.routeChannel(context(sender), channelId, content));
    }

    public CompletionStage<Void> direct(Player sender, Player recipient, String content) {
        UUID senderId = sender.getUniqueId();
        UUID recipientId = recipient.getUniqueId();
        conversations.recordOutgoing(senderId, recipientId);
        return handleQueueFailure(sender, outbound.submit(senderId, () -> router.routeDirect(
                        context(sender), recipientId, recipient.getUsername(), content)
                .thenCompose(decision -> {
                    if (decision instanceof RouteDecision.Approved) {
                        conversations.recordIncoming(recipientId, senderId);
                    }
                    return publishOrExplain(sender, decision);
                })));
    }

    public CompletionStage<Void> reply(Player sender, String content) {
        UUID recipientId = conversations.partner(sender.getUniqueId()).orElse(null);
        if (recipientId == null) {
            sender.sendMessage(responses.message(ResponseKey.REPLY_NOBODY));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        Player recipient = proxy.getPlayer(recipientId).orElse(null);
        if (recipient == null) {
            sender.sendMessage(responses.message(ResponseKey.REPLY_OFFLINE));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return direct(sender, recipient, content);
    }

    public CompletionStage<Void> group(Player sender, String content) {
        return send(sender, () -> router.routeGroup(context(sender), content));
    }

    public CompletionStage<RouteDecision> interceptGroup(Player sender, String content) {
        return intercept(sender, () -> router.routeGroup(context(sender), content));
    }

    public CompletionStage<Void> action(Player sender, String channelId, String content) {
        Supplier<? extends CompletionStage<RouteDecision>> route =
                GroupChannels.groupId(channelId).isPresent()
                        ? () -> router.routeGroup(context(sender), content)
                        : () -> router.routeChannel(context(sender), channelId, content);
        return handleQueueFailure(sender, outbound.submit(sender.getUniqueId(),
                () -> route.get().thenCompose(decision -> publishOrExplain(
                        sender, decision, true))));
    }

    private CompletionStage<Void> send(
            Player sender,
            Supplier<? extends CompletionStage<RouteDecision>> route
    ) {
        return handleQueueFailure(sender, outbound.submit(sender.getUniqueId(),
                () -> route.get().thenCompose(decision -> publishOrExplain(sender, decision))));
    }

    private CompletionStage<RouteDecision> intercept(
            Player sender,
            Supplier<? extends CompletionStage<RouteDecision>> route
    ) {
        return outbound.submit(sender.getUniqueId(), () -> route.get().thenCompose(decision -> {
            if (decision instanceof RouteDecision.Denied) {
                return CompletableFuture.completedFuture(decision);
            }
            return publishOrExplain(sender, decision).thenApply(ignored -> decision);
        })).exceptionally(error -> {
            PerSenderTaskQueue.QueueRejectedException rejected = queueRejection(error);
            if (rejected == null) {
                throw new CompletionException(unwrap(error));
            }
            return new RouteDecision.Denied(
                    rejected.reason() == PerSenderTaskQueue.RejectionReason.FULL
                            ? DenialReason.CHAT_BACKLOG_FULL
                            : DenialReason.MESSAGE_EXPIRED,
                    "");
        });
    }

    public CompletionStage<Void> publishOrExplain(Player sender, RouteDecision decision) {
        return publishOrExplain(sender, decision, false);
    }

    private CompletionStage<Void> publishOrExplain(
            Player sender,
            RouteDecision decision,
            boolean action
    ) {
        if (decision instanceof RouteDecision.Denied denied) {
            sender.sendMessage(responses.denial(denied.reason()));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        RouteDecision.Approved approved = (RouteDecision.Approved) decision;
        var prepared = approved.message().withFormatting(formatting.allowed(sender));
        if (action) {
            prepared = prepared.asAction();
        }
        return itemLinks.enrich(sender, prepared)
                .thenCompose(transport::publish)
                .exceptionally(failure -> {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
                    sender.sendMessage(responses.message(
                            cause instanceof ItemLinkService.ItemLinkException
                                    ? ResponseKey.FEEDBACK_ITEM_LINK_FAILED
                                    : ResponseKey.FEEDBACK_DELIVERY_FAILED));
                    return null;
                });
    }

    public SenderContext context(Player player) {
        String serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("unknown");
        return new SenderContext(player.getUniqueId(), player.getUsername(), serverId,
                player.getRemoteAddress().getAddress(), Map.of(),
                player.hasPermission(Permissions.BYPASS_RATE_LIMIT));
    }

    public void remove(UUID playerId) {
        router.remove(playerId);
        outbound.remove(playerId);
    }

    private CompletionStage<Void> handleQueueFailure(
            Player sender,
            CompletionStage<Void> stage
    ) {
        return stage.exceptionally(error -> {
            PerSenderTaskQueue.QueueRejectedException rejected = queueRejection(error);
            if (rejected == null) {
                throw new CompletionException(unwrap(error));
            }
            sender.sendMessage(responses.denial(rejected.reason() == PerSenderTaskQueue.RejectionReason.FULL
                    ? DenialReason.CHAT_BACKLOG_FULL
                    : DenialReason.MESSAGE_EXPIRED));
            return null;
        });
    }

    private static PerSenderTaskQueue.QueueRejectedException queueRejection(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof PerSenderTaskQueue.QueueRejectedException rejected ? rejected : null;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
