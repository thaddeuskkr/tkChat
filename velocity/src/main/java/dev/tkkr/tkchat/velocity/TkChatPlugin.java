package dev.tkkr.tkchat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginDescription;
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
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;
import dev.tkkr.tkchat.velocity.storage.MariaDbSocialRepository;
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
        version = BuildInfo.VERSION,
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
            boolean mariadbEnabled,
            boolean mariadbFallbackToMemory,
            String mariadbJdbcUrl,
            String mariadbUsername,
            String mariadbPassword,
            String mariadbTablePrefix,
            int mariadbMaximumPoolSize,
            int mariadbMinimumIdle,
            long mariadbConnectionTimeoutMillis,
            long mariadbValidationTimeoutMillis,
            long mariadbIdleTimeoutMillis,
            long mariadbMaxLifetimeMillis,
            long mariadbConnectTimeoutMillis,
            long mariadbSocketTimeoutMillis,
            int mariadbWorkerThreads,
            int mariadbMaxQueuedOperations,
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
                    config.mariadb.enabled,
                    config.mariadb.fallbackToMemory,
                    config.mariadb.jdbcUrl,
                    config.mariadb.username,
                    config.mariadb.password,
                    config.mariadb.tablePrefix,
                    config.mariadb.maximumPoolSize,
                    config.mariadb.minimumIdle,
                    config.mariadb.connectionTimeoutMillis,
                    config.mariadb.validationTimeoutMillis,
                    config.mariadb.idleTimeoutMillis,
                    config.mariadb.maxLifetimeMillis,
                    config.mariadb.connectTimeoutMillis,
                    config.mariadb.socketTimeoutMillis,
                    config.mariadb.workerThreads,
                    config.mariadb.maxQueuedOperations,
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
            if (mariadbEnabled != next.mariadbEnabled
                    || mariadbFallbackToMemory != next.mariadbFallbackToMemory
                    || !Objects.equals(mariadbJdbcUrl, next.mariadbJdbcUrl)
                    || !Objects.equals(mariadbUsername, next.mariadbUsername)
                    || !Objects.equals(mariadbPassword, next.mariadbPassword)
                    || !Objects.equals(mariadbTablePrefix, next.mariadbTablePrefix)
                    || mariadbMaximumPoolSize != next.mariadbMaximumPoolSize
                    || mariadbMinimumIdle != next.mariadbMinimumIdle
                    || mariadbConnectionTimeoutMillis != next.mariadbConnectionTimeoutMillis
                    || mariadbValidationTimeoutMillis != next.mariadbValidationTimeoutMillis
                    || mariadbIdleTimeoutMillis != next.mariadbIdleTimeoutMillis
                    || mariadbMaxLifetimeMillis != next.mariadbMaxLifetimeMillis
                    || mariadbConnectTimeoutMillis != next.mariadbConnectTimeoutMillis
                    || mariadbSocketTimeoutMillis != next.mariadbSocketTimeoutMillis
                    || mariadbWorkerThreads != next.mariadbWorkerThreads
                    || mariadbMaxQueuedOperations != next.mariadbMaxQueuedOperations) {
                changed.add("mariadb");
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
    private final String version;

    private SocialRepository repository;
    private MessageTransport transport;
    private ItemLinkService itemLinks;
    private ChannelRegistry channels;
    private VelocityAccessController access;
    private PlayerStateService states;
    private VelocityDeliveryService delivery;
    private VelocityChatService chat;
    private NetworkMessageService networkMessages;
    private ResponseService responses;
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
            ExecutorService executor,
            PluginDescription pluginDescription
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.executor = executor;
        this.version = pluginDescription.getVersion().orElseThrow(() ->
                new IllegalStateException("tkChat plugin metadata does not contain a version"));
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            AppConfig config = new ConfigLoader().load(dataDirectory);
            validateSignedVelocity(config);
            channels = new ChannelRegistry(config.channelDefinitions());
            repository = createRepository(config);
            responses = new ResponseService(
                    config.formats.responsePrefix, config.messages);
            access = new VelocityAccessController(config.libertyBans.failClosed);
            states = new PlayerStateService(repository, channels, config.defaultChannel);
            ChatRouter router = new ChatRouter(
                    channels, access, states, config.policy(), Clock.systemUTC(),
                    Permissions.BYPASS_CHANNEL_RESTRICTIONS);
            transport = createTransport(config);
            spies = new SocialSpyService();
            PlayerFormattingService formatting = new PlayerFormattingService();
            delivery = new VelocityDeliveryService(
                    proxy, channels, access, config.formats, config.mentions, config.itemLinks,
                    formatting, states, spies, config.chat.clearLines,
                    java.time.Duration.ofMillis(config.chat.maxDeliveryAgeMillis));
            transport.start(delivery::deliver);

            ConversationTracker conversations = new ConversationTracker();
            itemLinks = new ItemLinkService(proxy, config.itemLinks);
            chat = new VelocityChatService(
                    proxy, router, transport, conversations, itemLinks, formatting,
                    responses,
                    config.chat.maxPendingMessagesPerSender,
                    java.time.Duration.ofMillis(config.chat.maxMessageAgeMillis));
            networkMessages = new NetworkMessageService(transport, itemLinks, formatting);

            commandRegistrar = new CommandRegistrar(this, proxy, version);
            commandRegistrar.register(
                    proxy, channels, states, repository, chat, access, networkMessages, spies,
                    responses, config, this::reloadConfig);

            registerRuntimeListener(itemLinks);
            registerRuntimeListener(new ChatListener(chat, states, responses));
            PlayerLifecycleListener lifecycle = new PlayerLifecycleListener(
                    this, proxy, logger, states, conversations, spies, chat,
                    networkMessages, responses);
            registerRuntimeListener(lifecycle);
            registerRuntimeListener(new VanillaCommandBypassListener(
                    proxy, chat, states, responses));
            proxy.getAllPlayers().forEach(lifecycle::loadExisting);

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
                    replacementChannels, access, states, config.policy(), Clock.systemUTC(),
                    Permissions.BYPASS_CHANNEL_RESTRICTIONS);
            List<String> restartRequired = infrastructure.changed(config);

            commandRegistrar.register(
                    proxy, replacementChannels, states, repository, chat, access,
                    networkMessages, spies, responses, config, this::reloadConfig);
            access.reconfigure(config.libertyBans.failClosed);
            itemLinks.reconfigure(config.itemLinks);
            delivery.reconfigure(
                    replacementChannels, config.formats, config.mentions,
                    config.itemLinks, config.chat.clearLines,
                    java.time.Duration.ofMillis(config.chat.maxDeliveryAgeMillis));
            chat.reconfigure(
                    replacementRouter,
                    config.chat.maxPendingMessagesPerSender,
                    java.time.Duration.ofMillis(config.chat.maxMessageAgeMillis));
            channels = replacementChannels;
            responses.reconfigure(config.formats.responsePrefix, config.messages);

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
        if (!config.mariadb.enabled) {
            logger.warn("MariaDB is disabled; groups and settings will be lost when Velocity restarts.");
            return new InMemorySocialRepository(config.defaultChannel);
        }
        try {
            return new MariaDbSocialRepository(config.mariadb, config.defaultChannel);
        } catch (Exception error) {
            if (!config.mariadb.fallbackToMemory) {
                throw error;
            }
            logger.error("MariaDB is unavailable; using volatile in-memory storage as configured.", error);
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
        responses = null;
    }

    public boolean isReady() {
        return ready;
    }
}
