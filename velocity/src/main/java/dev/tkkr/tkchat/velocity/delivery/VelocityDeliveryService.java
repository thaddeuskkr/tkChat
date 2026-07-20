package dev.tkkr.tkchat.velocity.delivery;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.ItemLink;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.PlayerFormattingService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;
import dev.tkkr.tkchat.velocity.transport.RecentMessageIds;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VelocityDeliveryService {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+");

    private record PreparedMessage(Component content, Component prefix, Component suffix) {
    }

    private final ProxyServer proxy;
    private volatile ChannelRegistry channels;
    private final VelocityAccessController access;
    private volatile AppConfig.Formats formats;
    private volatile AppConfig.Mentions mentions;
    private volatile AppConfig.ItemLinks itemLinks;
    private final PlayerFormattingService playerFormatting;
    private final PlayerStateService states;
    private final SocialSpyService spies;
    private volatile int clearLines;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final RecentMessageIds recentIds = new RecentMessageIds(Duration.ofMinutes(2), Clock.systemUTC());
    private final Clock clock = Clock.systemUTC();
    private volatile Duration maxDeliveryAge;

    public VelocityDeliveryService(
            ProxyServer proxy,
            ChannelRegistry channels,
            VelocityAccessController access,
            AppConfig.Formats formats,
            AppConfig.Mentions mentions,
            AppConfig.ItemLinks itemLinks,
            PlayerFormattingService playerFormatting,
            PlayerStateService states,
            SocialSpyService spies,
            int clearLines,
            Duration maxDeliveryAge
    ) {
        this.proxy = proxy;
        this.channels = channels;
        this.access = access;
        this.formats = formats;
        this.mentions = mentions;
        this.itemLinks = itemLinks;
        this.playerFormatting = playerFormatting;
        this.states = states;
        this.spies = spies;
        this.clearLines = clearLines;
        this.maxDeliveryAge = maxDeliveryAge;
    }

    public void reconfigure(
            ChannelRegistry channels,
            AppConfig.Formats formats,
            AppConfig.Mentions mentions,
            AppConfig.ItemLinks itemLinks,
            int clearLines,
            Duration maxDeliveryAge
    ) {
        this.channels = channels;
        this.formats = formats;
        this.mentions = mentions;
        this.itemLinks = itemLinks;
        this.clearLines = clearLines;
        this.maxDeliveryAge = maxDeliveryAge;
    }

    public void deliver(ApprovedMessage message) {
        if (message.createdAt().isBefore(clock.instant().minus(maxDeliveryAge))) {
            return;
        }
        if (!recentIds.first(message.messageId())) {
            return;
        }
        PreparedMessage prepared = prepare(message);
        Map<String, Component> rendered = new HashMap<>();
        if (isAddressed(message)) {
            deliverAddressed(message, prepared, rendered);
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            boolean normalRecipient = shouldReceive(player, message);
            if (message.routeKind() == RouteKind.CHAT_CLEAR && normalRecipient) {
                clearAndSend(player, message, prepared, rendered);
            } else if (normalRecipient) {
                player.sendMessage(render(message, player, prepared, rendered));
            } else if (shouldSpy(player, message)) {
                player.sendMessage(renderSpy(message, player, prepared, rendered));
            }
        }
    }

    private void deliverAddressed(
            ApprovedMessage message,
            PreparedMessage prepared,
            Map<String, Component> rendered
    ) {
        Set<UUID> delivered = new HashSet<>();
        for (UUID recipientId : message.recipients()) {
            proxy.getPlayer(recipientId).ifPresent(player -> {
                if (shouldReceive(player, message)) {
                    player.sendMessage(render(message, player, prepared, rendered));
                    delivered.add(recipientId);
                }
            });
        }
        for (UUID spyId : spies.enabledPlayers()) {
            if (delivered.contains(spyId)) {
                continue;
            }
            proxy.getPlayer(spyId).ifPresent(player -> {
                if (shouldSpy(player, message)) {
                    player.sendMessage(renderSpy(message, player, prepared, rendered));
                }
            });
        }
    }

    private void clearAndSend(
            Player player,
            ApprovedMessage message,
            PreparedMessage prepared,
            Map<String, Component> rendered
    ) {
        if (!access.hasPermission(player, Permissions.BYPASS_CHAT_CLEAR)) {
            for (int line = 0; line < clearLines; line++) {
                player.sendMessage(Component.empty());
            }
        }
        player.sendMessage(render(message, player, prepared, rendered));
    }

    private static boolean isAddressed(ApprovedMessage message) {
        return message.routeKind() == RouteKind.DIRECT || message.routeKind() == RouteKind.GROUP;
    }

    private boolean shouldReceive(Player player, ApprovedMessage message) {
        if (message.routeKind() == RouteKind.BROADCAST) {
            return true;
        }
        if (message.routeKind() == RouteKind.CHAT_CLEAR) {
            return message.channelScope() == ChannelScope.GLOBAL
                    || player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName()
                            .equals(message.senderServerId()))
                    .orElse(false);
        }
        if (message.routeKind() != RouteKind.CHANNEL) {
            if (!message.recipients().contains(player.getUniqueId())) {
                return false;
            }
            if (player.getUniqueId().equals(message.senderId())) {
                return true;
            }
            if (states.isIgnoring(player.getUniqueId(), message.senderId())) {
                return false;
            }
            return message.routeKind() != RouteKind.GROUP
                    || access.hasPermission(player, "tkchat.channels.group.receive")
                    || access.hasPermission(player, Permissions.BYPASS_CHANNEL_RESTRICTIONS);
        }
        ChannelDefinition channel = channels.find(message.channelId()).orElse(null);
        if (channel == null || (!access.hasPermission(player, channel.receivePermission())
                && !access.hasPermission(player, channel.bypassPermission())
                && !access.hasPermission(player, Permissions.BYPASS_CHANNEL_RESTRICTIONS))) {
            return false;
        }
        if (!player.getUniqueId().equals(message.senderId())
                && states.isIgnoring(player.getUniqueId(), message.senderId())) {
            return false;
        }
        if (message.channelScope() == ChannelScope.SERVER) {
            return player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName().equals(message.senderServerId()))
                    .orElse(false);
        }
        return true;
    }

    private boolean shouldSpy(Player player, ApprovedMessage message) {
        if (message.routeKind() == RouteKind.BROADCAST || message.routeKind() == RouteKind.CHAT_CLEAR
                || player.getUniqueId().equals(message.senderId())) {
            return false;
        }
        return spies.enabled(player.getUniqueId())
                && access.hasPermission(player, Permissions.command("socialspy"));
    }

    private Component render(
            ApprovedMessage message,
            Player viewer,
            PreparedMessage prepared,
            Map<String, Component> rendered
    ) {
        String template;
        if (message.routeKind() == RouteKind.DIRECT) {
            template = viewer.getUniqueId().equals(message.senderId())
                    ? formats.directOutgoing
                    : formats.directIncoming;
        } else if (message.routeKind() == RouteKind.GROUP) {
            template = formats.group;
        } else if (message.routeKind() == RouteKind.BROADCAST) {
            template = formats.broadcast;
        } else if (message.routeKind() == RouteKind.CHAT_CLEAR) {
            template = formats.chatClear;
        } else {
            template = channels.find(message.channelId()).map(ChannelDefinition::format).orElse("<message>");
        }
        return renderTemplate(template, message, viewer, prepared, rendered);
    }

    private Component renderSpy(
            ApprovedMessage message,
            Player viewer,
            PreparedMessage prepared,
            Map<String, Component> rendered
    ) {
        return renderTemplate(formats.socialSpy, message, viewer, prepared, rendered);
    }

    private PreparedMessage prepare(ApprovedMessage message) {
        Component content = linkify(linkItems(
                playerFormatting.render(message.content(), message.formatting()), message.itemLink()));
        return new PreparedMessage(
                content, trustedMeta(message.senderPrefix()), trustedMeta(message.senderSuffix()));
    }

    private Component renderTemplate(
            String template,
            ApprovedMessage message,
            Player viewer,
            PreparedMessage prepared,
            Map<String, Component> rendered
    ) {
        AppConfig.Mentions mentionConfig = mentions;
        boolean viewerSpecific = mentionConfig.enabled
                && message.content().toLowerCase(Locale.ROOT)
                .contains(mentionConfig.prefix.toLowerCase(Locale.ROOT));
        if (!viewerSpecific) {
            return rendered.computeIfAbsent(template,
                    ignored -> deserializeTemplate(template, message, prepared.content(), prepared));
        }
        Component content = mention(prepared.content(), message, viewer);
        return deserializeTemplate(template, message, content, prepared);
    }

    private Component deserializeTemplate(
            String template,
            ApprovedMessage message,
            Component content,
            PreparedMessage prepared
    ) {
        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.component("prefix", prepared.prefix()))
                .resolver(Placeholder.unparsed("name", message.senderName()))
                .resolver(Placeholder.component("suffix", prepared.suffix()))
                .resolver(Placeholder.unparsed("target", message.routeDisplayName()))
                .resolver(Placeholder.component("message", content))
                .build();
        try {
            return miniMessage.deserialize(template, placeholders);
        } catch (RuntimeException malformedTemplate) {
            return Component.text("[tkChat] ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(message.senderName() + ": ", NamedTextColor.WHITE))
                    .append(content);
        }
    }

    private Component mention(Component input, ApprovedMessage message, Player viewer) {
        if (!mentions.enabled || viewer.getUniqueId().equals(message.senderId())) {
            return input;
        }
        Pattern mentionPattern = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(mentions.prefix + viewer.getUsername())
                        + "(?![A-Za-z0-9_])");
        if (!mentionPattern.matcher(message.content()).find()) {
            return input;
        }
        if (mentions.playSound) {
            try {
                viewer.playSound(Sound.sound(
                        Key.key(mentions.sound), Sound.Source.PLAYER,
                        mentions.soundVolume, mentions.soundPitch));
            } catch (RuntimeException ignored) {
                // Invalid sound identifiers are handled as a silent mention.
            }
        }
        return input.replaceText(TextReplacementConfig.builder()
                .match(mentionPattern)
                .replacement((match, builder) -> {
                    try {
                        return miniMessage.deserialize(mentions.highlightFormat,
                                Placeholder.unparsed("mention", match.group()));
                    } catch (RuntimeException malformedFormat) {
                        return Component.text(match.group(), NamedTextColor.YELLOW);
                    }
                })
                .build());
    }

    private Component trustedMeta(String input) {
        if (input.isEmpty()) {
            return Component.empty();
        }
        try {
            return miniMessage.deserialize(input);
        } catch (RuntimeException malformedMeta) {
            return Component.text(input);
        }
    }

    private Component linkItems(Component input, ItemLink item) {
        if (item == null) {
            return input;
        }
        Component display;
        try {
            display = miniMessage.deserialize(itemLinks.format,
                    Placeholder.unparsed("amount", Integer.toString(item.amount())),
                    Placeholder.unparsed("item_name", item.displayName()));
        } catch (RuntimeException malformedFormat) {
            display = Component.text("[" + item.amount() + "x " + item.displayName() + "]",
                    NamedTextColor.AQUA);
        }
        try {
            display = display.hoverEvent(HoverEvent.showItem(Key.key(item.itemId()), item.amount()));
        } catch (RuntimeException invalidItemIdentifier) {
            display = display.hoverEvent(HoverEvent.showText(Component.text(item.displayName())));
        }
        Component replacement = display;
        Component result = input;
        for (String placeholder : itemLinks.placeholders) {
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(placeholder)
                    .replacement(replacement)
                    .build());
        }
        return result;
    }

    private static Component linkify(Component input) {
        return input.replaceText(TextReplacementConfig.builder()
                .match(URL)
                .replacement((match, builder) -> {
                    String visible = match.group();
                    String url = visible.regionMatches(true, 0, "www.", 0, 4)
                            ? "https://" + visible
                            : visible;
                    return builder.color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Open link")));
                })
                .build());
    }
}
