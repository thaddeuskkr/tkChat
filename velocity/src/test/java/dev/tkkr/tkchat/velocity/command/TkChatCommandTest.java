package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import dev.tkkr.tkchat.velocity.ResponseTestFixtures;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigLoader;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TkChatCommandTest {
    @TempDir
    Path directory;

    @Test
    void delegatesOnlyFullSubcommandNames() {
        AtomicReference<String[]> received = new AtomicReference<>();
        SimpleCommand channel = invocation -> received.set(invocation.arguments());
        TkChatCommand root = new TkChatCommand(List.of(
                child("channel", "tkchat.command.channel", channel)),
                "0.7.1",
                ResponseTestFixtures.responses());
        CommandSource source = sourceWith("tkchat.command.channel");

        root.execute(invocation(source, "tkchat", "channel", "g"));
        assertArrayEquals(new String[]{"g"}, received.get());

        received.set(null);
        root.execute(invocation(source, "tkchat", "ch", "g"));
        assertNull(received.get());
    }

    @Test
    void suggestionsArePermissionAwareAndDelegateArguments() {
        AtomicReference<String[]> received = new AtomicReference<>();
        SimpleCommand channel = new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                received.set(invocation.arguments());
                return List.of("global");
            }
        };
        TkChatCommand root = new TkChatCommand(List.of(
                child("channel", "tkchat.command.channel", channel),
                child("reload", "tkchat.command.reload", invocation -> {
                })), "0.7.1", ResponseTestFixtures.responses());
        CommandSource source = sourceWith("tkchat.command.channel");

        assertEquals(List.of("channel", "help"),
                root.suggest(invocation(source, "tkchat")));
        assertEquals(List.of("channel"),
                root.suggest(invocation(source, "tkchat", "help", "c")));
        assertEquals(List.of(),
                root.suggest(invocation(source, "tkchat", "help", "r")));
        assertEquals(List.of("global"), root.suggest(
                invocation(source, "tkchat", "channel", "g")));
        assertArrayEquals(new String[]{"g"}, received.get());
    }

    @Test
    void rootShowsOnlyVersionAndHelpPrompt() throws Exception {
        ArrayList<Component> messages = new ArrayList<>();
        TkChatCommand root = new TkChatCommand(List.of(
                child("channel", "tkchat.command.channel", invocation -> {
                })), "0.7.1", responses());

        root.execute(invocation(capturingSource(messages, "tkchat.command.channel"), "tkchat"));

        assertEquals(List.of(
                "tkChat » Running tkChat v0.7.1",
                "tkChat » Use /tkchat help to view available commands."), plain(messages));
    }

    @Test
    void helpListsAndDescribesOnlyPermittedCommands() throws Exception {
        ArrayList<Component> messages = new ArrayList<>();
        TkChatCommand root = new TkChatCommand(List.of(
                child("channel", "tkchat.command.channel", invocation -> {
                }, "/tkchat channel [channel] [player]", "Choose an active channel.", "channel", "ch"),
                child("reload", "tkchat.command.reload", invocation -> {
                }, "/tkchat reload", "Reload the configuration.")),
                "0.7.1", responses());
        CommandSource source = capturingSource(messages, "tkchat.command.channel");

        root.execute(invocation(source, "tkchat", "help"));

        assertEquals(List.of(
                "tkChat » Running tkChat v0.7.1",
                "tkChat » /tkchat channel [channel] [player]",
                "tkChat » /tkchat help [command]"), plain(messages));

        messages.clear();
        root.execute(invocation(source, "tkchat", "help", "ch"));

        assertEquals(List.of(
                "tkChat » /tkchat channel",
                "tkChat » Choose an active channel.",
                "tkChat » Usage: /tkchat channel [channel] [player]",
                "tkChat » Standalone commands: /channel, /ch",
                "tkChat » Permission: tkchat.command.channel"), plain(messages));

        messages.clear();
        root.execute(invocation(source, "tkchat", "help", "reload"));
        assertEquals(List.of(
                        "tkChat » Unknown command, or you do not have permission to use it."),
                plain(messages));
    }

    private ResponseService responses() throws Exception {
        AppConfig config = new ConfigLoader().load(directory);
        return new ResponseService(config.formats.responsePrefix, config.messages);
    }

    private static TkChatCommand.Child child(
            String name,
            String permission,
            SimpleCommand command
    ) {
        return child(name, permission, command, "/tkchat " + name,
                "Help for " + name + ".");
    }

    private static TkChatCommand.Child child(
            String name,
            String permission,
            SimpleCommand command,
            String usage,
            String description,
            String... aliases
    ) {
        return new TkChatCommand.Child(name, permission, command,
                new TkChatCommand.Help(usage, description, List.of(aliases)));
    }

    private static CommandSource sourceWith(String... permissions) {
        Set<String> allowed = Set.of(permissions);
        return permission -> allowed.contains(permission) ? Tristate.TRUE : Tristate.FALSE;
    }

    private static CommandSource capturingSource(
            List<Component> messages,
            String... permissions
    ) {
        Set<String> allowed = Set.of(permissions);
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPermissionValue" -> allowed.contains(arguments[0])
                            ? Tristate.TRUE : Tristate.FALSE;
                    case "hasPermission" -> allowed.contains(arguments[0]);
                    case "sendMessage" -> {
                        if (arguments != null) {
                            for (Object argument : arguments) {
                                if (argument instanceof Component component) {
                                    messages.add(component);
                                }
                            }
                        }
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static List<String> plain(List<Component> messages) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        return messages.stream().map(serializer::serialize).toList();
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static SimpleCommand.Invocation invocation(
            CommandSource source,
            String alias,
            String... arguments
    ) {
        return new SimpleCommand.Invocation() {
            @Override
            public CommandSource source() {
                return source;
            }

            @Override
            public String[] arguments() {
                return arguments;
            }

            @Override
            public String alias() {
                return alias;
            }
        };
    }
}
