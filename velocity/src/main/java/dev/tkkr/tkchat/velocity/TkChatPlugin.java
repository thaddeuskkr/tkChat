package dev.tkkr.tkchat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.core.service.ChatRouter;
import dev.tkkr.tkchat.core.service.InMemorySocialRepository;
import dev.tkkr.tkchat.core.service.LocalMessageTransport;
import dev.tkkr.tkchat.core.service.MessageTransport;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.command.CommandRegistrar;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ConfigLoader;
import dev.tkkr.tkchat.velocity.config.ConfigReloadResult;
import dev.tkkr.tkchat.velocity.delivery.VelocityDeliveryService;
import dev.tkkr.tkchat.velocity.integration.VelocityAccessController;
import dev.tkkr.tkchat.velocity.listener.ChatListener;
import dev.tkkr.tkchat.velocity.listener.PlayerLifecycleListener;
import dev.tkkr.tkchat.velocity.listener.VanillaCommandBypassListener;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.service.ItemLinkService;
import dev.tkkr.tkchat.velocity.service.PlayerFormattingService;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;
import dev.tkkr.tkchat.velocity.storage.MongoSocialRepository;
import dev.tkkr.tkchat.velocity.transport.RabbitMessageTransport;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(
        id = "tkchat",
        name = "tkChat",
        version = "0.1.0-SNAPSHOT",
        description = "Velocity-led, cross-server channel chat",
        authors = {"tkkr"},
        dependencies = {
                @Dependency(id = "luckperms"),
                @Dependency(id = "libertybans"),
                @Dependency(id = "signedvelocity", optional = true)
        }
)
public final class TkChatPlugin {
    private record InfrastructureSnapshot(
            String instanceId,
            boolean mongodbEnabled,
            boolean mongodbFallbackToMemory,
            String mongodbConnectionString,
            String mongodbDatabase,
            String mongodbCollectionPrefix,
            long mongodbServerSelectionTimeoutMillis,
            long mongodbConnectTimeoutMillis,
            long mongodbReadTimeoutMillis,
            long mongodbOperationTimeoutMillis,
            int mongodbWorkerThreads,
            boolean rabbitmqEnabled,
            boolean rabbitmqFallbackToLocal,
            String rabbitmqUri,
            String rabbitmqExchange,
            String rabbitmqQueuePrefix,
            long rabbitmqPublishConfirmTimeoutMillis
    ) {
        private static InfrastructureSnapshot from(AppConfig config) {
            return new InfrastructureSnapshot(
                    config.instanceId,
                    config.mongodb.enabled,
                    config.mongodb.fallbackToMemory,
                    config.mongodb.connectionString,
                    config.mongodb.database,
                    config.mongodb.collectionPrefix,
                    config.mongodb.serverSelectionTimeoutMillis,
                    config.mongodb.connectTimeoutMillis,
                    config.mongodb.readTimeoutMillis,
                    config.mongodb.operationTimeoutMillis,
                    config.mongodb.workerThreads,
                    config.rabbitmq.enabled,
                    config.rabbitmq.fallbackToLocal,
                    config.rabbitmq.uri,
                    config.rabbitmq.exchange,
                    config.rabbitmq.queuePrefix,
                    config.rabbitmq.publishConfirmTimeoutMillis);
        }

        private List<String> changed(AppConfig config) {
            InfrastructureSnapshot next = from(config);
            ArrayList<String> changed = new ArrayList<>();
            if (!Objects.equals(instanceId, next.instanceId)) {
                changed.add("instance-id");
            }
            if (mongodbEnabled != next.mongodbEnabled
                    || mongodbFallbackToMemory != next.mongodbFallbackToMemory
                    || !Objects.equals(mongodbConnectionString, next.mongodbConnectionString)
                    || !Objects.equals(mongodbDatabase, next.mongodbDatabase)
                    || !Objects.equals(mongodbCollectionPrefix, next.mongodbCollectionPrefix)
                    || mongodbServerSelectionTimeoutMillis != next.mongodbServerSelectionTimeoutMillis
                    || mongodbConnectTimeoutMillis != next.mongodbConnectTimeoutMillis
                    || mongodbReadTimeoutMillis != next.mongodbReadTimeoutMillis
                    || mongodbOperationTimeoutMillis != next.mongodbOperationTimeoutMillis
                    || mongodbWorkerThreads != next.mongodbWorkerThreads) {
                changed.add("mongodb");
            }
            if (rabbitmqEnabled != next.rabbitmqEnabled
                    || rabbitmqFallbackToLocal != next.rabbitmqFallbackToLocal
                    || !Objects.equals(rabbitmqUri, next.rabbitmqUri)
                    || !Objects.equals(rabbitmqExchange, next.rabbitmqExchange)
                    || !Objects.equals(rabbitmqQueuePrefix, next.rabbitmqQueuePrefix)
                    || rabbitmqPublishConfirmTimeoutMillis != next.rabbitmqPublishConfirmTimeoutMillis) {
                changed.add("rabbitmq");
            }
            return List.copyOf(changed);
        }
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ExecutorService executor;

    private SocialRepository repository;
    private MessageTransport transport;
    private ItemLinkService itemLinks;
    private ChannelRegistry channels;
    private VelocityAccessController access;
    private PlayerStateService states;
    private VelocityDeliveryService delivery;
    private VelocityChatService chat;
    private NetworkMessageService networkMessages;
    private SocialSpyService spies;
    private CommandRegistrar commandRegistrar;
    private InfrastructureSnapshot infrastructure;
    private final List<Object> registeredRuntimeListeners = new ArrayList<>();
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile boolean ready;

    @Inject
    public TkChatPlugin(
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory,
            ExecutorService executor
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.executor = executor;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            AppConfig config = new ConfigLoader().load(dataDirectory);
            validateSignedVelocity(config);
            channels = new ChannelRegistry(config.channelDefinitions());
            repository = createRepository(config);
            access = new VelocityAccessController(config.libertyBans.failClosed);
            ChatRouter router = new ChatRouter(
                    channels, access, repository, config.policy(), Clock.systemUTC(),
                    Permissions.BYPASS_CHANNEL_RESTRICTIONS);
            transport = createTransport(config);
            states = new PlayerStateService(repository, channels, config.defaultChannel);
            spies = new SocialSpyService();
            PlayerFormattingService formatting = new PlayerFormattingService();
            delivery = new VelocityDeliveryService(
                    proxy, channels, access, config.formats, config.mentions, config.itemLinks,
                    formatting, states, spies, config.chat.clearLines);
            transport.start(delivery::deliver);

            ConversationTracker conversations = new ConversationTracker();
            itemLinks = new ItemLinkService(proxy, config.itemLinks);
            chat = new VelocityChatService(
                    proxy, router, transport, conversations, itemLinks, formatting);
            networkMessages = new NetworkMessageService(transport);

            commandRegistrar = new CommandRegistrar(this, proxy);
            commandRegistrar.register(
                    proxy, channels, states, repository, chat, access, networkMessages, spies,
                    config, this::reloadConfig);

            registerRuntimeListener(itemLinks);
            registerRuntimeListener(new ChatListener(chat, states));
            registerRuntimeListener(new PlayerLifecycleListener(states, conversations, spies, chat));
            registerRuntimeListener(new VanillaCommandBypassListener(proxy, chat));
            proxy.getAllPlayers().forEach(player -> states.load(player.getUniqueId()));

            infrastructure = InfrastructureSnapshot.from(config);
            ready = true;
            logger.info("tkChat started with {} channels, {} storage, and {} transport",
                    channels.all().size(), repository.getClass().getSimpleName(), transport.getClass().getSimpleName());
        } catch (Exception error) {
            cleanupRuntime();
            logger.error("tkChat could not start. Any partially initialized runtime was rolled back.", error);
        }
    }

