package dev.tkkr.tkchat.velocity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
