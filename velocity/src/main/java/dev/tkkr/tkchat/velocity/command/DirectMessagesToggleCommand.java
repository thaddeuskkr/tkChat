package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

public final class DirectMessagesToggleCommand implements SimpleCommand {
    private final PlayerStateService states;
    private final ResponseService responses;

    public DirectMessagesToggleCommand(PlayerStateService states, ResponseService responses) {
        this.states = states;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.DM_TOGGLE_PLAYER_ONLY));
            return;
        }
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        states.toggleDirectMessages(player.getUniqueId())
                .whenComplete((enabled, error) -> player.sendMessage(error == null
                        ? responses.message(enabled
                        ? ResponseKey.DM_TOGGLE_ENABLED
                        : ResponseKey.DM_TOGGLE_DISABLED)
                        : responses.message(ResponseKey.DM_TOGGLE_FAILED)));
    }
}
