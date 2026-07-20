package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
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
import java.util.stream.Stream;

public final class ChannelCommand implements SimpleCommand {
    private final ChannelRegistry channels;
    private final PlayerStateService states;
    private final VelocityAccessController access;
    private final ResponseService responses;

    public ChannelCommand(
            ChannelRegistry channels,
            PlayerStateService states,
            VelocityAccessController access,
            ResponseService responses
    ) {
        this.channels = channels;
        this.states = states;
        this.access = access;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.CHANNEL_PLAYER_ONLY));
            return;
        }
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            player.sendMessage(responses.message(
                    ResponseKey.CHANNEL_STATUS_ACTIVE,
                    ResponseService.text("channel", states.activeDisplayName(player.getUniqueId()))));
            player.sendMessage(responses.message(
                    ResponseKey.CHANNEL_STATUS_AVAILABLE,
                    ResponseService.text("channels", String.join(", ", visibleChannelLabels(player)))));
            return;
        }
        if (arguments.length != 1) {
            player.sendMessage(responses.message(ResponseKey.CHANNEL_USAGE));
            return;
        }

        String requested = arguments[0].toLowerCase(Locale.ROOT);
        ChannelDefinition channel = channels.find(requested).orElse(null);
        if (channel != null) {
            select(player, channel);
            return;
        }

        Group group = states.group(player.getUniqueId()).orElse(null);
        if (group != null && (requested.equals("group") || requested.equals(group.normalizedName()))) {
            states.setActiveGroup(player.getUniqueId(), group).whenComplete((ignored, error) ->
                    player.sendMessage(error == null
                            ? responses.message(ResponseKey.CHANNEL_ACTIVE_SET,
                            ResponseService.text("channel", group.name()))
                            : responses.message(ResponseKey.CHANNEL_GROUP_SAVE_FAILED)));
            return;
        }

        player.sendMessage(responses.message(
                ResponseKey.CHANNEL_UNKNOWN,
                ResponseService.text("channels", String.join(", ", visibleChannelLabels(player)))));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player player) || invocation.arguments().length > 1) {
            return List.of();
        }
        String prefix = invocation.arguments().length == 0
                ? ""
                : invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return selectableNames(player).stream().filter(channel -> channel.startsWith(prefix)).toList();
    }

    void select(Player player, ChannelDefinition channel) {
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        if (!canUse(player, channel)) {
            player.sendMessage(responses.message(ResponseKey.CHANNEL_NO_ACCESS));
            return;
        }
        states.setActiveChannel(player.getUniqueId(), channel.id()).whenComplete((ignored, error) ->
                player.sendMessage(error == null
                        ? responses.message(ResponseKey.CHANNEL_ACTIVE_SET,
                        ResponseService.text("channel", channel.displayName()))
                        : responses.message(ResponseKey.CHANNEL_SAVE_FAILED)));
    }

    private List<String> selectableNames(Player player) {
        Stream<String> configured = channels.all().stream()
                .filter(channel -> canUse(player, channel))
                .flatMap(channel -> Stream.concat(Stream.of(channel.id()), channel.aliases().stream()));
        Stream<String> group = states.group(player.getUniqueId()).stream().map(Group::normalizedName);
        return Stream.concat(configured, group).toList();
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

    private boolean canUse(Player player, ChannelDefinition channel) {
        return access.hasPermission(player, channel.sendPermission())
                || access.hasPermission(player, channel.bypassPermission())
                || access.hasPermission(player, Permissions.BYPASS_CHANNEL_RESTRICTIONS);
    }
}
