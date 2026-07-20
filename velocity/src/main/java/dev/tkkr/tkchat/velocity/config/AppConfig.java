package dev.tkkr.tkchat.velocity.config;

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
    public MongoDb mongodb = new MongoDb();
    public RabbitMq rabbitmq = new RabbitMq();
    public LibertyBans libertyBans = new LibertyBans();
    public Formats formats = new Formats();
    public List<Channel> channels = new ArrayList<>();

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
        if (mongodb.enabled && (mongodb.connectionString == null
                || !mongodb.connectionString.startsWith("mongodb"))) {
            throw new IllegalArgumentException("mongodb.connection-string must be a MongoDB URI");
        }
        if (mongodb.enabled && (mongodb.database == null || mongodb.database.isBlank())) {
            throw new IllegalArgumentException("mongodb.database cannot be blank");
        }
        if (mongodb.collectionPrefix == null || mongodb.collectionPrefix.isBlank()) {
            throw new IllegalArgumentException("mongodb.collection-prefix cannot be blank");
        }
        if (mongodb.serverSelectionTimeoutMillis < 100
                || mongodb.connectTimeoutMillis < 100
                || mongodb.readTimeoutMillis < 100
                || mongodb.operationTimeoutMillis < 100) {
            throw new IllegalArgumentException("MongoDB timeouts must be at least 100 milliseconds");
        }
        if (mongodb.workerThreads < 1 || mongodb.workerThreads > 32) {
            throw new IllegalArgumentException("mongodb.worker-threads must be between 1 and 32");
        }
        if (chat.clearLines < 1 || chat.clearLines > 200) {
            throw new IllegalArgumentException("chat.clear-lines must be between 1 and 200");
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

    public static final class MongoDb {
        public boolean enabled = true;
        public boolean fallbackToMemory = false;
        public String connectionString = "mongodb://127.0.0.1:27017/?authSource=admin";
        public String database = "tkchat";
        public String collectionPrefix = "tkchat";
        public long serverSelectionTimeoutMillis = 10_000;
        public long connectTimeoutMillis = 10_000;
        public long readTimeoutMillis = 10_000;
        public long operationTimeoutMillis = 15_000;
        public int workerThreads = 4;
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
        public String directIncoming = "<dark_gray>[</dark_gray><light_purple>DM from <name></light_purple><dark_gray>]</dark_gray> <message>";
        public String directOutgoing = "<dark_gray>[</dark_gray><light_purple>DM to <target></light_purple><dark_gray>]</dark_gray> <message>";
        public String group = "<dark_gray>[</dark_gray><aqua><target></aqua><dark_gray>]</dark_gray> <prefix><name><suffix><dark_gray>: </dark_gray><message>";
        public String broadcast = "<dark_gray>[</dark_gray><gold>Broadcast</gold><dark_gray>]</dark_gray> <message>";
        public String chatClear = "<gray><target> chat was cleared by <white><name></white>.</gray>";
        public String socialSpy = "<dark_gray>[Spy: <target>]</dark_gray> <name><dark_gray>: </dark_gray><message>";
    }

    public static final class Channel {
        public String id;
        public List<String> aliases = new ArrayList<>();
        public String displayName;
        public String scope;
        public String format;
    }
}
