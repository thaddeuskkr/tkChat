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

        assertTrue(yaml.contains("mongodb:"));
        assertTrue(yaml.contains("rabbitmq:"));
        assertEquals("tkchat.channels.global.send",
                config.channelDefinitions().getFirst().sendPermission());
        assertEquals("global", config.channelDefinitions().getFirst().id());
        assertTrue(config.channelDefinitions().getFirst().aliases().contains("g"));
        assertEquals(10_000, config.mongodb.connectTimeoutMillis);
        assertEquals(10_000, config.mongodb.readTimeoutMillis);
        assertEquals(15_000, config.mongodb.operationTimeoutMillis);
        assertEquals(4, config.mongodb.workerThreads);
        assertTrue(yaml.contains("operation-timeout-millis: 15000"));
    }

    @Test
    void permissionNodesFollowFixedScheme() {
        assertEquals("tkchat.command.broadcast", Permissions.command("Broadcast"));
        assertEquals("tkchat.channels.staff_chat.receive", Permissions.channelReceive("Staff-Chat"));
        assertEquals("tkchat.bypass.private_groups", Permissions.BYPASS_PRIVATE_GROUPS);
        assertEquals("tkchat.format.dark_blue", Permissions.format("dark-blue"));
    }
}
