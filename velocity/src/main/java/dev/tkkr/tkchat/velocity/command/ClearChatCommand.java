package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.service.NetworkMessageService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

import java.util.List;
import java.util.Locale;

public final class ClearChatCommand implements SimpleCommand {
    private final NetworkMessageService messages;
    private final ChannelRegistry channels;

    public ClearChatCommand(NetworkMessageService messages, ChannelRegistry channels) {
        this.messages = messages;
        this.channels = channels;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 1) {
            invocation.source().sendMessage(VelocityChatService.error(
                    "Usage: /clearchat <channel>"));
            return;
        }

        ChannelDefinition channel = channels.find(invocation.arguments()[0]).orElse(null);
        if (channel == null) {
            invocation.source().sendMessage(VelocityChatService.error(
                    "Unknown channel. Use /clearchat <channel>."));
            return;
        }
        if (channel.scope() == ChannelScope.SERVER
                && (!(invocation.source() instanceof Player player)
                || player.getCurrentServer().isEmpty())) {
            invocation.source().sendMessage(VelocityChatService.error(
                    "A server-scoped channel can only be cleared by a connected player."));
            return;
        }

        messages.clearChat(invocation.source(), channel).exceptionally(error -> {
            invocation.source().sendMessage(VelocityChatService.error("Chat could not be cleared."));
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
