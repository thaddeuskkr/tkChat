package dev.tkkr.tkchat.velocity.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.ChatPolicy;
import dev.tkkr.tkchat.velocity.Permissions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AppConfig {
    public String instanceId = "velocity-1";
    public String defaultChannel = "global";
    public boolean requireSignedVelocity = true;
    public Chat chat = new Chat();
    public Mentions mentions = new Mentions();
    public ItemLinks itemLinks = new ItemLinks();
    public MariaDb mariadb = new MariaDb();
    public RabbitMq rabbitmq = new RabbitMq();
    public LibertyBans libertyBans = new LibertyBans();
    public Formats formats = new Formats();
    public List<Channel> channels = new ArrayList<>();
    @JsonIgnore
    public ResponseMessages messages;

    public void validate() {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instance-id cannot be blank");
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("At least one channel must be configured");
        }
        ChannelRegistry registry = new ChannelRegistry(channelDefinitions());
        ChannelDefinition configuredDefault = registry.find(defaultChannel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "default-channel must name a configured channel or alias"));
        defaultChannel = configuredDefault.id();
        if (mariadb.enabled && (mariadb.jdbcUrl == null
                || !mariadb.jdbcUrl.startsWith("jdbc:mariadb://"))) {
            throw new IllegalArgumentException("mariadb.jdbc-url must be a MariaDB JDBC URL");
        }
        if (mariadb.enabled && (mariadb.username == null || mariadb.username.isBlank())) {
            throw new IllegalArgumentException("mariadb.username cannot be blank");
        }
        if (mariadb.tablePrefix == null || !mariadb.tablePrefix.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException(
                    "mariadb.table-prefix must contain 1-32 letters, numbers, or underscores");
        }
        if (mariadb.connectionTimeoutMillis < 250
                || mariadb.validationTimeoutMillis < 250
                || mariadb.connectTimeoutMillis < 100
                || mariadb.socketTimeoutMillis < 100) {
            throw new IllegalArgumentException("MariaDB timeouts are too short");
        }
        if (mariadb.validationTimeoutMillis > mariadb.connectionTimeoutMillis) {
            throw new IllegalArgumentException(
                    "mariadb.validation-timeout-millis cannot exceed connection-timeout-millis");
        }
        if (mariadb.idleTimeoutMillis < 10_000 || mariadb.maxLifetimeMillis < 30_000) {
            throw new IllegalArgumentException(
                    "MariaDB idle timeout must be at least 10000ms and max lifetime at least 30000ms");
        }
        if (mariadb.maximumPoolSize < 1 || mariadb.maximumPoolSize > 64
                || mariadb.minimumIdle < 0 || mariadb.minimumIdle > mariadb.maximumPoolSize) {
            throw new IllegalArgumentException("Invalid MariaDB connection pool sizes");
        }
        if (mariadb.workerThreads < 1 || mariadb.workerThreads > 64) {
            throw new IllegalArgumentException("mariadb.worker-threads must be between 1 and 64");
        }
        if (mariadb.maxQueuedOperations < 1 || mariadb.maxQueuedOperations > 100_000) {
            throw new IllegalArgumentException(
                    "mariadb.max-queued-operations must be between 1 and 100000");
        }
        if (chat.clearLines < 1 || chat.clearLines > 200) {
            throw new IllegalArgumentException("chat.clear-lines must be between 1 and 200");
        }
        if (chat.maxPendingMessagesPerSender < 1 || chat.maxPendingMessagesPerSender > 100) {
            throw new IllegalArgumentException(
                    "chat.max-pending-messages-per-sender must be between 1 and 100");
        }
        if (chat.maxMessageAgeMillis < 100 || chat.maxMessageAgeMillis > 60_000) {
            throw new IllegalArgumentException(
                    "chat.max-message-age-millis must be between 100 and 60000");
        }
        if (chat.maxDeliveryAgeMillis < 1_000 || chat.maxDeliveryAgeMillis > 300_000) {
            throw new IllegalArgumentException(
                    "chat.max-delivery-age-millis must be between 1000 and 300000");
        }
        if (mentions.prefix == null || mentions.prefix.isBlank()) {
            throw new IllegalArgumentException("mentions.prefix cannot be blank");
        }
        if (mentions.soundVolume < 0 || mentions.soundPitch < 0 || mentions.soundPitch > 2) {
            throw new IllegalArgumentException("Invalid mention sound volume or pitch");
        }
        if (itemLinks.placeholders == null || itemLinks.placeholders.isEmpty()
                || itemLinks.placeholders.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("item-links.placeholders must contain at least one value");
        }
        if (itemLinks.responseTimeoutMillis < 100 || itemLinks.responseTimeoutMillis > 10_000) {
            throw new IllegalArgumentException("item-links.response-timeout-millis must be between 100 and 10000");
        }
        if (formats.responsePrefix == null) {
            throw new IllegalArgumentException("formats.response-prefix cannot be null");
        }
        if (formats.me == null) {
            throw new IllegalArgumentException("formats.me cannot be null");
        }
        if (formats.join == null || formats.leave == null
                || formats.globalJoin == null || formats.globalLeave == null) {
            throw new IllegalArgumentException("join and leave formats cannot be null");
        }
    }

    public ChatPolicy policy() {
        return new ChatPolicy(chat.maxMessageLength, chat.rateLimitMessages,
                Duration.ofMillis(chat.rateLimitWindowMillis));
    }

    public List<ChannelDefinition> channelDefinitions() {
        return channels.stream().map(channel -> new ChannelDefinition(
                channel.id,
                channel.displayName,
                ChannelScope.valueOf(channel.scope.toUpperCase()),
                Permissions.channelSend(channel.id),
                Permissions.channelReceive(channel.id),
                Permissions.BYPASS_CHANNEL_RESTRICTIONS,
                channel.aliases,
                channel.format
        )).toList();
    }

    public static final class Chat {
        public int maxMessageLength = 256;
        public int rateLimitMessages = 5;
        public long rateLimitWindowMillis = 5_000;
        public int clearLines = 50;
        public int maxPendingMessagesPerSender = 8;
        public long maxMessageAgeMillis = 5_000;
        public long maxDeliveryAgeMillis = 30_000;
    }

    public static final class Mentions {
        public boolean enabled = true;
        public String prefix = "@";
        public String highlightFormat = "<yellow><bold><mention></bold></yellow>";
        public boolean playSound = true;
        public String sound = "minecraft:entity.experience_orb.pickup";
        public float soundVolume = 1.0f;
        public float soundPitch = 1.0f;
    }

    public static final class ItemLinks {
        public boolean enabled = true;
        public List<String> placeholders = new ArrayList<>(List.of("<item>", "[item]"));
        public String format = "<aqua>[<amount>x <item_name>]</aqua>";
        public long responseTimeoutMillis = 1_500;
    }

    public static final class MariaDb {
        public boolean enabled = true;
        public boolean fallbackToMemory = false;
        public String jdbcUrl = "jdbc:mariadb://127.0.0.1:3306/tkchat";
        public String username = "tkchat";
        public String password = "";
        public String tablePrefix = "tkchat";
        public int maximumPoolSize = 8;
        public int minimumIdle = 2;
        public long connectionTimeoutMillis = 10_000;
        public long validationTimeoutMillis = 5_000;
        public long idleTimeoutMillis = 600_000;
        public long maxLifetimeMillis = 1_800_000;
        public long connectTimeoutMillis = 10_000;
        public long socketTimeoutMillis = 15_000;
        public int workerThreads = 8;
        public int maxQueuedOperations = 1_024;
    }

    public static final class RabbitMq {
        public boolean enabled = true;
        public boolean fallbackToLocal = true;
        public String uri = "amqp://guest:guest@127.0.0.1:5672/%2f";
        public String exchange = "tkchat.events";
        public String queuePrefix = "tkchat.velocity";
        public long publishConfirmTimeoutMillis = 5_000;
    }

    public static final class LibertyBans {
        public boolean failClosed = true;
    }

    public static final class Formats {
        public String responsePrefix = "<gradient:#55FFFF:#55FF55><bold>tkChat</bold></gradient> "
                + "<dark_gray>»</dark_gray> ";
        public String me = "<gray>* </gray><prefix><name><suffix> <message>";
        public String directIncoming = "<dark_gray>[</dark_gray><light_purple>DM from <name></light_purple><dark_gray>]</dark_gray> <message>";
        public String directOutgoing = "<dark_gray>[</dark_gray><light_purple>DM to <target></light_purple><dark_gray>]</dark_gray> <message>";
        public String group = "<dark_gray>[</dark_gray><aqua><target></aqua><dark_gray>]</dark_gray> <prefix><name><suffix><dark_gray>: </dark_gray><message>";
        public String broadcast = "<dark_gray>[</dark_gray><gold>Broadcast</gold><dark_gray>]</dark_gray> <message>";
        public String chatClear = "<gray><target> chat was cleared by <white><name></white>.</gray>";
        public String socialSpy = "<dark_gray>[Spy: <target>]</dark_gray> <name><dark_gray>: </dark_gray><message>";
        public String globalJoin = "";
        public String globalLeave = "";
        public String join = "<yellow><name> joined the server.</yellow>";
        public String leave = "<yellow><name> left the server.</yellow>";
    }

    public static final class Channel {
        public String id;
        public List<String> aliases = new ArrayList<>();
        public String displayName;
        public String scope;
        public String format;
    }
}
