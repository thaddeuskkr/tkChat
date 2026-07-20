package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import dev.tkkr.tkchat.velocity.service.VelocityChatService;
import dev.tkkr.tkchat.velocity.state.ConversationTracker;
import dev.tkkr.tkchat.velocity.state.PlayerStateService;
import dev.tkkr.tkchat.velocity.state.SocialSpyService;

public final class PlayerLifecycleListener {
    private final PlayerStateService states;
    private final ConversationTracker conversations;
    private final SocialSpyService spies;
    private final VelocityChatService chat;

    public PlayerLifecycleListener(
            PlayerStateService states,
            ConversationTracker conversations,
            SocialSpyService spies,
            VelocityChatService chat
    ) {
        this.states = states;
        this.conversations = conversations;
        this.spies = spies;
        this.chat = chat;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        states.load(event.getPlayer().getUniqueId()).exceptionally(error -> {
            event.getPlayer().sendMessage(VelocityChatService.error(
                    "Your chat preferences could not be loaded; defaults will be used."));
            return null;
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        conversations.remove(event.getPlayer().getUniqueId());
        spies.remove(event.getPlayer().getUniqueId());
        chat.remove(event.getPlayer().getUniqueId());
    }
}
