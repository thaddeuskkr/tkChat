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
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.Permissions;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void listShowsOnlyPublicGroupsWithoutVisibilityForRegularPlayers() throws Exception {
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        TestPlayer viewer = player("Viewer");
        TestPlayer onlineOwner = player("OnlineOwner");
        UUID offlineOwnerId = UUID.randomUUID();
        repository.createGroup(
                offlineOwnerId, "Builders", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.createGroup(
                onlineOwner.id(), "Creators", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.createGroup(
                UUID.randomUUID(), "Secret", GroupVisibility.PRIVATE, "secret-pass")
                .toCompletableFuture().join();
        repository.recordPlayerName(offlineOwnerId, "OfflineOwner")
                .toCompletableFuture().join();

        AtomicInteger nameLookups = new AtomicInteger();
        SocialRepository countedRepository = (SocialRepository) Proxy.newProxyInstance(
                SocialRepository.class.getClassLoader(),
                new Class<?>[]{SocialRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("playerNames")) {
                        nameLookups.incrementAndGet();
                    }
                    try {
                        return method.invoke(repository, arguments);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "tkchat.channel.global.send", "tkchat.channel.global.receive",
                "tkchat.bypass.channel_restrictions", List.of("g"), "<message>")));
        PlayerStateService states = new PlayerStateService(repository, channels, "global");
        states.activate(viewer.id());
        states.load(viewer.id(), viewer.player().getUsername()).toCompletableFuture().join();
        ProxyServer proxy = proxy(List.of(viewer.player(), onlineOwner.player()));
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);
        GroupCommand command = new GroupCommand(
                proxy, countedRepository, null, states, channels,
                (player, permission) -> false,
                responses);

        command.execute(invocation(viewer.player(), "group", "list"));

        assertEquals(List.of(
                "tkChat » Public groups:",
                "tkChat »  • Builders · Owner: OfflineOwner (offline) [Join]",
                "tkChat »  • Creators · Owner: OnlineOwner [Join]"),
                plain(viewer.messages()));
        assertEquals(NamedTextColor.GRAY,
                textColor(viewer.messages().get(1), "OfflineOwner", null).orElseThrow());
        assertEquals(NamedTextColor.WHITE,
                textColor(viewer.messages().get(2), "OnlineOwner", null).orElseThrow());
        assertEquals(1, nameLookups.get());
    }

    @Test
    void listShowsAllGroupsAndVisibilityForPrivateGroupBypass() throws Exception {
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        TestPlayer viewer = player("Admin");
        UUID publicOwnerId = UUID.randomUUID();
        UUID privateOwnerId = UUID.randomUUID();
        repository.createGroup(publicOwnerId, "Open", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.createGroup(
                privateOwnerId, "Secret", GroupVisibility.PRIVATE, "secret-pass")
                .toCompletableFuture().join();
        repository.recordPlayerName(publicOwnerId, "PublicOwner").toCompletableFuture().join();
        repository.recordPlayerName(privateOwnerId, "PrivateOwner").toCompletableFuture().join();

        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "tkchat.channel.global.send", "tkchat.channel.global.receive",
                "tkchat.bypass.channel_restrictions", List.of("g"), "<message>")));
        PlayerStateService states = new PlayerStateService(repository, channels, "global");
        states.activate(viewer.id());
        states.load(viewer.id(), viewer.player().getUsername()).toCompletableFuture().join();
        ProxyServer proxy = proxy(List.of(viewer.player()));
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);
        GroupCommand command = new GroupCommand(
                proxy, repository, null, states, channels,
                (player, permission) -> permission.equals(Permissions.BYPASS_PRIVATE_GROUPS),
                responses);

        command.execute(invocation(viewer.player(), "group", "list"));

        assertEquals(List.of(
                "tkChat » Groups:",
                "tkChat »  • Open (public) · Owner: PublicOwner (offline) [Join]",
                "tkChat »  • Secret (private) · Owner: PrivateOwner (offline) [Join]"),
                plain(viewer.messages()));
    }

    @Test
    void inviteShowsTheCurrentAcceptedMembersWithOneBatchedNameLookup() throws Exception {
        InMemorySocialRepository repository = new InMemorySocialRepository("global");
        TestPlayer owner = player("Owner");
        TestPlayer target = player("Target");
        UUID memberId = UUID.randomUUID();
        var group = repository.createGroup(
                owner.id(), "Builders", GroupVisibility.PUBLIC, null)
                .toCompletableFuture().join();
        repository.joinGroup(memberId, group.name(), null, false, Instant.now())
                .toCompletableFuture().join();
        repository.recordPlayerName(memberId, "Member").toCompletableFuture().join();

        AtomicInteger nameLookups = new AtomicInteger();
        SocialRepository countedRepository = (SocialRepository) Proxy.newProxyInstance(
                SocialRepository.class.getClassLoader(),
                new Class<?>[]{SocialRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("playerNames")) {
                        nameLookups.incrementAndGet();
                    }
                    try {
                        return method.invoke(repository, arguments);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        ChannelRegistry channels = new ChannelRegistry(List.of(new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "tkchat.channel.global.send", "tkchat.channel.global.receive",
                "tkchat.bypass.channel_restrictions", List.of("g"), "<message>")));
        PlayerStateService states = new PlayerStateService(repository, channels, "global");
        states.activate(owner.id());
        states.load(owner.id(), owner.player().getUsername()).toCompletableFuture().join();
        ProxyServer proxy = proxy(List.of(owner.player(), target.player()));
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);
        GroupCommand command = new GroupCommand(
                proxy, countedRepository, null, states, channels,
                (player, permission) -> false,
                responses);

        command.execute(invocation(owner.player(), "group", "invite", "Target"));

        assertEquals(List.of("tkChat » Invited Target."), plain(owner.messages()));
        assertEquals(List.of(
                "tkChat » Owner invited you to Builders. [Accept]",
                "tkChat » Current members: Owner, Member (offline)"),
                plain(target.messages()));
        assertEquals(NamedTextColor.WHITE,
                textColor(target.messages().get(1), "Owner", null).orElseThrow());
        assertEquals(NamedTextColor.GRAY,
                textColor(target.messages().get(1), "Member", null).orElseThrow());
        assertEquals(1, nameLookups.get());
        assertEquals(java.util.Set.of(target.id()),
                repository.groupInvitees(group.id(), Instant.now()).toCompletableFuture().join());
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
        Map<String, Player> byName = players.stream().collect(java.util.stream.Collectors.toMap(
                player -> player.getUsername().toLowerCase(java.util.Locale.ROOT),
                player -> player));
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlayer" -> arguments[0] instanceof UUID id
                            ? Optional.ofNullable(byId.get(id))
                            : arguments[0] instanceof String name
                            ? Optional.ofNullable(byName.get(name.toLowerCase(java.util.Locale.ROOT)))
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
