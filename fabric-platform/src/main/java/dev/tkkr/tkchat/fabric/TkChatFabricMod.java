package dev.tkkr.tkchat.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TkChatFabricMod implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("tkChat");

    @Override
    public void onInitializeServer() {
        FabricItemLinkBridge.register();
        ServerMessageDecoratorEvent.EVENT.register(
                ServerMessageDecoratorEvent.STYLING_PHASE,
                (sender, message) -> {
                    if (sender == null) {
                        return message;
                    }
                    return Component.empty()
                            .append(Component.literal("[Local] ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(message.copy());
                }
        );
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
            if (overlay || !(message.getContents() instanceof TranslatableContents translated)) {
                return true;
            }
            String key = translated.getKey();
            return !key.equals("multiplayer.player.joined")
                    && !key.equals("multiplayer.player.joined.renamed")
                    && !key.equals("multiplayer.player.left");
        });
        LOGGER.info("tkChat Fabric bridge enabled");
    }
}
