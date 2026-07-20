package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.ConfigReloadResult;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class ReloadCommand implements SimpleCommand {
    private final Supplier<CompletionStage<ConfigReloadResult>> reload;

    public ReloadCommand(Supplier<CompletionStage<ConfigReloadResult>> reload) {
        this.reload = reload;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 0) {
            invocation.source().sendMessage(VelocityChatService.error("Usage: /tkchat reload"));
            return;
        }
        reload.get().whenComplete((result, error) -> {
            if (error != null) {
                invocation.source().sendMessage(VelocityChatService.error(
                        "tkChat config reload failed. Check the proxy console."));
                return;
            }
            invocation.source().sendMessage(VelocityChatService.success("tkChat config reloaded."));
            if (!result.restartRequired().isEmpty()) {
                invocation.source().sendMessage(Component.text(
                        "Restart Velocity to apply changes to: "
                                + String.join(", ", result.restartRequired()) + ".",
                        NamedTextColor.YELLOW));
            }
        });
    }
}
