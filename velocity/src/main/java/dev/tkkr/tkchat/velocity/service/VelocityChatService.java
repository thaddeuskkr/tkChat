package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.core.model.RouteDecision;
import dev.tkkr.tkchat.core.model.SenderContext;
import dev.tkkr.tkchat.core.service.ChatRouter;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.Permissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.UUID;
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
    private final PerSenderTaskQueue outbound = new PerSenderTaskQueue();

    public VelocityChatService(
            ProxyServer proxy,
            ChatRouter router,
            MessageTransport transport,
            ConversationTracker conversations,
            ItemLinkService itemLinks,
            PlayerFormattingService formatting
    ) {
        this.proxy = proxy;
        this.router = router;
        this.transport = transport;
        this.conversations = conversations;
        this.itemLinks = itemLinks;
        this.formatting = formatting;
    }

    public void reconfigure(ChatRouter router) {
        this.router = router;
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
        return outbound.submit(senderId, () -> router.routeDirect(
                        context(sender), recipientId, recipient.getUsername(), content)
                .thenCompose(decision -> {
                    if (decision instanceof RouteDecision.Approved) {
                        conversations.recordIncoming(recipientId, senderId);
                    }
                    return publishOrExplain(sender, decision);
                }));
    }

    public CompletionStage<Void> reply(Player sender, String content) {
        UUID recipientId = conversations.partner(sender.getUniqueId()).orElse(null);
        if (recipientId == null) {
            sender.sendMessage(error("There is nobody to reply to."));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        Player recipient = proxy.getPlayer(recipientId).orElse(null);
        if (recipient == null) {
            sender.sendMessage(error("That player is no longer online."));
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

    private CompletionStage<Void> send(
            Player sender,
            Supplier<? extends CompletionStage<RouteDecision>> route
    ) {
        return outbound.submit(sender.getUniqueId(),
                () -> route.get().thenCompose(decision -> publishOrExplain(sender, decision)));
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
        }));
    }

    public CompletionStage<Void> publishOrExplain(Player sender, RouteDecision decision) {
        if (decision instanceof RouteDecision.Denied denied) {
            sender.sendMessage(denial(denied.reason()));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        RouteDecision.Approved approved = (RouteDecision.Approved) decision;
        return itemLinks.enrich(sender, approved.message().withFormatting(formatting.allowed(sender)))
                .thenCompose(transport::publish)
                .exceptionally(failure -> {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
                    sender.sendMessage(error(cause instanceof ItemLinkService.ItemLinkException
                            ? cause.getMessage()
                            : "Chat delivery failed. Please try again."));
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
        outbound.remove(playerId);
    }

    public static Component denial(DenialReason reason) {
        return error(switch (reason) {
            case NOT_READY -> "Chat is still starting. Please try again shortly.";
            case UNKNOWN_CHANNEL -> "That chat channel does not exist.";
            case NO_PERMISSION -> "You do not have permission to send that message.";
            case MUTED -> "You are muted.";
            case LINKS_NOT_ALLOWED -> "You do not have permission to send links.";
            case RATE_LIMITED -> "You are sending messages too quickly.";
            case INVALID_MESSAGE -> "That message is empty, too long, or contains invalid characters.";
            case DIRECT_MESSAGES_DISABLED -> "That player has direct messages disabled.";
            case IGNORED -> "That player is not accepting messages from you.";
            case NOT_IN_GROUP -> "You are not in a group.";
            case STORAGE_UNAVAILABLE -> "Chat storage is temporarily unavailable.";
            case INTERNAL_ERROR -> "Chat moderation is temporarily unavailable.";
        });
    }

    public static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    public static Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }
}
