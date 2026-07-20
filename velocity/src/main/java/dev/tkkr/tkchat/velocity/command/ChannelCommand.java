package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ChannelCommand implements SimpleCommand {
    private final ChannelRegistry channels;
    private final PlayerStateService states;
    private final VelocityAccessController access;

    public ChannelCommand(
            ChannelRegistry channels,
            PlayerStateService states,
            VelocityAccessController access
    ) {
        this.channels = channels;
        this.states = states;
        this.access = access;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players can select a chat channel."));
            return;
        }
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(VelocityChatService.denial(
                    dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            player.sendMessage(Component.text("Active channel: ", NamedTextColor.GRAY)
                    .append(Component.text(states.activeDisplayName(player.getUniqueId()), NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Channels: ", NamedTextColor.GRAY)
                    .append(Component.text(String.join(", ", visibleChannelLabels(player)), NamedTextColor.WHITE)));
            return;
        }
        if (arguments.length != 1) {
            player.sendMessage(VelocityChatService.error("Usage: /channel <channel>"));
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
                            ? VelocityChatService.success("Active channel set to " + group.name() + ".")
                            : VelocityChatService.error("The group channel could not be saved.")));
            return;
        }

        player.sendMessage(VelocityChatService.error("Unknown channel. Available: "
                + String.join(", ", visibleChannelLabels(player))));
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
            player.sendMessage(VelocityChatService.denial(
                    dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        if (!canUse(player, channel)) {
            player.sendMessage(VelocityChatService.error("You cannot use that channel."));
            return;
        }
        states.setActiveChannel(player.getUniqueId(), channel.id()).whenComplete((ignored, error) ->
                player.sendMessage(error == null
                        ? VelocityChatService.success("Active channel set to " + channel.displayName() + ".")
                        : VelocityChatService.error("The channel could not be saved.")));
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
