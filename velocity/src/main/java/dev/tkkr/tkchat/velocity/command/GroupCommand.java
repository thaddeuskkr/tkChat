package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.Group;
import dev.tkkr.tkchat.core.model.GroupException;
import dev.tkkr.tkchat.core.model.GroupFailure;
import dev.tkkr.tkchat.core.model.GroupRole;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.GroupNames;
import dev.tkkr.tkchat.core.service.GroupPasswords;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class GroupCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final SocialRepository repository;
    private final VelocityChatService chat;
    private final PlayerStateService states;
    private final ChannelRegistry channels;
    private final VelocityAccessController access;

    public GroupCommand(
            ProxyServer proxy,
            SocialRepository repository,
            VelocityChatService chat,
            PlayerStateService states,
            ChannelRegistry channels,
            VelocityAccessController access
    ) {
        this.proxy = proxy;
        this.repository = repository;
        this.chat = chat;
        this.states = states;
        this.channels = channels;
        this.access = access;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players can manage groups."));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            showStatus(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, args);
            case "list" -> list(player);
            case "join" -> join(player, args);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "leave" -> leave(player);
            case "chat" -> groupChat(player, args);
            default -> usage(player);
        }
    }

    private void showStatus(Player player) {
        repository.groupForMember(player.getUniqueId()).whenComplete((membership, error) -> {
            if (error != null) {
                player.sendMessage(VelocityChatService.error("Group storage is unavailable."));
            } else if (membership.isEmpty()) {
                player.sendMessage(Component.text("You are not in a group. Use /group list to browse public groups.",
                        NamedTextColor.GRAY));
            } else {
                Group group = membership.get().group();
                player.sendMessage(Component.text("Group: ", NamedTextColor.GRAY)
                        .append(Component.text(group.name(), NamedTextColor.AQUA))
                        .append(Component.text(" · " + group.visibility().name().toLowerCase(Locale.ROOT),
                                NamedTextColor.DARK_GRAY))
                        .append(Component.space())
                        .append(switchButton(group)));
            }
        });
    }

    private void create(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(VelocityChatService.error("Usage: /group create <name> [password]"));
            return;
        }
        String name = args[1];
        String password = args.length == 3 ? args[2] : null;
        GroupVisibility visibility = password == null ? GroupVisibility.PUBLIC : GroupVisibility.PRIVATE;

        String normalizedName;
        try {
            normalizedName = GroupNames.normalize(name);
            if (visibility == GroupVisibility.PRIVATE) {
                GroupPasswords.validate(password);
            }
        } catch (IllegalArgumentException invalid) {
            player.sendMessage(VelocityChatService.error(invalid.getMessage()));
            return;
        }
        if (normalizedName.equals("group") || channels.find(normalizedName).isPresent()) {
            player.sendMessage(VelocityChatService.error("That name is reserved by a configured channel."));
            return;
        }

        repository.createGroup(player.getUniqueId(), name, visibility, password)
                .whenComplete((group, error) -> {
                    if (error != null) {
                        sendFailure(player, error);
                        return;
                    }
                    states.setGroupMembership(player.getUniqueId(), group);
                    player.sendMessage(VelocityChatService.success("Created "
                                    + visibility.name().toLowerCase(Locale.ROOT) + " group " + group.name() + ".")
                            .append(Component.space()).append(switchButton(group)));
                });
    }

    private void list(Player player) {
        boolean includePrivate = isGroupAdmin(player);
        repository.listGroups(includePrivate).whenComplete((groups, error) -> {
            if (error != null) {
                player.sendMessage(VelocityChatService.error("Group storage is unavailable."));
                return;
            }
            if (groups.isEmpty()) {
                player.sendMessage(Component.text("There are no joinable groups.", NamedTextColor.GRAY));
                return;
            }
            player.sendMessage(Component.text(includePrivate ? "Groups:" : "Public groups:", NamedTextColor.GRAY));
            groups.forEach(group -> player.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(group.name(), NamedTextColor.AQUA))
                    .append(Component.text(" (" + group.visibility().name().toLowerCase(Locale.ROOT) + ") ",
                            NamedTextColor.DARK_GRAY))
                    .append(joinButton(group, includePrivate))));
        });
    }

    private void join(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(VelocityChatService.error("Usage: /group join <name> [password]"));
            return;
        }
        boolean bypass = isGroupAdmin(player);
        String password = args.length == 3 ? args[2] : null;
        repository.joinGroup(player.getUniqueId(), args[1], password, bypass, Instant.now())
                .whenComplete((group, error) -> joined(player, group, error));
    }

    private void invite(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(VelocityChatService.error("Usage: /group invite <player>"));
            return;
        }
        Player target = proxy.getPlayer(args[1]).orElse(null);
        if (target == null || target.equals(player)) {
            player.sendMessage(VelocityChatService.error("That player is not available."));
            return;
        }
        repository.groupForMember(player.getUniqueId()).thenCompose(membership -> {
            if (membership.isEmpty() || membership.get().role() != GroupRole.OWNER) {
                throw new GroupException(GroupFailure.NOT_OWNER, "Only the group owner can invite players");
            }
            Group group = membership.get().group();
            return repository.invite(group.id(), player.getUniqueId(), target.getUniqueId(),
                            Instant.now().plus(Duration.ofMinutes(5)))
                    .thenRun(() -> {
                        player.sendMessage(VelocityChatService.success("Invited " + target.getUsername() + "."));
                        target.sendMessage(Component.text(player.getUsername() + " invited you to ",
                                        NamedTextColor.AQUA)
                                .append(Component.text(group.name(), NamedTextColor.WHITE))
                                .append(Component.text(". ", NamedTextColor.AQUA))
                                .append(acceptButton(group)));
                    });
        }).exceptionally(error -> {
            sendFailure(player, error);
            return null;
        });
    }

    private void accept(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(VelocityChatService.error("Usage: /group accept <name>"));
            return;
        }
        repository.acceptInvite(player.getUniqueId(), args[1], Instant.now())
                .whenComplete((group, error) -> joined(player, group, error));
    }

    private void joined(Player player, Group group, Throwable error) {
        if (error != null) {
            sendFailure(player, error);
            return;
        }
        states.setGroupMembership(player.getUniqueId(), group);
        player.sendMessage(VelocityChatService.success("Joined " + group.name() + ".")
                .append(Component.space()).append(switchButton(group)));
    }

    private void leave(Player player) {
        repository.leaveGroup(player.getUniqueId()).whenComplete((result, error) -> {
            if (error != null) {
                sendFailure(player, error);
                return;
            }
            result.affectedMembers().forEach(memberId -> {
                states.clearGroupMembership(memberId, result.group().id());
                if (result.deleted() && !memberId.equals(player.getUniqueId())) {
                    proxy.getPlayer(memberId).ifPresent(member -> member.sendMessage(
                            Component.text("Group " + result.group().name() + " was disbanded.",
                                    NamedTextColor.YELLOW)));
                }
            });
            player.sendMessage(result.deleted()
                    ? VelocityChatService.success("Disbanded " + result.group().name() + ".")
                    : VelocityChatService.success("You left " + result.group().name() + "."));
        });
    }

    private void groupChat(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(VelocityChatService.error("Usage: /group chat <message>"));
            return;
        }
        chat.group(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
    }

    private Component acceptButton(Group group) {
        return actionButton("[Accept]", "/tkchat group accept " + group.normalizedName(),
                "Accept this invitation");
    }

    private Component joinButton(Group group, boolean bypass) {
        String command = "/tkchat group join " + group.normalizedName();
        String hover = group.visibility() == GroupVisibility.PRIVATE && !bypass
                ? "Private groups require a password or invitation"
                : "Join " + group.name();
        return actionButton("[Join]", command, hover);
    }

    private Component switchButton(Group group) {
        return actionButton("[Switch channel]", "/tkchat channel " + group.normalizedName(),
                "Make " + group.name() + " your active channel");
    }

    private static Component actionButton(String label, String command, String hover) {
        return Component.text(label, NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private void sendFailure(Player player, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof GroupException groupError) {
            player.sendMessage(VelocityChatService.error(switch (groupError.failure()) {
                case NAME_TAKEN -> "A group with that name already exists.";
                case ALREADY_MEMBER -> "You or the target player is already in a group.";
                case NOT_FOUND -> "That group does not exist.";
                case INVITE_REQUIRED -> "That private group requires an invitation.";
                case INVALID_PASSWORD -> "That group password is incorrect.";
                case INVITE_MISSING_OR_EXPIRED -> "That invitation is missing or expired.";
                case NOT_MEMBER -> "You are not in a group.";
                case NOT_OWNER -> "Only the group owner can do that.";
            }));
            return;
        }
        player.sendMessage(VelocityChatService.error("Group storage is unavailable."));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isGroupAdmin(Player player) {
        return access.hasPermission(player, Permissions.BYPASS_PRIVATE_GROUPS);
    }

    private static void usage(Player player) {
        player.sendMessage(VelocityChatService.error(
                "Usage: /group <create|list|join|invite|accept|leave|chat>"));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("create", "list", "join", "invite", "accept", "leave", "chat").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream().map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
