package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.service.SocialRepository;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;

public final class DirectMessagesToggleCommand implements SimpleCommand {
    private final SocialRepository repository;
    private final String defaultChannel;

    public DirectMessagesToggleCommand(SocialRepository repository, String defaultChannel) {
        this.repository = repository;
        this.defaultChannel = defaultChannel;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(VelocityChatService.error("Only players have DM settings."));
            return;
        }
        repository.settings(player.getUniqueId(), defaultChannel).thenCompose(settings ->
                repository.setDirectMessagesEnabled(player.getUniqueId(), !settings.directMessagesEnabled())
                        .thenApply(ignored -> !settings.directMessagesEnabled())
        ).whenComplete((enabled, error) -> player.sendMessage(error == null
                ? VelocityChatService.success("Direct messages are now " + (enabled ? "enabled." : "disabled."))
                : VelocityChatService.error("DM settings could not be changed.")));
    }
}
