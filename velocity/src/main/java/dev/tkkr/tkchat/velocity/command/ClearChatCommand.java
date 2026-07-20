package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.List;
import java.util.Locale;

public final class ClearChatCommand implements SimpleCommand {
    private final NetworkMessageService messages;
    private final ChannelRegistry channels;
    private final ResponseService responses;

    public ClearChatCommand(
            NetworkMessageService messages,
            ChannelRegistry channels,
            ResponseService responses
    ) {
        this.messages = messages;
        this.channels = channels;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 1) {
            invocation.source().sendMessage(responses.message(ResponseKey.CLEAR_USAGE));
            return;
        }

        ChannelDefinition channel = channels.find(invocation.arguments()[0]).orElse(null);
        if (channel == null) {
            invocation.source().sendMessage(responses.message(ResponseKey.CLEAR_UNKNOWN));
            return;
        }
        if (channel.scope() == ChannelScope.SERVER
                && (!(invocation.source() instanceof Player player)
                || player.getCurrentServer().isEmpty())) {
            invocation.source().sendMessage(responses.message(ResponseKey.CLEAR_PLAYER_REQUIRED));
            return;
        }

        messages.clearChat(invocation.source(), channel).exceptionally(error -> {
            invocation.source().sendMessage(responses.message(ResponseKey.CLEAR_FAILED));
            return null;
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length > 1) {
            return List.of();
        }
        String prefix = invocation.arguments().length == 0
                ? ""
                : invocation.arguments()[0].toLowerCase(Locale.ROOT);
        return channels.all().stream()
                .map(ChannelDefinition::id)
                .filter(id -> id.startsWith(prefix))
                .sorted()
                .toList();
    }
}
