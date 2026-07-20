package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

public final class BroadcastCommand implements SimpleCommand {
    private final NetworkMessageService messages;
    private final int maxMessageLength;

    public BroadcastCommand(NetworkMessageService messages, AppConfig.Chat chat) {
        this.messages = messages;
        this.maxMessageLength = chat.maxMessageLength;
    }

    @Override
    public void execute(Invocation invocation) {
        String content = String.join(" ", invocation.arguments());
        if (content.isBlank() || content.length() > maxMessageLength) {
            invocation.source().sendMessage(VelocityChatService.error(
                    "Usage: /broadcast <message> (maximum " + maxMessageLength + " characters)"));
            return;
        }
        messages.broadcast(invocation.source(), content).exceptionally(error -> {
            invocation.source().sendMessage(VelocityChatService.error("The broadcast could not be delivered."));
            return null;
        });
    }
}
