package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

public final class MeCommand implements SimpleCommand {
    private final VelocityChatService chat;
    private final PlayerStateService states;
    private final ResponseService responses;
    private final int maxMessageLength;

    public MeCommand(
            VelocityChatService chat,
            PlayerStateService states,
            ResponseService responses,
            AppConfig.Chat config
    ) {
        this.chat = chat;
        this.states = states;
        this.responses = responses;
        this.maxMessageLength = config.maxMessageLength;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.ME_PLAYER_ONLY));
            return;
        }
        String content = String.join(" ", invocation.arguments());
        if (content.isBlank() || content.length() > maxMessageLength) {
            player.sendMessage(responses.message(
                    ResponseKey.ME_USAGE,
                    ResponseService.text("max_length", maxMessageLength)));
            return;
        }
        chat.action(player, states.activeChannel(player.getUniqueId()), content);
    }
}
