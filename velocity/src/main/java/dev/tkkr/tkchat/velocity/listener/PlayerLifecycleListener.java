package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Owns online state generations so late database reads cannot survive a disconnect or reconnect. */
public final class PlayerLifecycleListener {
    private static final long[] RETRY_DELAYS_SECONDS = {1, 5, 15, 30};

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PlayerStateService states;
    private final ConversationTracker conversations;
    private final SocialSpyService spies;
    private final VelocityChatService chat;
    private final ResponseService responses;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final ConcurrentMap<UUID, Long> generations = new ConcurrentHashMap<>();

    public PlayerLifecycleListener(
            Object plugin,
            ProxyServer proxy,
            Logger logger,
            PlayerStateService states,
            ConversationTracker conversations,
            SocialSpyService spies,
            VelocityChatService chat,
            ResponseService responses
    ) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.states = states;
        this.conversations = conversations;
        this.spies = spies;
        this.chat = chat;
        this.responses = responses;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        long generation = activate(player);
        var completion = states.load(player.getUniqueId()).handle((ignored, error) -> {
            if (error != null) {
                logger.warn("Could not load chat state for {}; retrying in the background: {}",
                        player.getUsername(), unwrap(error).toString());
                player.sendMessage(responses.message(ResponseKey.FEEDBACK_STATE_LOAD_FAILED));
                scheduleRetry(player.getUniqueId(), generation, 0);
            }
            return (Void) null;
        }).toCompletableFuture();
        return EventTask.resumeWhenComplete(completion);
    }

    /** Handles the unusual case where tkChat starts while players are already connected. */
    public void loadExisting(Player player) {
        long generation = activate(player);
        states.load(player.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.warn("Could not load chat state for {}; retrying in the background: {}",
                        player.getUsername(), unwrap(error).toString());
                scheduleRetry(player.getUniqueId(), generation, 0);
            }
        });
    }

    private long activate(Player player) {
        long generation = nextGeneration.incrementAndGet();
        generations.put(player.getUniqueId(), generation);
        states.activate(player.getUniqueId());
        return generation;
    }

    private void scheduleRetry(UUID playerId, long generation, int attempt) {
        long baseDelayMillis = RETRY_DELAYS_SECONDS[
                Math.min(attempt, RETRY_DELAYS_SECONDS.length - 1)] * 1_000L;
        long jitterBound = Math.min(5_000L, Math.max(250L, baseDelayMillis / 4));
        long delayMillis = baseDelayMillis + ThreadLocalRandom.current().nextLong(jitterBound + 1);
        proxy.getScheduler().buildTask(plugin,
                        () -> retry(playerId, generation, attempt + 1))
                .delay(Duration.ofMillis(delayMillis))
                .schedule();
    }

    private void retry(UUID playerId, long generation, int attempt) {
        if (!generationMatches(playerId, generation)) {
            return;
        }
        states.load(playerId).whenComplete((ignored, error) -> {
            if (!generationMatches(playerId, generation)) {
                return;
            }
            if (error == null && states.isLoaded(playerId)) {
                proxy.getPlayer(playerId).ifPresent(player -> player.sendMessage(
                        responses.message(ResponseKey.FEEDBACK_STATE_LOAD_RECOVERED)));
                return;
            }
            logger.debug("Chat state retry {} failed for {}: {}",
                    attempt, playerId, error == null ? "stale load" : unwrap(error).toString());
            scheduleRetry(playerId, generation, attempt);
        });
    }

    private boolean generationMatches(UUID playerId, long generation) {
        return generations.getOrDefault(playerId, -1L) == generation
                && proxy.getPlayer(playerId).isPresent();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        generations.remove(playerId);
        states.remove(playerId);
        conversations.remove(playerId);
        spies.remove(playerId);
        chat.remove(playerId);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
