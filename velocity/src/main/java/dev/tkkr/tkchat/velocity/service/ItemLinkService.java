package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ItemLink;
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

public final class ItemLinkService implements AutoCloseable {
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("tkchat", "item");
    private static final int PROTOCOL_VERSION = 1;
    private static final int REQUEST = 0;
    private static final int RESPONSE = 1;

    private final ProxyServer proxy;
    private volatile AppConfig.ItemLinks config;
    private final Map<UUID, PendingRequest> pending = new ConcurrentHashMap<>();

    public ItemLinkService(ProxyServer proxy, AppConfig.ItemLinks config) {
        this.proxy = proxy;
        this.config = config;
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    public void reconfigure(AppConfig.ItemLinks config) {
        this.config = config;
    }

    public CompletionStage<ApprovedMessage> enrich(Player sender, ApprovedMessage message) {
        if (!config.enabled || config.placeholders.stream().noneMatch(message.content()::contains)) {
            return CompletableFuture.completedFuture(message);
        }
        ServerConnection backend = sender.getCurrentServer().orElse(null);
        if (backend == null) {
            return CompletableFuture.failedFuture(
                    new ItemLinkException("You must be connected to a backend server to link an item."));
        }

        UUID requestId = UUID.randomUUID();
        CompletableFuture<ItemLink> response = new CompletableFuture<>();
        pending.put(requestId, new PendingRequest(sender.getUniqueId(), response));
        if (!backend.sendPluginMessage(CHANNEL, request(requestId))) {
            pending.remove(requestId);
            return CompletableFuture.failedFuture(
                    new ItemLinkException("This backend does not have the tkChat item-link bridge."));
        }

        CompletableFuture.delayedExecutor(config.responseTimeoutMillis, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    PendingRequest timedOut = pending.remove(requestId);
                    if (timedOut != null) {
                        timedOut.response.completeExceptionally(
                                new ItemLinkException("The backend did not answer the item-link request."));
                    }
                });
        return response.thenApply(message::withItemLink);
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
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            if (input.readUnsignedByte() != PROTOCOL_VERSION || input.readUnsignedByte() != RESPONSE) {
                return;
            }
            UUID requestId = new UUID(input.readLong(), input.readLong());
            PendingRequest request = pending.get(requestId);
            if (request == null || !request.playerId.equals(source.getPlayer().getUniqueId())) {
                return;
            }
            if (!input.readBoolean()) {
                if (pending.remove(requestId, request)) {
                    request.response.completeExceptionally(
                            new ItemLinkException("Hold an item in your main hand before using <item>."));
                }
                return;
            }
            ItemLink item = new ItemLink(input.readUTF(), input.readInt(), input.readUTF());
            if (pending.remove(requestId, request)) {
                request.response.complete(item);
            }
        } catch (IOException | RuntimeException error) {
            // Malformed or stale backend responses are ignored and the request will time out.
        }
    }

    @Override
    public void close() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        pending.values().forEach(request -> request.response.completeExceptionally(
                new ItemLinkException("tkChat is shutting down.")));
        pending.clear();
    }

    private static byte[] request(UUID requestId) {
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

    private record PendingRequest(UUID playerId, CompletableFuture<ItemLink> response) {
    }

    public static final class ItemLinkException extends RuntimeException {
        public ItemLinkException(String message) {
            super(message);
        }
    }
}
