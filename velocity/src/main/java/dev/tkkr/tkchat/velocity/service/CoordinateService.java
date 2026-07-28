package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.Coordinates;
import dev.tkkr.tkchat.velocity.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CoordinateService implements AutoCloseable {
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("tkchat", "coords");
    private static final int PROTOCOL_VERSION = 1;
    private static final int REQUEST = 0;
    private static final int RESPONSE = 1;

    private final ProxyServer proxy;
    private volatile AppConfig.Coordinates config;
    private final Map<UUID, PendingRequest> pending = new ConcurrentHashMap<>();

    public CoordinateService(ProxyServer proxy, AppConfig.Coordinates config) {
        this.proxy = proxy;
        this.config = config;
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    public void reconfigure(AppConfig.Coordinates config) {
        this.config = config;
    }

    public CompletionStage<ApprovedMessage> enrich(Player sender, ApprovedMessage message) {
        AppConfig.Coordinates current = config;
        if (!current.enabled
                || current.placeholders.stream().noneMatch(message.content()::contains)) {
            return CompletableFuture.completedFuture(message);
        }
        ServerConnection backend = sender.getCurrentServer().orElse(null);
        if (backend == null) {
            return CompletableFuture.failedFuture(new CoordinateException(
                    "You must be connected to a backend server to share coordinates."));
        }

        UUID requestId = UUID.randomUUID();
        CompletableFuture<Coordinates> response = new CompletableFuture<>();
        PendingRequest request = new PendingRequest(
                sender.getUniqueId(), backend.getServerInfo().getName(), response);
        pending.put(requestId, request);
        if (!backend.sendPluginMessage(CHANNEL, request(requestId))) {
            pending.remove(requestId);
            return CompletableFuture.failedFuture(new CoordinateException(
                    "This backend does not have the tkChat coordinate bridge."));
        }

        CompletableFuture.delayedExecutor(current.responseTimeoutMillis, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    PendingRequest timedOut = pending.remove(requestId);
                    if (timedOut != null) {
                        timedOut.response.completeExceptionally(new CoordinateException(
                                "The backend did not answer the coordinate request."));
                    }
                });
        return response.thenApply(message::withCoordinates);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }
        try {
            CoordinateResponse response = decodeResponse(event.getData());
            PendingRequest request = pending.get(response.requestId());
            if (request == null
                    || !request.playerId.equals(source.getPlayer().getUniqueId())
                    || !request.serverId.equals(source.getServerInfo().getName())) {
                return;
            }
            if (pending.remove(response.requestId(), request)) {
                request.response.complete(response.coordinates());
            }
        } catch (IOException | RuntimeException error) {
            // Malformed or stale backend responses are ignored and the request will time out.
        }
    }

    @Override
    public void close() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        pending.values().forEach(request -> request.response.completeExceptionally(
                new CoordinateException("tkChat is shutting down.")));
        pending.clear();
    }

    static CoordinateResponse decodeResponse(byte[] data) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readUnsignedByte() != PROTOCOL_VERSION
                    || input.readUnsignedByte() != RESPONSE) {
                throw new IOException("Unsupported coordinate response");
            }
            UUID requestId = new UUID(input.readLong(), input.readLong());
            Coordinates coordinates = new Coordinates(
                    input.readInt(), input.readInt(), input.readInt(), input.readUTF());
            return new CoordinateResponse(requestId, coordinates);
        }
    }

    static byte[] request(UUID requestId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(18);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(PROTOCOL_VERSION);
                output.writeByte(REQUEST);
                output.writeLong(requestId.getMostSignificantBits());
                output.writeLong(requestId.getLeastSignificantBits());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CompletionException(impossible);
        }
    }

    record CoordinateResponse(UUID requestId, Coordinates coordinates) {
    }

    private record PendingRequest(
            UUID playerId,
            String serverId,
            CompletableFuture<Coordinates> response
    ) {
    }

    public static final class CoordinateException extends RuntimeException {
        public CoordinateException(String message) {
            super(message);
        }
    }
}
