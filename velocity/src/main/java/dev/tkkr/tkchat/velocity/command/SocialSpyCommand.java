package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;

public final class SocialSpyCommand implements SimpleCommand {
    private final SocialSpyService spies;
    private final ResponseService responses;

    public SocialSpyCommand(SocialSpyService spies, ResponseService responses) {
        this.spies = spies;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(responses.message(ResponseKey.SOCIAL_SPY_PLAYER_ONLY));
            return;
        }
        if (invocation.arguments().length > 1) {
            player.sendMessage(responses.message(ResponseKey.SOCIAL_SPY_USAGE));
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
            player.sendMessage(responses.message(ResponseKey.SOCIAL_SPY_USAGE));
            return;
        }
        player.sendMessage(responses.message(enabled
                ? ResponseKey.SOCIAL_SPY_ENABLED
                : ResponseKey.SOCIAL_SPY_DISABLED));
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        return invocation.arguments().length <= 1 ? java.util.List.of("on", "off") : java.util.List.of();
    }
}
