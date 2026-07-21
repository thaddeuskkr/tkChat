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
     * Stored in the existing formatting collection so presentation variants remain wire-compatible
     * with 0.3.x proxies during a rolling upgrade. Older proxies safely ignore unknown keys.
     */
    private static final String ACTION_MARKER = "tkchat:action";
    private static final String JOIN_MARKER = "tkchat:join";
    private static final String LEAVE_MARKER = "tkchat:leave";
    private static final String GLOBAL_JOIN_MARKER = "tkchat:global_join";
    private static final String GLOBAL_LEAVE_MARKER = "tkchat:global_leave";

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
        return withMarker(ACTION_MARKER);
    }

    public boolean hasActionMarker() {
        return formatting.contains(ACTION_MARKER);
    }

    public ApprovedMessage asJoinMessage() {
        return withMarker(JOIN_MARKER);
    }

    public boolean hasJoinMarker() {
        return formatting.contains(JOIN_MARKER);
    }

    public ApprovedMessage asLeaveMessage() {
        return withMarker(LEAVE_MARKER);
    }

    public boolean hasLeaveMarker() {
        return formatting.contains(LEAVE_MARKER);
    }

    public ApprovedMessage asGlobalJoinMessage() {
        return withMarker(GLOBAL_JOIN_MARKER);
    }

    public boolean hasGlobalJoinMarker() {
        return formatting.contains(GLOBAL_JOIN_MARKER);
    }

    public ApprovedMessage asGlobalLeaveMessage() {
        return withMarker(GLOBAL_LEAVE_MARKER);
    }

    public boolean hasGlobalLeaveMarker() {
        return formatting.contains(GLOBAL_LEAVE_MARKER);
    }

    private ApprovedMessage withMarker(String marker) {
        if (formatting.contains(marker)) {
            return this;
        }
        HashSet<String> replacement = new HashSet<>(formatting);
        replacement.add(marker);
        return withFormatting(replacement);
    }
}