    private void registerRuntimeListener(Object listener) {
        registeredRuntimeListeners.add(listener);
        proxy.getEventManager().register(this, listener);
    }

    private CompletionStage<ConfigReloadResult> reloadConfig() {
        if (!ready) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("tkChat is not ready"));
        }
        if (!reloading.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A tkChat config reload is already running"));
        }
        try {
            AppConfig config = new ConfigLoader().load(dataDirectory);
            validateSignedVelocity(config);
            ChannelRegistry replacementChannels = new ChannelRegistry(config.channelDefinitions());
            ChatRouter replacementRouter = new ChatRouter(
                    replacementChannels, access, repository, config.policy(), Clock.systemUTC(),
                    Permissions.BYPASS_CHANNEL_RESTRICTIONS);
            List<String> restartRequired = infrastructure.changed(config);

            commandRegistrar.register(
                    proxy, replacementChannels, states, repository, chat, access,
                    networkMessages, spies, config, this::reloadConfig);
            access.reconfigure(config.libertyBans.failClosed);
            itemLinks.reconfigure(config.itemLinks);
            delivery.reconfigure(
                    replacementChannels, config.formats, config.mentions,
                    config.itemLinks, config.chat.clearLines);
            chat.reconfigure(replacementRouter);
            channels = replacementChannels;

            CompletionStage<Void> repairs = states.reconfigure(
                    replacementChannels, config.defaultChannel);
            ConfigReloadResult result = new ConfigReloadResult(restartRequired);
            return repairs.handle((ignored, repairError) -> {
                if (repairError != null) {
                    logger.error("tkChat reloaded, but some active channels could not be repaired", repairError);
                }
                logger.info("tkChat config reloaded with {} channels{}",
                        replacementChannels.all().size(),
                        restartRequired.isEmpty()
                                ? ""
                                : "; restart required for " + String.join(", ", restartRequired));
                return result;
            }).whenComplete((ignored, error) -> reloading.set(false));
        } catch (Exception error) {
            reloading.set(false);
            logger.error("tkChat config reload failed; the previous config remains active", error);
            return CompletableFuture.failedFuture(error);
        }
    }

    private void validateSignedVelocity(AppConfig config) {
        if (config.requireSignedVelocity && !proxy.getPluginManager().isLoaded("signedvelocity")) {
            throw new IllegalStateException(
                    "SignedVelocity is required by config but is not installed on this proxy");
        }
    }

    private SocialRepository createRepository(AppConfig config) throws Exception {
        if (!config.mongodb.enabled) {
            logger.warn("MongoDB is disabled; groups and settings will be lost when Velocity restarts.");
            return new InMemorySocialRepository(config.defaultChannel);
        }
        try {
            return new MongoSocialRepository(config.mongodb, config.defaultChannel);
        } catch (Exception error) {
            if (!config.mongodb.fallbackToMemory) {
                throw error;
            }
            logger.error("MongoDB is unavailable; using volatile in-memory storage as configured.", error);
            return new InMemorySocialRepository(config.defaultChannel);
        }
    }

    private MessageTransport createTransport(AppConfig config) {
        if (!config.rabbitmq.enabled) {
            logger.warn("RabbitMQ is disabled; chat is limited to this Velocity process.");
            return new LocalMessageTransport();
        }
        RabbitMessageTransport rabbit = new RabbitMessageTransport(config.rabbitmq, config.instanceId, executor);
        if (!config.rabbitmq.fallbackToLocal) {
            return rabbit;
        }
        return new FallbackMessageTransport(rabbit, logger);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        cleanupRuntime();
    }

    private void cleanupRuntime() {
        ready = false;
        reloading.set(false);
        for (Object listener : List.copyOf(registeredRuntimeListeners)) {
            try {
                proxy.getEventManager().unregisterListener(this, listener);
            } catch (RuntimeException error) {
                logger.warn("Could not unregister a tkChat runtime listener", error);
            }
        }
        registeredRuntimeListeners.clear();
        if (commandRegistrar != null) {
            try {
                commandRegistrar.unregister();
            } catch (RuntimeException error) {
                logger.warn("Could not unregister tkChat commands", error);
            }
            commandRegistrar = null;
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (RuntimeException error) {
                logger.warn("Could not close the tkChat transport", error);
            }
            transport = null;
        }
        if (itemLinks != null) {
            try {
                itemLinks.close();
            } catch (RuntimeException error) {
                logger.warn("Could not close the tkChat item-link bridge", error);
            }
            itemLinks = null;
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (RuntimeException error) {
                logger.warn("Could not close tkChat storage", error);
            }
            repository = null;
        }
    }

    public boolean isReady() {
        return ready;
    }
}
