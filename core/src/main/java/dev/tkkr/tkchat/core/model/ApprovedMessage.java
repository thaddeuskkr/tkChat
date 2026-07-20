package dev.tkkr.tkchat.core.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ApprovedMessage(
        UUID messageId,
        Instant createdAt,
        RouteKind routeKind,
        String routeId,
        String routeDisplayName,
        String channelId,
        ChannelScope channelScope,
        UUID senderId,
        String senderName,
        String senderServerId,
        String senderPrefix,
        String senderSuffix,
        String content,
        Set<String> formatting,
        ItemLink itemLink,
        Set<UUID> recipients
) {
    /**
     * Stored in the existing formatting collection so action messages remain wire-compatible with
     * 0.3.x proxies during a rolling upgrade. Older proxies safely ignore unknown formatting keys.
     */
    private static final String ACTION_MARKER = "tkchat:action";

    public ApprovedMessage {
        messageId = Objects.requireNonNull(messageId, "messageId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        routeKind = Objects.requireNonNull(routeKind, "routeKind");
        routeId = Objects.requireNonNull(routeId, "routeId");
        routeDisplayName = Objects.requireNonNull(routeDisplayName, "routeDisplayName");
        channelId = Objects.requireNonNull(channelId, "channelId");
        channelScope = Objects.requireNonNull(channelScope, "channelScope");
        senderId = Objects.requireNonNull(senderId, "senderId");
        senderName = Objects.requireNonNull(senderName, "senderName");
        senderServerId = Objects.requireNonNull(senderServerId, "senderServerId");
        senderPrefix = senderPrefix == null ? "" : senderPrefix;
        senderSuffix = senderSuffix == null ? "" : senderSuffix;
        content = Objects.requireNonNull(content, "content");
        formatting = Set.copyOf(formatting == null ? Set.of() : formatting);
        recipients = Set.copyOf(recipients == null ? Set.of() : recipients);
    }

    public ApprovedMessage withItemLink(ItemLink replacement) {
        return new ApprovedMessage(
                messageId, createdAt, routeKind, routeId, routeDisplayName, channelId, channelScope,
                senderId, senderName, senderServerId, senderPrefix, senderSuffix, content,
                formatting, replacement, recipients);
    }

    public ApprovedMessage withFormatting(Set<String> replacement) {
        return new ApprovedMessage(
                messageId, createdAt, routeKind, routeId, routeDisplayName, channelId, channelScope,
                senderId, senderName, senderServerId, senderPrefix, senderSuffix, content,
                replacement, itemLink, recipients);
    }

    public ApprovedMessage asAction() {
        if (hasActionMarker()) {
            return this;
        }
        HashSet<String> replacement = new HashSet<>(formatting);
        replacement.add(ACTION_MARKER);
        return withFormatting(replacement);
    }

    public boolean hasActionMarker() {
        return formatting.contains(ACTION_MARKER);
    }
}
