package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

import java.util.Arrays;
import java.util.List;

public final class MessageCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final VelocityChatService chat;
    private final ResponseService responses;

    public MessageCommand(ProxyServer proxy, VelocityChatService chat, ResponseService responses) {
        this.proxy = proxy;
        this.chat = chat;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player sender)) {
            invocation.source().sendMessage(responses.message(ResponseKey.DIRECT_PLAYER_ONLY));
            return;
        }
        String[] arguments = invocation.arguments();
        if (arguments.length < 2) {
            sender.sendMessage(responses.message(ResponseKey.DIRECT_USAGE));
            return;
        }
        Player recipient = proxy.getPlayer(arguments[0]).orElse(null);
        if (recipient == null) {
            sender.sendMessage(responses.message(ResponseKey.DIRECT_OFFLINE));
            return;
        }
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(responses.message(ResponseKey.DIRECT_SELF));
            return;
        }
        chat.direct(sender, recipient, String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length > 1) {
            return List.of();
        }
        String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase();
        return proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
