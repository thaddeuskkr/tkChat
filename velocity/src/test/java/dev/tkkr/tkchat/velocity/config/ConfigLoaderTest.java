package dev.tkkr.tkchat.velocity.config;

import dev.tkkr.tkchat.velocity.Permissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void defaultConfigUsesFreshSchemaAndDerivedPermissions() throws Exception {
        AppConfig config = new ConfigLoader().load(directory);
        String yaml = Files.readString(directory.resolve("config.yml"));
        String messages = Files.readString(directory.resolve("messages.yml"));

        assertTrue(yaml.contains("mariadb:"));
        assertTrue(yaml.contains("rabbitmq:"));
        assertEquals("tkchat.channel.global.send",
                config.channelDefinitions().getFirst().sendPermission());
        assertEquals("global", config.channelDefinitions().getFirst().id());
        assertTrue(config.channelDefinitions().getFirst().aliases().contains("g"));
        assertEquals("jdbc:mariadb://127.0.0.1:3306/tkchat", config.mariadb.jdbcUrl);
        assertEquals(8, config.mariadb.maximumPoolSize);
        assertEquals(2, config.mariadb.minimumIdle);
        assertEquals(10_000, config.mariadb.connectionTimeoutMillis);
        assertEquals(15_000, config.mariadb.socketTimeoutMillis);
        assertEquals(8, config.mariadb.workerThreads);
        assertEquals(1_024, config.mariadb.maxQueuedOperations);
        assertEquals(8, config.chat.maxPendingMessagesPerSender);
        assertEquals(5_000, config.chat.maxMessageAgeMillis);
        assertEquals(30_000, config.chat.maxDeliveryAgeMillis);
        assertTrue(yaml.contains("socket-timeout-millis: 15000"));
        assertTrue(yaml.contains("max-queued-operations: 1024"));
        assertTrue(yaml.contains("response-prefix:"));
        assertTrue(yaml.contains("me: '<gray>* </gray><prefix><name><suffix> <message>'"));
        assertTrue(yaml.contains("global-join: ''"));
        assertTrue(yaml.contains("join: '<yellow><name> joined the server.</yellow>'"));
        assertEquals("", config.formats.globalLeave);
        assertEquals("<yellow><name> left the server.</yellow>", config.formats.leave);
        assertTrue(messages.contains("no-permission:"));
        assertTrue(messages.contains("Usage: /me <action>"));
        assertTrue(messages.contains("invite-received:"));
        assertTrue(messages.contains("invite-members:"));
        assertEquals("<red>Invalid command</red>",
                config.messages.template(ResponseKey.ROOT_UNKNOWN));
    }

    @Test
    void existingConfigsAndMessagesReceiveNewDefaultsWithoutBeingRewritten() throws Exception {
        new ConfigLoader().load(directory);
        Path configPath = directory.resolve("config.yml");
        String oldConfig = Files.readString(configPath).replace(
                "  me: '<gray>* </gray><prefix><name><suffix> <message>'\n", "")
                .replace("  global-join: ''\n", "")
                .replace("  global-leave: ''\n", "")
                .replace("  join: '<yellow><name> joined the server.</yellow>'\n", "")
                .replace("  leave: '<yellow><name> left the server.</yellow>'\n", "");
        Files.writeString(configPath, oldConfig);
        Path messagesPath = directory.resolve("messages.yml");
        String oldMessages = Files.readString(messagesPath).replace(
                "me:\n"
                        + "  player-only: '<red>Only players can use /me.</red>'\n"
                        + "  usage: '<red>Usage: /me <action> (maximum <max_length> characters)</red>'\n\n",
                "")
                .replace("  active-set-other: '<green>Set <player>''s active channel to <channel>.</green>'\n", "")
                .replace("  active-set-forced: '<green>Your active channel was set to <channel>.</green>'\n", "")
                .replace("  target-offline: '<red>That player is not online.</red>'\n", "")
                .replace("  target-not-ready: '<red>That player''s chat data is not ready.</red>'\n", "")
                .replace("  status-owner: '<gray>Owner: <owner></gray>'\n", "")
                .replace("  status-members: '<gray>Members: <members></gray>'\n", "")
                .replace("  status-invited-tag: '<dark_gray> [invited]</dark_gray>'\n", "")
                .replace("  status-offline-tag: '<dark_gray> (offline)</dark_gray>'\n", "")
                .replace("  list-public-entry: '<dark_gray> • </dark_gray><aqua><group></aqua> <button>'\n", "")
                .replace("  list-owner: '<dark_gray>· Owner:</dark_gray> <owner> '\n", "")
                .replace("  invite-members: '<gray>Current members: <members></gray>'\n", "")
                .replace("  invite-members-unavailable: '<gray>Current members are temporarily unavailable.</gray>'\n", "")
                .replace("  member-joined: '<green><player> joined <group>.</green>'\n", "");
        Files.writeString(messagesPath, oldMessages);

        AppConfig upgraded = new ConfigLoader().load(directory);

        assertEquals("<gray>* </gray><prefix><name><suffix> <message>",
                upgraded.formats.me);
        assertEquals("", upgraded.formats.globalJoin);
        assertEquals("", upgraded.formats.globalLeave);
        assertEquals("<yellow><name> joined the server.</yellow>", upgraded.formats.join);
        assertEquals("<yellow><name> left the server.</yellow>", upgraded.formats.leave);
        assertEquals("<red>Usage: /me <action> (maximum <max_length> characters)</red>",
                upgraded.messages.template(ResponseKey.ME_USAGE));
        assertEquals("<green>Set <player>'s active channel to <channel>.</green>",
                upgraded.messages.template(ResponseKey.CHANNEL_ACTIVE_SET_OTHER));
        assertEquals("<gray>Owner: <owner></gray>",
                upgraded.messages.template(ResponseKey.GROUP_STATUS_OWNER));
        assertEquals("<dark_gray> [invited]</dark_gray>",
                upgraded.messages.template(ResponseKey.GROUP_STATUS_INVITED_TAG));
        assertEquals("<dark_gray> (offline)</dark_gray>",
                upgraded.messages.template(ResponseKey.GROUP_STATUS_OFFLINE_TAG));
        assertEquals("<dark_gray> • </dark_gray><aqua><group></aqua> <button>",
                upgraded.messages.template(ResponseKey.GROUP_LIST_PUBLIC_ENTRY));
        assertEquals("<gray>Groups:</gray>",
                upgraded.messages.template(ResponseKey.GROUP_LIST_HEADING_ALL));
        assertEquals("<dark_gray> • </dark_gray><aqua><group></aqua> "
                        + "<dark_gray>(<visibility>)</dark_gray> <button>",
                upgraded.messages.template(ResponseKey.GROUP_LIST_ENTRY));
        assertEquals("<dark_gray>· Owner:</dark_gray> <owner> ",
                upgraded.messages.template(ResponseKey.GROUP_LIST_OWNER));
        assertEquals("<gray>Current members: <members></gray>",
                upgraded.messages.template(ResponseKey.GROUP_INVITE_MEMBERS));
        assertEquals("<gray>Current members are temporarily unavailable.</gray>",
                upgraded.messages.template(ResponseKey.GROUP_INVITE_MEMBERS_UNAVAILABLE));
        assertEquals("<green><player> joined <group>.</green>",
                upgraded.messages.template(ResponseKey.GROUP_MEMBER_JOINED));
        assertEquals(oldConfig, Files.readString(configPath));
        assertEquals(oldMessages, Files.readString(messagesPath));
    }

    @Test
    void permissionNodesFollowFixedScheme() {
        assertEquals("tkchat.command.broadcast", Permissions.command("Broadcast"));
        assertEquals("tkchat.command.channel.others", Permissions.CHANNEL_OTHERS);
        assertEquals("tkchat.channel.staff_chat.receive", Permissions.channelReceive("Staff-Chat"));
        assertEquals("tkchat.bypass.private_groups", Permissions.BYPASS_PRIVATE_GROUPS);
        assertEquals("tkchat.bypass.group_join_notifications",
                Permissions.BYPASS_GROUP_JOIN_NOTIFICATIONS);
        assertEquals("tkchat.format.dark_blue", Permissions.format("dark-blue"));
    }
}
