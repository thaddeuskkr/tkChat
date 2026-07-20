package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

public final class ReplyCommand implements SimpleCommand {
    private final VelocityChatService chat;
    private final ResponseService responses;

    public ReplyCommand(VelocityChatService chat, ResponseService responses) {
        this.chat = chat;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.REPLY_PLAYER_ONLY));
            return;
        }
        if (invocation.arguments().length == 0) {
            player.sendMessage(responses.message(ResponseKey.REPLY_USAGE));
            return;
        }
        chat.reply(player, String.join(" ", invocation.arguments()));
    }
}
