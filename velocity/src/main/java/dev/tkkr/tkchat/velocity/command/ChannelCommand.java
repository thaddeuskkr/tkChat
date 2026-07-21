package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public final class ChannelCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final ChannelRegistry channels;
    private final PlayerStateService states;
    private final BiPredicate<Player, String> hasPermission;
    private final ResponseService responses;

    public ChannelCommand(
            ProxyServer proxy,
            ChannelRegistry channels,
            PlayerStateService states,
            VelocityAccessController access,
            ResponseService responses
    ) {
        this(proxy, channels, states, access::hasPermission, responses);
    }

    ChannelCommand(
            ProxyServer proxy,
            ChannelRegistry channels,
            PlayerStateService states,
            BiPredicate<Player, String> hasPermission,
            ResponseService responses
    ) {
        this.proxy = proxy;
        this.channels = channels;
        this.states = states;
        this.hasPermission = hasPermission;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(responses.message(ResponseKey.CHANNEL_PLAYER_ONLY));
                return;
            }
            if (!states.isLoaded(player.getUniqueId())) {
                player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
                return;
            }
            player.sendMessage(responses.message(
                    ResponseKey.CHANNEL_STATUS_ACTIVE,
                    ResponseService.text("channel", states.activeDisplayName(player.getUniqueId()))));
            player.sendMessage(responses.message(
                    ResponseKey.CHANNEL_STATUS_AVAILABLE,
                    ResponseService.text("channels", String.join(", ", visibleChannelLabels(player)))));
            return;
        }
        if (arguments.length > 2) {
            invocation.source().sendMessage(responses.message(ResponseKey.CHANNEL_USAGE));
            return;
        }

        boolean forced = arguments.length == 2;
        Player target;
        if (forced) {
            if (!invocation.source().hasPermission(Permissions.CHANNEL_OTHERS)) {
                invocation.source().sendMessage(responses.message(ResponseKey.GENERAL_NO_PERMISSION));
                return;
            }
            target = proxy.getPlayer(arguments[1]).orElse(null);
            if (target == null) {
                invocation.source().sendMessage(responses.message(ResponseKey.CHANNEL_TARGET_OFFLINE));
                return;
            }
        } else if (invocation.source() instanceof Player player) {
            target = player;
        } else {
            invocation.source().sendMessage(responses.message(ResponseKey.CHANNEL_PLAYER_ONLY));
            return;
        }

        if (!states.isLoaded(target.getUniqueId())) {
            invocation.source().sendMessage(forced
                    ? responses.message(ResponseKey.CHANNEL_TARGET_NOT_READY)
                    : responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }

        String requested = arguments[0].toLowerCase(Locale.ROOT);
        ChannelDefinition channel = channels.find(requested).orElse(null);
        if (channel != null) {
            select(invocation.source(), target, channel, forced);
            return;
        }

        Group group = states.group(target.getUniqueId()).orElse(null);
        if (group != null && (requested.equals("group") || requested.equals(group.normalizedName()))) {
            states.setActiveGroup(target.getUniqueId(), group).whenComplete((ignored, error) -> {
                if (error == null) {
                    sendSuccess(invocation.source(), target, group.name(), forced);
                } else {
                    invocation.source().sendMessage(responses.message(
                            ResponseKey.CHANNEL_GROUP_SAVE_FAILED));
                }
            });
            return;
        }

        invocation.source().sendMessage(responses.message(
                ResponseKey.CHANNEL_UNKNOWN,
                ResponseService.text("channels", String.join(", ", forced
                        ? allChannelLabels(target)
                        : visibleChannelLabels(target)))));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length <= 1) {
            String prefix = arguments.length == 0
                    ? ""
                    : arguments[0].toLowerCase(Locale.ROOT);
            Stream<String> names = invocation.source() instanceof Player player
                    ? (invocation.source().hasPermission(Permissions.CHANNEL_OTHERS)
                    ? allSelectableNames(player)
                    : selectableNames(player).stream())
                    : configuredNames();
            return names.filter(channel -> channel.startsWith(prefix)).toList();
        }
        if (arguments.length == 2
                && invocation.source().hasPermission(Permissions.CHANNEL_OTHERS)) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    void select(Player player, ChannelDefinition channel) {
        select(player, player, channel, false);
    }

    private void select(
            CommandSource source,
            Player target,
            ChannelDefinition channel,
            boolean forced
    ) {
        if (!states.isLoaded(target.getUniqueId())) {
            source.sendMessage(forced
                    ? responses.message(ResponseKey.CHANNEL_TARGET_NOT_READY)
                    : responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        if (!forced && !canUse(target, channel)) {
            target.sendMessage(responses.message(ResponseKey.CHANNEL_NO_ACCESS));
            return;
        }
        states.setActiveChannel(target.getUniqueId(), channel.id()).whenComplete((ignored, error) -> {
            if (error == null) {
                sendSuccess(source, target, channel.displayName(), forced);
            } else {
                source.sendMessage(responses.message(ResponseKey.CHANNEL_SAVE_FAILED));
            }
        });
    }

    private void sendSuccess(
            CommandSource source,
            Player target,
            String channel,
            boolean forced
    ) {
        if (!forced) {
            target.sendMessage(responses.message(
                    ResponseKey.CHANNEL_ACTIVE_SET,
                    ResponseService.text("channel", channel)));
            return;
        }
        source.sendMessage(responses.message(
                ResponseKey.CHANNEL_ACTIVE_SET_OTHER,
                ResponseService.text("player", target.getUsername()),
                ResponseService.text("channel", channel)));
        if (source != target) {
            target.sendMessage(responses.message(
                    ResponseKey.CHANNEL_ACTIVE_SET_FORCED,
                    ResponseService.text("channel", channel)));
        }
    }

    private List<String> selectableNames(Player player) {
        Stream<String> configured = channels.all().stream()
                .filter(channel -> canUse(player, channel))
                .flatMap(channel -> Stream.concat(Stream.of(channel.id()), channel.aliases().stream()));
        Stream<String> group = states.group(player.getUniqueId()).stream().map(Group::normalizedName);
        return Stream.concat(configured, group).toList();
    }

    private Stream<String> configuredNames() {
        return channels.all().stream()
                .flatMap(channel -> Stream.concat(Stream.of(channel.id()), channel.aliases().stream()));
    }

    private Stream<String> allSelectableNames(Player player) {
        Stream<String> group = states.group(player.getUniqueId()).stream().map(Group::normalizedName);
        return Stream.concat(configuredNames(), group).distinct();
    }

    private List<String> visibleChannelLabels(Player player) {
        Stream<String> configured = channels.all().stream()
                .filter(channel -> canUse(player, channel))
                .map(channel -> channel.aliases().isEmpty()
                        ? channel.id()
                        : channel.id() + " (" + String.join(", ", channel.aliases()) + ")");
        Stream<String> group = states.group(player.getUniqueId()).stream().map(Group::normalizedName);
        return Stream.concat(configured, group).toList();
    }

    private List<String> allChannelLabels(Player player) {
        Stream<String> configured = channels.all().stream()
                .map(channel -> channel.aliases().isEmpty()
                        ? channel.id()
                        : channel.id() + " (" + String.join(", ", channel.aliases()) + ")");
        Stream<String> group = states.group(player.getUniqueId()).stream().map(Group::normalizedName);
        return Stream.concat(configured, group).toList();
    }

    private boolean canUse(Player player, ChannelDefinition channel) {
        return hasPermission.test(player, channel.sendPermission())
                || hasPermission.test(player, channel.bypassPermission())
                || hasPermission.test(player, Permissions.BYPASS_CHANNEL_RESTRICTIONS);
    }
}
