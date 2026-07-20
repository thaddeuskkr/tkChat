package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.ConfigReloadResult;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class ReloadCommand implements SimpleCommand {
    private final Supplier<CompletionStage<ConfigReloadResult>> reload;
    private final ResponseService responses;

    public ReloadCommand(
            Supplier<CompletionStage<ConfigReloadResult>> reload,
            ResponseService responses
    ) {
        this.reload = reload;
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.arguments().length != 0) {
            invocation.source().sendMessage(responses.message(ResponseKey.RELOAD_USAGE));
            return;
        }
        reload.get().whenComplete((result, error) -> {
            if (error != null) {
                invocation.source().sendMessage(responses.message(ResponseKey.RELOAD_FAILED));
                return;
            }
            invocation.source().sendMessage(responses.message(ResponseKey.RELOAD_SUCCESS));
            if (!result.restartRequired().isEmpty()) {
                invocation.source().sendMessage(responses.message(
                        ResponseKey.RELOAD_RESTART_REQUIRED,
                        ResponseService.text("sections", String.join(", ", result.restartRequired()))));
            }
        });
    }
}
