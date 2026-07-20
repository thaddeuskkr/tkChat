package dev.tkkr.tkchat.core.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record ChannelDefinition(
        String id,
        String displayName,
        ChannelScope scope,
        String sendPermission,
        String receivePermission,
        String bypassPermission,
        List<String> aliases,
        String format
) {
    public ChannelDefinition {
        id = requireIdentifier(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        scope = Objects.requireNonNull(scope, "scope");
        sendPermission = Objects.requireNonNull(sendPermission, "sendPermission");
        receivePermission = Objects.requireNonNull(receivePermission, "receivePermission");
        bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission");
        aliases = aliases == null
                ? List.of()
                : aliases.stream().map(alias -> requireIdentifier(alias, "alias")).toList();
        if (new LinkedHashSet<>(aliases).size() != aliases.size()) {
            throw new IllegalArgumentException("Channel aliases must be unique");
        }
        if (aliases.contains(id)) {
            throw new IllegalArgumentException("Channel aliases cannot repeat the channel id");
        }
        format = Objects.requireNonNull(format, "format");
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(field + " must match [a-z0-9_-]{1,32}");
        }
        return normalized;
    }
}
