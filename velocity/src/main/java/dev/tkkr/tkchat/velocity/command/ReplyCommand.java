package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

public final class ReplyCommand implements SimpleCommand {
    private final VelocityChatService chat;

    public ReplyCommand(VelocityChatService chat) {
        this.chat = chat;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players can reply."));
            return;
        }
        if (invocation.arguments().length == 0) {
            player.sendMessage(VelocityChatService.error("Usage: /reply <message>"));
            return;
        }
        chat.reply(player, String.join(" ", invocation.arguments()));
    }
}
