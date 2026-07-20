package dev.tkkr.tkchat.velocity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigLoader {
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

    public AppConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream defaults = ConfigLoader.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Bundled config.yml is missing");
                }
                Files.copy(defaults, configPath);
            }
        }
        AppConfig config = mapper.readValue(configPath.toFile(), AppConfig.class);
        config.messages = loadMessages(dataDirectory);
        String mariaUrl = System.getenv("TKCHAT_MARIADB_URL");
        if (mariaUrl != null && !mariaUrl.isBlank()) {
            config.mariadb.jdbcUrl = mariaUrl;
        }
        String mariaUsername = System.getenv("TKCHAT_MARIADB_USERNAME");
        if (mariaUsername != null && !mariaUsername.isBlank()) {
            config.mariadb.username = mariaUsername;
        }
        String mariaPassword = System.getenv("TKCHAT_MARIADB_PASSWORD");
        if (mariaPassword != null) {
            config.mariadb.password = mariaPassword;
        }
        String rabbitUri = System.getenv("TKCHAT_RABBITMQ_URI");
        if (rabbitUri != null && !rabbitUri.isBlank()) {
            config.rabbitmq.uri = rabbitUri;
        }
        config.validate();
        return config;
    }

    private ResponseMessages loadMessages(Path dataDirectory) throws IOException {
        Path messagesPath = dataDirectory.resolve("messages.yml");
        if (Files.notExists(messagesPath)) {
            try (InputStream defaults = ConfigLoader.class.getResourceAsStream("/messages.yml")) {
                if (defaults == null) {
                    throw new IOException("Bundled messages.yml is missing");
                }
                Files.copy(defaults, messagesPath);
            }
        }
        JsonNode root = mapper.readTree(messagesPath.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("messages.yml must contain a mapping of responses");
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        flattenMessages("", root, flattened);
        return new ResponseMessages(flattened);
    }

    private static void flattenMessages(
            String parent,
            JsonNode node,
            Map<String, String> flattened
    ) throws IOException {
        for (var field : node.properties()) {
            String path = parent.isEmpty() ? field.getKey() : parent + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flattenMessages(path, value, flattened);
            } else if (value.isTextual()) {
                flattened.put(path, value.textValue());
            } else {
                throw new IOException("messages.yml response " + path + " must be text");
            }
        }
    }
}
