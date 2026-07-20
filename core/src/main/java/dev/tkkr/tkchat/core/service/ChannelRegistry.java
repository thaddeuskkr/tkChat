package dev.tkkr.tkchat.core.service;

import dev.tkkr.tkchat.core.model.ChannelDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ChannelRegistry {
    private final Map<String, ChannelDefinition> channels;
    private final Map<String, ChannelDefinition> lookup;

    public ChannelRegistry(Collection<ChannelDefinition> definitions) {
        Map<String, ChannelDefinition> collected = new LinkedHashMap<>();
        Map<String, ChannelDefinition> resolved = new LinkedHashMap<>();
        for (ChannelDefinition definition : definitions) {
            ChannelDefinition previous = collected.put(definition.id(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate channel " + definition.id());
            }
            addLookup(resolved, definition.id(), definition);
            definition.aliases().forEach(alias -> addLookup(resolved, alias, definition));
        }
        if (collected.isEmpty()) {
            throw new IllegalArgumentException("At least one channel must be configured");
        }
        this.channels = Map.copyOf(collected);
        this.lookup = Map.copyOf(resolved);
    }

    public Optional<ChannelDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(lookup.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<ChannelDefinition> all() {
        return channels.values();
    }

    private static void addLookup(
            Map<String, ChannelDefinition> resolved,
            String name,
            ChannelDefinition definition
    ) {
        ChannelDefinition previous = resolved.put(name, definition);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate channel id or alias " + name);
        }
    }
}
