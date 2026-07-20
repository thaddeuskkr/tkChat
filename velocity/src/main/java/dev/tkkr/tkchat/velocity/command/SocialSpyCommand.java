package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;

public final class SocialSpyCommand implements SimpleCommand {
    private final SocialSpyService spies;

    public SocialSpyCommand(SocialSpyService spies) {
        this.spies = spies;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players can use social spy."));
            return;
        }
        if (invocation.arguments().length > 1) {
            player.sendMessage(VelocityChatService.error("Usage: /socialspy [on|off]"));
            return;
        }
        boolean enabled;
        if (invocation.arguments().length == 0) {
            enabled = spies.toggle(player.getUniqueId());
        } else if (invocation.arguments()[0].equalsIgnoreCase("on")) {
            enabled = spies.set(player.getUniqueId(), true);
        } else if (invocation.arguments()[0].equalsIgnoreCase("off")) {
            enabled = spies.set(player.getUniqueId(), false);
        } else {
            player.sendMessage(VelocityChatService.error("Usage: /socialspy [on|off]"));
            return;
        }
        player.sendMessage(VelocityChatService.success(
                "Social spy " + (enabled ? "enabled" : "disabled") + "."));
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        return invocation.arguments().length <= 1 ? java.util.List.of("on", "off") : java.util.List.of();
    }
}
