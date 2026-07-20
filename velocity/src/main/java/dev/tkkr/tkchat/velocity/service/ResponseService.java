package dev.tkkr.tkchat.velocity.service;

import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.config.ResponseMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Objects;

/** Renders administrator-controlled feedback while keeping dynamic values literal. */
public final class ResponseService {
    private record State(Component prefix, ResponseMessages messages) {
    }

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile State state;

    public ResponseService(String responsePrefix, ResponseMessages messages) {
        reconfigure(responsePrefix, messages);
    }

    public void reconfigure(String responsePrefix, ResponseMessages messages) {
        Objects.requireNonNull(responsePrefix, "responsePrefix");
        state = new State(miniMessage.deserialize(responsePrefix),
                Objects.requireNonNull(messages, "messages"));
    }

    public Component message(ResponseKey key, TagResolver... placeholders) {
        State current = state;
        return current.prefix().append(render(current, key, placeholders));
    }

    public Component content(ResponseKey key, TagResolver... placeholders) {
        return render(state, key, placeholders);
    }

    public Component denial(DenialReason reason) {
        return message(switch (reason) {
            case NOT_READY -> ResponseKey.DENIAL_NOT_READY;
            case UNKNOWN_CHANNEL -> ResponseKey.DENIAL_UNKNOWN_CHANNEL;
            case NO_PERMISSION -> ResponseKey.DENIAL_NO_PERMISSION;
            case MUTED -> ResponseKey.DENIAL_MUTED;
            case LINKS_NOT_ALLOWED -> ResponseKey.DENIAL_LINKS_NOT_ALLOWED;
            case RATE_LIMITED -> ResponseKey.DENIAL_RATE_LIMITED;
            case CHAT_BACKLOG_FULL -> ResponseKey.DENIAL_BACKLOG_FULL;
            case MESSAGE_EXPIRED -> ResponseKey.DENIAL_MESSAGE_EXPIRED;
            case INVALID_MESSAGE -> ResponseKey.DENIAL_INVALID_MESSAGE;
            case DIRECT_MESSAGES_DISABLED -> ResponseKey.DENIAL_DIRECT_DISABLED;
            case IGNORED -> ResponseKey.DENIAL_IGNORED;
            case NOT_IN_GROUP -> ResponseKey.DENIAL_NOT_IN_GROUP;
            case STORAGE_UNAVAILABLE -> ResponseKey.DENIAL_STORAGE_UNAVAILABLE;
            case INTERNAL_ERROR -> ResponseKey.DENIAL_INTERNAL_ERROR;
        });
    }

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    private Component render(State current, ResponseKey key, TagResolver... placeholders) {
        return miniMessage.deserialize(current.messages().template(key), placeholders);
    }
}
