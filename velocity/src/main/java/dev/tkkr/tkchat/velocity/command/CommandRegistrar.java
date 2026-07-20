package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigReloadResult;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class CommandRegistrar {
    private record CommandSpec(
            String name,
            String permission,
            SimpleCommand command,
            List<String> aliases
    ) {
    }

    private record RegisteredCommand(CommandMeta meta, SimpleCommand command) {
    }

    private final Object plugin;
    private final CommandManager commands;
    private List<RegisteredCommand> registered = List.of();

    public CommandRegistrar(Object plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.commands = proxy.getCommandManager();
    }

    public synchronized void register(
            ProxyServer proxy,
            ChannelRegistry channels,
            PlayerStateService states,
            SocialRepository repository,
            VelocityChatService chat,
            VelocityAccessController access,
            NetworkMessageService networkMessages,
            SocialSpyService spies,
            ResponseService responses,
            AppConfig config,
            Supplier<CompletionStage<ConfigReloadResult>> reload
    ) {
        List<CommandSpec> specs = specifications(
                proxy, channels, states, repository, chat, access, networkMessages,
                spies, responses, config, reload);
        List<RegisteredCommand> previous = registered;
        previous.forEach(entry -> commands.unregister(entry.meta()));

        ArrayList<RegisteredCommand> replacement = new ArrayList<>();
        try {
            for (CommandSpec spec : specs) {
                CommandMeta meta = commands.metaBuilder(spec.name())
                        .aliases(spec.aliases().toArray(String[]::new))
                        .plugin(plugin)
                        .build();
                SimpleCommand command = permission(spec.permission(), spec.command(), responses);
                commands.register(meta, command);
                replacement.add(new RegisteredCommand(meta, command));
            }
            registered = List.copyOf(replacement);
        } catch (RuntimeException error) {
            replacement.forEach(entry -> commands.unregister(entry.meta()));
            previous.forEach(entry -> commands.register(entry.meta(), entry.command()));
            registered = previous;
            throw error;
        }
    }

    public synchronized void unregister() {
        registered.forEach(entry -> commands.unregister(entry.meta()));
        registered = List.of();
    }

    private List<CommandSpec> specifications(
            ProxyServer proxy,
            ChannelRegistry channels,
            PlayerStateService states,
            SocialRepository repository,
            VelocityChatService chat,
            VelocityAccessController access,
            NetworkMessageService networkMessages,
            SocialSpyService spies,
            ResponseService responses,
            AppConfig config,
            Supplier<CompletionStage<ConfigReloadResult>> reload
    ) {
        ChannelCommand channelCommand = new ChannelCommand(channels, states, access, responses);
        LinkedHashMap<String, TkChatCommand.Child> rootChildren = new LinkedHashMap<>();
        ArrayList<CommandSpec> specs = new ArrayList<>();

        add(specs, rootChildren, "channel", "channel", Permissions.command("channel"),
                channelCommand, "ch");
        add(specs, rootChildren, "msg", "message", Permissions.command("message"),
                new MessageCommand(proxy, chat, responses), "tell", "w", "message");
        add(specs, rootChildren, "reply", "reply", Permissions.command("reply"),
                new ReplyCommand(chat, responses), "r");
        add(specs, rootChildren, "me", "me", Permissions.command("me"),
                new MeCommand(chat, states, responses, config.chat));
        add(specs, rootChildren, "group", "group", Permissions.command("group"),
                new GroupCommand(proxy, repository, chat, states, channels, access, responses), "party");
        SimpleCommand groupChat = invocation -> {
            if (!(invocation.source() instanceof com.velocitypowered.api.proxy.Player player)) {
                invocation.source().sendMessage(responses.message(
                        ResponseKey.GROUP_CHAT_PLAYER_ONLY));
                return;
            }
            if (!states.isLoaded(player.getUniqueId())) {
                player.sendMessage(responses.denial(
                        dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
                return;
            }
            if (invocation.arguments().length == 0) {
                var group = states.group(player.getUniqueId()).orElse(null);
                if (group == null) {
                    player.sendMessage(responses.message(ResponseKey.GROUP_NOT_MEMBER));
                } else {
                    states.setActiveGroup(player.getUniqueId(), group).whenComplete((ignored, error) ->
                            player.sendMessage(error == null
                                    ? responses.message(ResponseKey.CHANNEL_ACTIVE_SET,
                                    ResponseService.text("channel", group.name()))
                                    : responses.message(ResponseKey.CHANNEL_GROUP_SAVE_FAILED)));
                }
            } else {
                chat.group(player, String.join(" ", invocation.arguments()));
            }
        };
        add(specs, rootChildren, "groupchat", "groupchat", Permissions.command("groupchat"),
                groupChat, "gc", "pc");
        add(specs, rootChildren, "ignore", "ignore", Permissions.command("ignore"),
                new IgnoreCommand(proxy, states, responses), "block");
        add(specs, rootChildren, "dmtoggle", "dmtoggle", Permissions.command("dmtoggle"),
                new DirectMessagesToggleCommand(states, responses));
        add(specs, rootChildren, "broadcast", "broadcast", Permissions.command("broadcast"),
                new BroadcastCommand(networkMessages, config.chat, responses), "bc");
        add(specs, rootChildren, "clearchat", "clearchat", Permissions.command("clearchat"),
                new ClearChatCommand(networkMessages, channels, responses));
        add(specs, rootChildren, "socialspy", "socialspy", Permissions.command("socialspy"),
                new SocialSpyCommand(spies, responses), "spy");

        for (var channel : channels.all()) {
            add(specs, rootChildren, channel.id(), channel.id(), Permissions.command("channel"),
                    new QuickChannelCommand(channel, channelCommand, chat, responses),
                    channel.aliases().toArray(String[]::new));
        }

        putRoot(rootChildren, new TkChatCommand.Child(
                "reload", Permissions.command("reload"), new ReloadCommand(reload, responses)));
        specs.add(new CommandSpec("tkchat", "", new TkChatCommand(
                List.copyOf(rootChildren.values()), responses), List.of()));
        return List.copyOf(specs);
    }

    private static void add(
            List<CommandSpec> specs,
            Map<String, TkChatCommand.Child> rootChildren,
            String standaloneName,
            String rootName,
            String permission,
            SimpleCommand command,
            String... aliases
    ) {
        specs.add(new CommandSpec(standaloneName, permission, command, List.of(aliases)));
        putRoot(rootChildren, new TkChatCommand.Child(rootName, permission, command));
    }

    private static void putRoot(
            Map<String, TkChatCommand.Child> children,
            TkChatCommand.Child child
    ) {
        if (children.put(child.name(), child) != null) {
            throw new IllegalArgumentException(
                    "Configured channel conflicts with /tkchat " + child.name());
        }
    }

    private static SimpleCommand permission(
            String permission,
            SimpleCommand command,
            ResponseService responses
    ) {
        if (permission == null || permission.isBlank()) {
            return command;
        }
        return new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                if (!invocation.source().hasPermission(permission)) {
                    invocation.source().sendMessage(responses.message(
                            ResponseKey.GENERAL_NO_PERMISSION));
                    return;
                }
                command.execute(invocation);
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                return invocation.source().hasPermission(permission)
                        ? command.suggest(invocation)
                        : List.of();
            }
        };
    }
}
