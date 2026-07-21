package dev.tkkr.tkchat.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TkChatPaperPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+");
    private static final String ITEM_CHANNEL = "tkchat:item";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private String localFormat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        localFormat = getConfig().getString("local-format",
                "<dark_gray>[</dark_gray><gray>Local</gray><dark_gray>]</dark_gray> <name><dark_gray>: </dark_gray><message>");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, ITEM_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, ITEM_CHANNEL);
        if (getServer().getPluginManager().getPlugin("SignedVelocity") == null) {
            getSLF4JLogger().warn("SignedVelocity is not installed. Proxy-side cancellation may race backend chat.");
        }
        getSLF4JLogger().info("tkChat Paper bridge enabled");
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] data
    ) {
        if (!channel.equals(ITEM_CHANNEL)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                return;
            }
            UUID requestId = new UUID(input.readLong(), input.readLong());
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
                            .serialize(item.effectiveName()));
                }
            }
            player.sendPluginMessage(this, ITEM_CHANNEL, bytes.toByteArray());
        } catch (IOException malformedRequest) {
            getSLF4JLogger().warn("Ignored a malformed tkChat item-link request");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLocalChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component linkedMessage = linkify(message);
            TagResolver placeholders = TagResolver.builder()
                    .resolver(Placeholder.component("name", sourceDisplayName))
                    .resolver(Placeholder.component("message", linkedMessage))
                    .build();
            try {
                return miniMessage.deserialize(localFormat, placeholders);
            } catch (RuntimeException malformedFormat) {
                return Component.text("[Local] ", NamedTextColor.DARK_GRAY)
                        .append(sourceDisplayName)
                        .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                        .append(linkedMessage);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBackendJoinMessage(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressBackendQuitMessage(PlayerQuitEvent event) {
        event.quitMessage(null);
    }

    private static Component linkify(Component input) {
        return input.replaceText(TextReplacementConfig.builder()
                .match(URL)
                .replacement((match, builder) -> {
                    String visible = match.group();
                    String url = visible.regionMatches(true, 0, "www.", 0, 4)
                            ? "https://" + visible
                            : visible;
                    return builder.color(NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Open link")));
                })
                .build());
    }
}
