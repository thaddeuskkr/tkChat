package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class VanillaCommandBypassListener {
    private final ProxyServer proxy;
    private final VelocityChatService chat;

    public VanillaCommandBypassListener(ProxyServer proxy, VelocityChatService chat) {
        this.proxy = proxy;
        this.chat = chat;
    }

    @Subscribe
    public EventTask onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player sender)) {
            return null;
        }
        String[] parts = event.getCommand().trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!command.equals("minecraft:msg")
                && !command.equals("minecraft:tell")
                && !command.equals("minecraft:w")) {
            return null;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        if (!sender.hasPermission(Permissions.command("message"))) {
            sender.sendMessage(VelocityChatService.error(
                    "You do not have permission to use this command."));
            return null;
        }
        if (parts.length < 3) {
            sender.sendMessage(VelocityChatService.error("Usage: /msg <player> <message>"));
            return null;
        }
        Player recipient = proxy.getPlayer(parts[1]).orElse(null);
        if (recipient == null || recipient.equals(sender)) {
            sender.sendMessage(VelocityChatService.error("That player is not available."));
            return null;
        }
        CompletableFuture<Void> future = chat.direct(sender, recipient,
                String.join(" ", Arrays.copyOfRange(parts, 2, parts.length))).toCompletableFuture();
        return EventTask.resumeWhenComplete(future);
    }
}
