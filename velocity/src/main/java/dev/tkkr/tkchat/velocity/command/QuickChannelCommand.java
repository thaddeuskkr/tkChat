package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

public final class QuickChannelCommand implements SimpleCommand {
    private final ChannelDefinition channel;
    private final ChannelCommand selector;
    private final VelocityChatService chat;

    public QuickChannelCommand(
            ChannelDefinition channel,
            ChannelCommand selector,
            VelocityChatService chat
    ) {
        this.channel = channel;
        this.selector = selector;
        this.chat = chat;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error(
                    "Only players can use quick channel commands."));
            return;
        }
        if (invocation.arguments().length == 0) {
            selector.select(player, channel);
            return;
        }
        String content = String.join(" ", invocation.arguments());
        chat.channel(player, channel.id(), content);
    }
}
