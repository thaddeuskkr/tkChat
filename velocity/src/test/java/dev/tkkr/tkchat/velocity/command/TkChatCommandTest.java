package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import dev.tkkr.tkchat.velocity.ResponseTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TkChatCommandTest {
    @Test
    void delegatesOnlyFullSubcommandNames() {
        AtomicReference<String[]> received = new AtomicReference<>();
        SimpleCommand channel = invocation -> received.set(invocation.arguments());
        TkChatCommand root = new TkChatCommand(List.of(
                new TkChatCommand.Child("channel", "tkchat.command.channel", channel)),
                ResponseTestFixtures.responses());
        CommandSource source = sourceWith("tkchat.command.channel");

        root.execute(invocation(source, "tkchat", "channel", "g"));
        assertArrayEquals(new String[]{"g"}, received.get());

        received.set(null);
        root.execute(invocation(source, "tkchat", "ch", "g"));
        assertEquals(null, received.get());
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
                new TkChatCommand.Child("channel", "tkchat.command.channel", channel),
                new TkChatCommand.Child("reload", "tkchat.command.reload", invocation -> {
                })), ResponseTestFixtures.responses());
        CommandSource source = sourceWith("tkchat.command.channel");

        assertEquals(List.of("channel"), root.suggest(invocation(source, "tkchat")));
        assertEquals(List.of("global"), root.suggest(
                invocation(source, "tkchat", "channel", "g")));
        assertArrayEquals(new String[]{"g"}, received.get());
    }

    private static CommandSource sourceWith(String... permissions) {
        Set<String> allowed = Set.of(permissions);
        return permission -> allowed.contains(permission) ? Tristate.TRUE : Tristate.FALSE;
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
