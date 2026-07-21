package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigLoader;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelCommandTest {
    @TempDir
    Path directory;

    @Test
    void changingAnotherPlayerRequiresTheAdditionalPermission() throws Exception {
        Fixture fixture = fixture(Set.of());

        fixture.command().execute(invocation(
                fixture.actor().player(), "channel", "local", "Target"));

        assertEquals("global", fixture.states().activeChannel(fixture.target().id()));
        assertEquals(List.of("tkChat » You do not have permission to use this command."),
                plain(fixture.actor().messages()));
        assertEquals(List.of(), fixture.target().messages());
    }

    @Test
    void permittedSenderForcesTheTargetChannelAndNotifiesBothPlayers() throws Exception {
        Fixture fixture = fixture(Set.of(Permissions.CHANNEL_OTHERS));

        fixture.command().execute(invocation(
                fixture.actor().player(), "channel", "local", "target"));

        assertEquals("local", fixture.states().activeChannel(fixture.target().id()));
        assertEquals(List.of("tkChat » Set Target's active channel to Local."),
                plain(fixture.actor().messages()));
        assertEquals(List.of("tkChat » Your active channel was set to Local."),
                plain(fixture.target().messages()));
    }

    @Test
    void targetSuggestionsArePermissionAware() throws Exception {
        Fixture denied = fixture(Set.of());
        Fixture permitted = fixture(Set.of(Permissions.CHANNEL_OTHERS));

        assertEquals(List.of(), denied.command().suggest(invocation(
                denied.actor().player(), "channel", "local", "Ta")));
        assertEquals(List.of("Target"), permitted.command().suggest(invocation(
                permitted.actor().player(), "channel", "local", "Ta")));
        assertEquals(List.of("local", "l"), permitted.command().suggest(invocation(
                permitted.actor().player(), "channel", "l")));
    }

    private Fixture fixture(Set<String> actorPermissions) throws Exception {
        ChannelRegistry channels = new ChannelRegistry(List.of(
                channel("global", "Global", "g"),
                channel("local", "Local", "l")));
        PlayerStateService states = new PlayerStateService(
                new InMemorySocialRepository("global"), channels, "global");
        TestPlayer actor = player("Actor", actorPermissions);
        TestPlayer target = player("Target", Set.of());
        for (TestPlayer player : List.of(actor, target)) {
            states.activate(player.id());
            states.load(player.id()).toCompletableFuture().join();
        }
        ProxyServer proxy = proxy(List.of(actor.player(), target.player()));
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);
        ChannelCommand command = new ChannelCommand(
                proxy, channels, states, (player, permission) -> false, responses);
        return new Fixture(command, states, actor, target);
    }

    private static ChannelDefinition channel(String id, String displayName, String alias) {
        return new ChannelDefinition(
                id, displayName, ChannelScope.GLOBAL,
                "tkchat.channel." + id + ".send",
                "tkchat.channel." + id + ".receive",
                Permissions.BYPASS_CHANNEL_RESTRICTIONS,
                List.of(alias), "<message>");
    }

    private static TestPlayer player(String username, Set<String> permissions) {
        UUID id = UUID.randomUUID();
        ArrayList<Component> messages = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getUsername" -> username;
                    case "getPermissionValue" -> permissions.contains(arguments[0])
                            ? Tristate.TRUE : Tristate.FALSE;
                    case "hasPermission" -> permissions.contains(arguments[0]);
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
                    case "toString" -> username;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                });
        return new TestPlayer(id, player, messages);
    }

    private static ProxyServer proxy(List<Player> players) {
        Map<String, Player> byName = players.stream().collect(java.util.stream.Collectors.toMap(
                player -> player.getUsername().toLowerCase(Locale.ROOT),
                player -> player));
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> arguments[0] instanceof String name
                            ? Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)))
                            : Optional.empty();
                    case "getAllPlayers" -> (Collection<Player>) players;
                    default -> defaultValue(method.getReturnType());
                });
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

    private record TestPlayer(UUID id, Player player, List<Component> messages) {
    }

    private record Fixture(
            ChannelCommand command,
            PlayerStateService states,
            TestPlayer actor,
            TestPlayer target
    ) {
    }
}
