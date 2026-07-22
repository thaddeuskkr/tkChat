package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.GroupVisibility;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigLoader;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupCommandTest {
    @TempDir
    Path directory;

    @Test
    void baseCommandShowsOwnerVisibilityMembersAndUnexpiredInvitees() throws Exception {
        Instant now = Instant.now();
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        TestPlayer owner = player("Owner");
        UUID memberId = UUID.randomUUID();
        UUID invitedId = UUID.randomUUID();
        UUID expiredInviteeId = UUID.randomUUID();
        var group = repository.createGroup(
                owner.id(), "Builders", GroupVisibility.PRIVATE, "secret-pass")
                .toCompletableFuture().join();
        repository.joinGroup(memberId, group.name(), "secret-pass", false, now)
                .toCompletableFuture().join();
        repository.invite(group.id(), owner.id(), invitedId, now.plusSeconds(300))
                .toCompletableFuture().join();
        repository.invite(group.id(), owner.id(), expiredInviteeId, now.minusSeconds(1))
                .toCompletableFuture().join();
        repository.recordPlayerName(memberId, "Member").toCompletableFuture().join();
        repository.recordPlayerName(invitedId, "Pending").toCompletableFuture().join();
        repository.recordPlayerName(expiredInviteeId, "Expired").toCompletableFuture().join();

        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "tkchat.channel.global.send", "tkchat.channel.global.receive",
                "tkchat.bypass.channel_restrictions", List.of("g"), "<message>")));
        PlayerStateService states = new PlayerStateService(repository, channels, "global");
        states.activate(owner.id());
        states.load(owner.id()).toCompletableFuture().join();
        ProxyServer proxy = proxy(List.of(owner.player()));
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);
        GroupCommand command = new GroupCommand(
                proxy, repository, null, states, channels,
                (player, permission) -> false,
                responses);

        command.execute(invocation(owner.player(), "group"));

        assertEquals(List.of(
                "tkChat » Group: Builders · private [Switch channel]",
                "tkChat » Owner: Owner",
                "tkChat » Members: Owner, Member (offline), Pending [invited] (offline)"),
                plain(owner.messages()));
        assertEquals(NamedTextColor.WHITE,
                textColor(owner.messages().get(2), "Owner", null).orElseThrow());
        assertEquals(NamedTextColor.GRAY,
                textColor(owner.messages().get(2), "Member", null).orElseThrow());
        assertEquals(NamedTextColor.GRAY,
                textColor(owner.messages().get(2), "Pending", null).orElseThrow());
    }

    private static TestPlayer player(String username) {
        UUID id = UUID.randomUUID();
        ArrayList<Component> messages = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getUsername" -> username;
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
        Map<UUID, Player> byId = players.stream().collect(java.util.stream.Collectors.toMap(
                Player::getUniqueId, player -> player));
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> arguments[0] instanceof UUID id
                            ? Optional.ofNullable(byId.get(id))
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

    private static Optional<TextColor> textColor(
            Component root,
            String content,
            TextColor inheritedColor
    ) {
        TextColor effectiveColor = root.color() == null ? inheritedColor : root.color();
        if (root instanceof TextComponent text && text.content().contains(content)) {
            return Optional.ofNullable(effectiveColor);
        }
        for (Component child : root.children()) {
            Optional<TextColor> found = textColor(child, content, effectiveColor);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
}
