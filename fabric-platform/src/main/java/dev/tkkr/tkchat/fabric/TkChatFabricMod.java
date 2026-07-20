package dev.tkkr.tkchat.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
        LOGGER.info("tkChat Fabric bridge enabled");
    }
}
