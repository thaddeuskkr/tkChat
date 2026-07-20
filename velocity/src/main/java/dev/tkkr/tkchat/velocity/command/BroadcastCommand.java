package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ItemLinkService;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.concurrent.CompletionException;

public final class BroadcastCommand implements SimpleCommand {
    private final NetworkMessageService messages;
    private final int maxMessageLength;
    private final ResponseService responses;

    public BroadcastCommand(NetworkMessageService messages, AppConfig.Chat chat, ResponseService responses) {
        this.messages = messages;
        this.maxMessageLength = chat.maxMessageLength;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        String content = String.join(" ", invocation.arguments());
        if (content.isBlank() || content.length() > maxMessageLength) {
            invocation.source().sendMessage(responses.message(
                    ResponseKey.BROADCAST_USAGE,
                    ResponseService.text("max_length", maxMessageLength)));
            return;
        }
        messages.broadcast(invocation.source(), content).exceptionally(error -> {
            invocation.source().sendMessage(responses.message(
                    unwrap(error) instanceof ItemLinkService.ItemLinkException
                            ? ResponseKey.FEEDBACK_ITEM_LINK_FAILED
                            : ResponseKey.BROADCAST_FAILED));
            return null;
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
