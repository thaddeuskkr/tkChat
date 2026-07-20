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
        assertEquals("tkchat.channels.global.send",
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
        assertTrue(messages.contains("no-permission:"));
        assertTrue(messages.contains("Usage: /me <action>"));
        assertTrue(messages.contains("invite-received:"));
        assertEquals("<red>Unknown tkChat command.</red>",
                config.messages.template(ResponseKey.ROOT_UNKNOWN));
    }

    @Test
    void existingConfigsAndMessagesReceiveNewDefaultsWithoutBeingRewritten() throws Exception {
        new ConfigLoader().load(directory);
        Path configPath = directory.resolve("config.yml");
        String oldConfig = Files.readString(configPath).replace(
                "  me: '<gray>* </gray><prefix><name><suffix> <message>'\n", "");
        Files.writeString(configPath, oldConfig);
        Path messagesPath = directory.resolve("messages.yml");
        String oldMessages = Files.readString(messagesPath).replace(
                "me:\n"
                        + "  player-only: '<red>Only players can use /me.</red>'\n"
                        + "  usage: '<red>Usage: /me <action> (maximum <max_length> characters)</red>'\n\n",
                "");
        Files.writeString(messagesPath, oldMessages);

        AppConfig upgraded = new ConfigLoader().load(directory);

        assertEquals("<gray>* </gray><prefix><name><suffix> <message>",
                upgraded.formats.me);
        assertEquals("<red>Usage: /me <action> (maximum <max_length> characters)</red>",
                upgraded.messages.template(ResponseKey.ME_USAGE));
        assertEquals(oldConfig, Files.readString(configPath));
        assertEquals(oldMessages, Files.readString(messagesPath));
    }

    @Test
    void permissionNodesFollowFixedScheme() {
        assertEquals("tkchat.command.broadcast", Permissions.command("Broadcast"));
        assertEquals("tkchat.channels.staff_chat.receive", Permissions.channelReceive("Staff-Chat"));
        assertEquals("tkchat.bypass.private_groups", Permissions.BYPASS_PRIVATE_GROUPS);
        assertEquals("tkchat.format.dark_blue", Permissions.format("dark-blue"));
    }
}
