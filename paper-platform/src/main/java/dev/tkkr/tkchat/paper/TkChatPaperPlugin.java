package dev.tkkr.tkchat.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class TkChatPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private static final String ITEM_CHANNEL = "tkchat:item";
    private static final String COORDINATE_CHANNEL = "tkchat:coords";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, ITEM_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ITEM_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(
                this, COORDINATE_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, COORDINATE_CHANNEL);
        if (getServer().getPluginManager().getPlugin("SignedVelocity") == null) {
            getSLF4JLogger().warn("SignedVelocity is not installed. Proxy-side cancellation may race backend chat.");
        }
        getSLF4JLogger().info("tkChat Paper bridge enabled");
    }

    @Override
    public void onPluginMessageReceived(
            String channel,
            Player player,
            byte[] data
    ) {
        if (!channel.equals(ITEM_CHANNEL) && !channel.equals(COORDINATE_CHANNEL)) {
            return;
        }
        try {
            UUID requestId = requestId(data);
            if (channel.equals(COORDINATE_CHANNEL)) {
                sendCoordinates(player, requestId);
                return;
            }
            var item = player.getInventory().getItemInMainHand();
            boolean present = !item.getType().isAir();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(1);
                output.writeByte(1);
                output.writeLong(requestId.getMostSignificantBits());
                output.writeLong(requestId.getLeastSignificantBits());
                output.writeBoolean(present);
                if (present) {
                    output.writeUTF(item.getType().getKey().toString());
                    output.writeInt(item.getAmount());
                    output.writeUTF(PlainTextComponentSerializer.plainText()
                            .serialize(item.displayName()));
                }
            }
            player.sendPluginMessage(this, ITEM_CHANNEL, bytes.toByteArray());
        } catch (IOException malformedRequest) {
            getSLF4JLogger().warn("Ignored a malformed tkChat placeholder request");
        }
    }

    private void sendCoordinates(Player player, UUID requestId) throws IOException {
        var location = player.getLocation();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeByte(1);
            output.writeLong(requestId.getMostSignificantBits());
            output.writeLong(requestId.getLeastSignificantBits());
            output.writeInt(location.getBlockX());
            output.writeInt(location.getBlockY());
            output.writeInt(location.getBlockZ());
            output.writeUTF(player.getWorld().getKey().toString());
        }
        player.sendPluginMessage(this, COORDINATE_CHANNEL, bytes.toByteArray());
    }

    private static UUID requestId(byte[] data) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                throw new IOException("Unsupported placeholder request");
            }
            return new UUID(input.readLong(), input.readLong());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBackendChat(AsyncChatEvent event) {
        // Velocity has already validated, enriched, and delivered the server-authored tkChat copy.
        // Cancelling here prevents Paper from also sending the original player-chat packet. This
        // remains a safe fallback when SignedVelocity cannot recognize Paper's current call stack.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBackendJoinMessage(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBackendQuitMessage(PlayerQuitEvent event) {
        event.quitMessage(null);
    }

}
