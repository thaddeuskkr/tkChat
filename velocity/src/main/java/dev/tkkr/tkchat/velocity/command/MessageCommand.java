package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

import java.util.Arrays;
import java.util.List;

public final class MessageCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final VelocityChatService chat;

    public MessageCommand(ProxyServer proxy, VelocityChatService chat) {
        this.proxy = proxy;
        this.chat = chat;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player sender)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players can send direct messages."));
            return;
        }
        String[] arguments = invocation.arguments();
        if (arguments.length < 2) {
            sender.sendMessage(VelocityChatService.error("Usage: /msg <player> <message>"));
            return;
        }
        Player recipient = proxy.getPlayer(arguments[0]).orElse(null);
        if (recipient == null) {
            sender.sendMessage(VelocityChatService.error("That player is not online."));
            return;
        }
        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(VelocityChatService.error("You cannot message yourself."));
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
