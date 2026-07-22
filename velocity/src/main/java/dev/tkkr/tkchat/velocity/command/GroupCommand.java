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
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.function.BiPredicate;

public final class GroupCommand implements SimpleCommand {
    private record GroupRoster(Group group, Set<UUID> members, Set<UUID> invitees) {
    }

    private record NamedMember(String name, boolean invited, boolean online) {
    }

    private record GroupStatus(Group group, NamedMember owner, List<NamedMember> members) {
    }

    private final ProxyServer proxy;
    private final SocialRepository repository;
    private final VelocityChatService chat;
    private final PlayerStateService states;
    private final ChannelRegistry channels;
    private final BiPredicate<Player, String> hasPermission;
    private final ResponseService responses;

    public GroupCommand(
            ProxyServer proxy,
            SocialRepository repository,
            VelocityChatService chat,
            PlayerStateService states,
            ChannelRegistry channels,
            VelocityAccessController access,
            ResponseService responses
    ) {
        this(proxy, repository, chat, states, channels, access::hasPermission, responses);
    }

    GroupCommand(
            ProxyServer proxy,
            SocialRepository repository,
            VelocityChatService chat,
            PlayerStateService states,
            ChannelRegistry channels,
            BiPredicate<Player, String> hasPermission,
            ResponseService responses
    ) {
        this.proxy = proxy;
        this.repository = repository;
        this.chat = chat;
        this.states = states;
        this.channels = channels;
        this.hasPermission = hasPermission;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.GROUP_PLAYER_ONLY));
            return;
        }
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
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
        repository.groupForMember(player.getUniqueId()).thenCompose(membership -> {
            if (membership.isEmpty()) {
                return CompletableFuture.completedFuture((GroupStatus) null);
            }
            Group group = membership.get().group();
            CompletionStage<Set<UUID>> members = repository.groupMembers(group.id());
            CompletionStage<Set<UUID>> invitees = repository.groupInvitees(group.id(), Instant.now());
            return members.thenCombine(invitees, (accepted, invited) ->
                            new GroupRoster(group, accepted, invited))
                    .thenCompose(this::resolveRoster);
        }).whenComplete((status, error) -> {
            if (error != null) {
                player.sendMessage(responses.message(ResponseKey.GROUP_STORAGE_UNAVAILABLE));
            } else if (status == null) {
                player.sendMessage(responses.message(ResponseKey.GROUP_STATUS_NONE));
            } else {
                Group group = status.group();
                player.sendMessage(responses.message(
                        ResponseKey.GROUP_STATUS_MEMBER,
                        ResponseService.text("group", group.name()),
                        ResponseService.text("visibility",
                                group.visibility().name().toLowerCase(Locale.ROOT)),
                        ResponseService.component("button", switchButton(group))));
                player.sendMessage(responses.message(
                        ResponseKey.GROUP_STATUS_OWNER,
                        ResponseService.component("owner", playerComponent(status.owner()))));
                player.sendMessage(responses.message(
                        ResponseKey.GROUP_STATUS_MEMBERS,
                        ResponseService.component("members", rosterComponent(status.members()))));
            }
        });
    }

    private CompletionStage<GroupStatus> resolveRoster(GroupRoster roster) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        ids.add(roster.group().ownerId());
        ids.addAll(roster.members());
        ids.addAll(roster.invitees());
        return repository.playerNames(Set.copyOf(ids)).thenApply(names -> {
            Comparator<NamedMember> byName = Comparator.comparing(
                    NamedMember::name, String.CASE_INSENSITIVE_ORDER);
            List<NamedMember> accepted = roster.members().stream()
                    .filter(id -> !id.equals(roster.group().ownerId()))
                    .map(id -> namedMember(id, names, false))
                    .sorted(byName)
                    .toList();
            List<NamedMember> invited = roster.invitees().stream()
                    .filter(id -> !roster.members().contains(id))
                    .map(id -> namedMember(id, names, true))
                    .sorted(byName)
                    .toList();
            NamedMember owner = namedMember(roster.group().ownerId(), names, false);
            List<NamedMember> displayed = new ArrayList<>(1 + accepted.size() + invited.size());
            displayed.add(owner);
            displayed.addAll(accepted);
            displayed.addAll(invited);
            return new GroupStatus(roster.group(), owner, List.copyOf(displayed));
        });
    }

    private NamedMember namedMember(
            UUID playerId,
            Map<UUID, String> storedNames,
            boolean invited
    ) {
        Player online = proxy.getPlayer(playerId).orElse(null);
        if (online != null) {
            return new NamedMember(online.getUsername(), invited, true);
        }
        return new NamedMember(storedNames.getOrDefault(playerId, playerId.toString()),
                invited, false);
    }

    private Component rosterComponent(List<NamedMember> members) {
        Component roster = Component.empty();
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                roster = roster.append(Component.text(", "));
            }
            NamedMember member = members.get(index);
            roster = roster.append(playerComponent(member));
        }
        return roster;
    }

    private Component playerComponent(NamedMember member) {
        Component result = Component.text(member.name(),
                member.online() ? NamedTextColor.WHITE : NamedTextColor.GRAY);
        if (member.invited()) {
            result = result.append(responses.content(ResponseKey.GROUP_STATUS_INVITED_TAG));
        }
        if (!member.online()) {
            result = result.append(responses.content(ResponseKey.GROUP_STATUS_OFFLINE_TAG));
        }
        return result;
    }

    private void create(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(responses.message(ResponseKey.GROUP_CREATE_USAGE));
            return;
        }
        String name = args[1];
        String password = args.length == 3 ? args[2] : null;
        GroupVisibility visibility = password == null ? GroupVisibility.PUBLIC : GroupVisibility.PRIVATE;

        String normalizedName;
        try {
            normalizedName = GroupNames.normalize(name);
        } catch (IllegalArgumentException invalid) {
            player.sendMessage(responses.message(ResponseKey.GROUP_INVALID_NAME));
            return;
        }
        if (visibility == GroupVisibility.PRIVATE) {
            try {
                GroupPasswords.validate(password);
            } catch (IllegalArgumentException invalid) {
                player.sendMessage(responses.message(ResponseKey.GROUP_INVALID_PASSWORD));
                return;
            }
        }
        if (normalizedName.equals("group") || channels.find(normalizedName).isPresent()) {
            player.sendMessage(responses.message(ResponseKey.GROUP_RESERVED_NAME));
            return;
        }

        repository.createGroup(player.getUniqueId(), name, visibility, password)
                .whenComplete((group, error) -> {
                    if (error != null) {
                        sendFailure(player, error);
                        return;
                    }
                    states.setGroupMembership(player.getUniqueId(), group, GroupRole.OWNER);
                    player.sendMessage(responses.message(
                            ResponseKey.GROUP_CREATED,
                            ResponseService.text("visibility", visibility.name().toLowerCase(Locale.ROOT)),
                            ResponseService.text("group", group.name()),
                            ResponseService.component("button", switchButton(group))));
                });
    }

    private void list(Player player) {
        boolean includePrivate = isGroupAdmin(player);
        repository.listGroups(includePrivate).whenComplete((groups, error) -> {
            if (error != null) {
                player.sendMessage(responses.message(ResponseKey.GROUP_STORAGE_UNAVAILABLE));
                return;
            }
            if (groups.isEmpty()) {
                player.sendMessage(responses.message(ResponseKey.GROUP_LIST_EMPTY));
                return;
            }
            player.sendMessage(responses.message(includePrivate
                    ? ResponseKey.GROUP_LIST_HEADING_ALL
                    : ResponseKey.GROUP_LIST_HEADING_PUBLIC));
            groups.forEach(group -> player.sendMessage(responses.message(
                    ResponseKey.GROUP_LIST_ENTRY,
                    ResponseService.text("group", group.name()),
                    ResponseService.text("visibility", group.visibility().name().toLowerCase(Locale.ROOT)),
                    ResponseService.component("button", joinButton(group, includePrivate)))));
        });
    }

    private void join(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) {
            player.sendMessage(responses.message(ResponseKey.GROUP_JOIN_USAGE));
            return;
        }
        boolean bypass = isGroupAdmin(player);
        String password = args.length == 3 ? args[2] : null;
        repository.joinGroup(player.getUniqueId(), args[1], password, bypass, Instant.now())
                .whenComplete((group, error) -> joined(player, group, error));
    }

    private void invite(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(responses.message(ResponseKey.GROUP_INVITE_USAGE));
            return;
        }
        Player target = proxy.getPlayer(args[1]).orElse(null);
        if (target == null || target.equals(player)) {
            player.sendMessage(responses.message(ResponseKey.GROUP_TARGET_UNAVAILABLE));
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
                        player.sendMessage(responses.message(
                                ResponseKey.GROUP_INVITE_SENT,
                                ResponseService.text("player", target.getUsername())));
                        target.sendMessage(responses.message(
                                ResponseKey.GROUP_INVITE_RECEIVED,
                                ResponseService.text("player", player.getUsername()),
                                ResponseService.text("group", group.name()),
                                ResponseService.component("button", acceptButton(group))));
                    });
        }).exceptionally(error -> {
            sendFailure(player, error);
            return null;
        });
    }

    private void accept(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(responses.message(ResponseKey.GROUP_ACCEPT_USAGE));
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
        states.setGroupMembership(player.getUniqueId(), group, GroupRole.MEMBER);
        player.sendMessage(responses.message(
                ResponseKey.GROUP_JOINED,
                ResponseService.text("group", group.name()),
                ResponseService.component("button", switchButton(group))));
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
                    proxy.getPlayer(memberId).ifPresent(member -> member.sendMessage(responses.message(
                            ResponseKey.GROUP_DISBAND_NOTICE,
                            ResponseService.text("group", result.group().name()))));
                }
            });
            player.sendMessage(responses.message(
                    result.deleted() ? ResponseKey.GROUP_DISBANDED : ResponseKey.GROUP_LEFT,
                    ResponseService.text("group", result.group().name())));
        });
    }

    private void groupChat(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(responses.message(ResponseKey.GROUP_CHAT_USAGE));
            return;
        }
        chat.group(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
    }

    private Component acceptButton(Group group) {
        return actionButton(
                ResponseKey.GROUP_ACTION_ACCEPT_LABEL,
                "/tkchat group accept " + group.normalizedName(),
                ResponseKey.GROUP_ACTION_ACCEPT_HOVER,
                group);
    }

    private Component joinButton(Group group, boolean bypass) {
        String command = "/tkchat group join " + group.normalizedName();
        ResponseKey hover = group.visibility() == GroupVisibility.PRIVATE && !bypass
                ? ResponseKey.GROUP_ACTION_JOIN_PRIVATE_HOVER
                : ResponseKey.GROUP_ACTION_JOIN_HOVER;
        return actionButton(ResponseKey.GROUP_ACTION_JOIN_LABEL, command, hover, group);
    }

    private Component switchButton(Group group) {
        return actionButton(
                ResponseKey.GROUP_ACTION_SWITCH_LABEL,
                "/tkchat channel " + group.normalizedName(),
                ResponseKey.GROUP_ACTION_SWITCH_HOVER,
                group);
    }

    private Component actionButton(
            ResponseKey label,
            String command,
            ResponseKey hover,
            Group group
    ) {
        return responses.content(label, ResponseService.text("group", group.name()))
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(responses.content(
                        hover,
                        ResponseService.text("group", group.name()))));
    }

    private void sendFailure(Player player, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof GroupException groupError) {
            player.sendMessage(responses.message(switch (groupError.failure()) {
                case NAME_TAKEN -> ResponseKey.GROUP_FAILURE_NAME_TAKEN;
                case ALREADY_MEMBER -> ResponseKey.GROUP_FAILURE_ALREADY_MEMBER;
                case NOT_FOUND -> ResponseKey.GROUP_FAILURE_NOT_FOUND;
                case INVITE_REQUIRED -> ResponseKey.GROUP_FAILURE_INVITE_REQUIRED;
                case INVALID_PASSWORD -> ResponseKey.GROUP_FAILURE_INVALID_PASSWORD;
                case INVITE_MISSING_OR_EXPIRED -> ResponseKey.GROUP_FAILURE_INVITE_EXPIRED;
                case NOT_MEMBER -> ResponseKey.GROUP_FAILURE_NOT_MEMBER;
                case NOT_OWNER -> ResponseKey.GROUP_FAILURE_NOT_OWNER;
            }));
            return;
        }
        player.sendMessage(responses.message(ResponseKey.GROUP_STORAGE_UNAVAILABLE));
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
        return hasPermission.test(player, Permissions.BYPASS_PRIVATE_GROUPS);
    }

    private void usage(Player player) {
        player.sendMessage(responses.message(ResponseKey.GROUP_ROOT_USAGE));
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
