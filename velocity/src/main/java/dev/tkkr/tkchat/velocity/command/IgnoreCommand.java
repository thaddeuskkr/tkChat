package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

import java.util.List;

public final class IgnoreCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final PlayerStateService states;
    private final ResponseService responses;

    public IgnoreCommand(ProxyServer proxy, PlayerStateService states, ResponseService responses) {
        this.proxy = proxy;
        this.states = states;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player) || invocation.arguments().length != 1) {
            invocation.source().sendMessage(responses.message(ResponseKey.IGNORE_USAGE));
            return;
        }
        if (!states.isLoaded(player.getUniqueId())) {
            player.sendMessage(responses.denial(dev.tkkr.tkchat.core.model.DenialReason.NOT_READY));
            return;
        }
        Player target = proxy.getPlayer(invocation.arguments()[0]).orElse(null);
        if (target == null || target.equals(player)) {
            player.sendMessage(responses.message(ResponseKey.IGNORE_UNAVAILABLE));
            return;
        }
        boolean ignored = !states.isIgnoring(player.getUniqueId(), target.getUniqueId());
        states.setIgnoring(player.getUniqueId(), target.getUniqueId(), ignored).whenComplete((saved, error) -> {
            if (error != null) {
                player.sendMessage(responses.message(ResponseKey.IGNORE_FAILED));
            } else {
                player.sendMessage(responses.message(
                        saved ? ResponseKey.IGNORE_ENABLED : ResponseKey.IGNORE_DISABLED,
                        ResponseService.text("player", target.getUsername())));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
        return proxy.getAllPlayers().stream().map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(prefix)).toList();
    }
}
