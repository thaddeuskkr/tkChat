package dev.tkkr.tkchat.velocity.service;

import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigLoader;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResponseServiceTest {
    @TempDir
    Path directory;

    @Test
    void appliesPrefixAndKeepsDynamicTextLiteral() throws Exception {
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                config.formats.responsePrefix, config.messages);

        assertEquals("tkChat » Active channel set to <red>literal</red>.", plain(
                responses.message(
                        ResponseKey.CHANNEL_ACTIVE_SET,
                        ResponseService.text("channel", "<red>literal</red>"))));
    }

    @Test
    void emptyPrefixDisablesTheGlobalPrefix() throws Exception {
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService("", config.messages);

        assertEquals("Invalid command", plain(
                responses.message(ResponseKey.ROOT_UNKNOWN)));
    }

    @Test
    void commandArgumentsRemainVisibleInUsageResponses() throws Exception {
        AppConfig config = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService("", config.messages);

        assertEquals("Usage: /msg <player> <message>", plain(
                responses.message(ResponseKey.DIRECT_USAGE)));
        assertEquals("Usage: /channel <channel> [player]", plain(
                responses.message(ResponseKey.CHANNEL_USAGE)));
        assertEquals("Usage: /group <create|list|join|invite|accept|leave|chat>", plain(
                responses.message(ResponseKey.GROUP_ROOT_USAGE)));
    }

    @Test
    void customMessageAndPrefixAreLoadedFromTheirSeparateFiles() throws Exception {
        AppConfig initial = new ConfigLoader().load(directory);
        Path messagesPath = directory.resolve("messages.yml");
        Files.writeString(messagesPath, Files.readString(messagesPath).replace(
                "<red>Invalid command</red>",
                "<gold>Choose another command.</gold>"));
        Path configPath = directory.resolve("config.yml");
        Files.writeString(configPath, Files.readString(configPath).replace(
                "<gradient:#55FFFF:#55FF55><bold>tkChat</bold></gradient> <dark_gray>»</dark_gray> ",
                "<blue>Network » </blue>"));

        AppConfig customized = new ConfigLoader().load(directory);
        ResponseService responses = new ResponseService(
                initial.formats.responsePrefix, initial.messages);
        responses.reconfigure(customized.formats.responsePrefix, customized.messages);

        assertEquals("Network » Choose another command.", plain(
                responses.message(ResponseKey.ROOT_UNKNOWN)));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
