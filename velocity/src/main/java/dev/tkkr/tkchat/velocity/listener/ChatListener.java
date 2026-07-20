package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.core.model.RouteDecision;
import dev.tkkr.tkchat.core.service.GroupChannels;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;

import java.util.concurrent.CompletableFuture;

public final class ChatListener {
    private final VelocityChatService chat;
    private final PlayerStateService states;

    public ChatListener(VelocityChatService chat, PlayerStateService states) {
        this.chat = chat;
        this.states = states;
    }

    @Subscribe(priority = Short.MIN_VALUE / 2)
    public EventTask onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String activeChannel = states.activeChannel(player.getUniqueId());
        var decisionStage = GroupChannels.groupId(activeChannel).isPresent()
                ? chat.interceptGroup(player, event.getMessage())
                : chat.interceptChannel(player, activeChannel, event.getMessage());
        CompletableFuture<Void> completion = decisionStage.handle((decision, error) -> {
                    denyVanillaChat(event);
                    if (error != null) {
                        player.sendMessage(VelocityChatService.error(
                                "Chat moderation failed. Your message was not sent."));
                    } else if (decision instanceof RouteDecision.Denied denied) {
                        player.sendMessage(VelocityChatService.denial(denied.reason()));
                    }
                    return (Void) null;
                })
                .toCompletableFuture();
        return EventTask.resumeWhenComplete(completion);
    }

    private static void denyVanillaChat(PlayerChatEvent event) {
        // Velocity deprecates cancelling signed chat directly. SignedVelocity consumes this result,
        // safely cancels it on the backend, then restores the proxy event to allowed.
        ResultedEvent<PlayerChatEvent.ChatResult> resultedEvent = event;
        resultedEvent.setResult(PlayerChatEvent.ChatResult.denied());
    }
}
