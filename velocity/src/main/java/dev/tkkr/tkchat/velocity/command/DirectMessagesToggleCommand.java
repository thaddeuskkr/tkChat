package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

public final class DirectMessagesToggleCommand implements SimpleCommand {
    private final PlayerStateService states;

    public DirectMessagesToggleCommand(PlayerStateService states) {
        this.states = states;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players have DM settings."));
            return;
        }
        states.toggleDirectMessages(player.getUniqueId())
                .whenComplete((enabled, error) -> player.sendMessage(error == null
                ? VelocityChatService.success("Direct messages are now " + (enabled ? "enabled." : "disabled."))
                : VelocityChatService.error("DM settings could not be changed.")));
    }
}
