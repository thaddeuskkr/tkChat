package dev.tkkr.tkchat.velocity.service;

import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.Permissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PlayerFormattingService {
    public static final List<String> NAMED_COLORS = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white");
    public static final List<String> DECORATIONS = List.of(
            "bold", "italic", "underlined", "strikethrough", "obfuscated");
    public static final List<String> EFFECTS = List.of(
            "hex", "gradient", "transition", "rainbow", "pride", "shadow", "font", "reset",
            "newline");

    private static final Set<String> COLOR_WRAPPERS = Set.of("color", "colour", "c");
    private static final Map<String, String> COLOR_KEYS = colorKeys();
    private static final Map<String, TagResolver> DECORATION_RESOLVERS = decorationResolvers();
    private static final Map<String, TagResolver> EFFECT_RESOLVERS = effectResolvers();
    private static final Set<String> KNOWN_FORMATS = knownFormats();

    private final MiniMessage playerMiniMessage = MiniMessage.builder()
            .tags(TagResolver.empty())
            .build();

    public Set<String> allowed(Player player) {
        HashSet<String> allowed = new HashSet<>();
        for (String format : KNOWN_FORMATS) {
            if (player.hasPermission(Permissions.format(format))) {
                allowed.add(format);
            }
        }
        return Set.copyOf(allowed);
    }

    public Component render(String input, Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return Component.text(input);
        }
        HashSet<String> permitted = new HashSet<>(allowed);
        permitted.retainAll(KNOWN_FORMATS);
        if (permitted.isEmpty()) {
            return Component.text(input);
        }
        TagResolver.Builder tags = TagResolver.builder();
        tags.resolver(filteredColors(permitted));
        DECORATION_RESOLVERS.forEach((permission, resolver) -> {
            if (permitted.contains(permission)) {
                tags.resolver(resolver);
            }
        });
        EFFECT_RESOLVERS.forEach((permission, resolver) -> {
            if (!permitted.contains(permission)) {
                return;
            }
            tags.resolver(switch (permission) {
                case "gradient", "transition", "shadow" -> filteredEffectColors(resolver, permitted);
                default -> resolver;
            });
        });
        try {
            return playerMiniMessage.deserialize(input, tags.build());
        } catch (RuntimeException malformedFormatting) {
            return Component.text(input);
        }
    }

    private static TagResolver filteredColors(Set<String> allowed) {
        TagResolver colors = StandardTags.color();
        return new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context context) {
                String requested = name;
                if (COLOR_WRAPPERS.contains(name)) {
                    Tag.Argument argument = arguments.peek();
                    requested = argument == null ? "" : argument.lowerValue();
                }
                String required = colorKey(requested);
                return required != null && allowed.contains(required)
                        ? colors.resolve(name, arguments, context)
                        : null;
            }

            @Override
            public boolean has(String name) {
                return colors.has(name);
            }
        };
    }

    private static TagResolver filteredEffectColors(TagResolver delegate, Set<String> allowed) {
        return new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context context) {
                List<String> values = new ArrayList<>();
                while (arguments.hasNext()) {
                    values.add(arguments.pop().lowerValue());
                }
                arguments.reset();
                for (String value : values) {
                    String required = colorKey(value);
                    if (required != null && !allowed.contains(required)) {
                        return null;
                    }
                }
                return delegate.resolve(name, arguments, context);
            }

            @Override
            public boolean has(String name) {
                return delegate.has(name);
            }
        };
    }

    private static String colorKey(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#")) {
            return "hex";
        }
        return COLOR_KEYS.get(normalized);
    }

    private static Map<String, String> colorKeys() {
        HashMap<String, String> keys = new HashMap<>();
        NAMED_COLORS.forEach(color -> keys.put(color, color));
        keys.put("grey", "gray");
        keys.put("dark_grey", "dark_gray");
        return Map.copyOf(keys);
    }

    private static Map<String, TagResolver> decorationResolvers() {
        LinkedHashMap<String, TagResolver> resolvers = new LinkedHashMap<>();
        resolvers.put("bold", StandardTags.decorations(TextDecoration.BOLD));
        resolvers.put("italic", StandardTags.decorations(TextDecoration.ITALIC));
        resolvers.put("underlined", StandardTags.decorations(TextDecoration.UNDERLINED));
        resolvers.put("strikethrough", StandardTags.decorations(TextDecoration.STRIKETHROUGH));
        resolvers.put("obfuscated", StandardTags.decorations(TextDecoration.OBFUSCATED));
        return Map.copyOf(resolvers);
    }

    private static Map<String, TagResolver> effectResolvers() {
        LinkedHashMap<String, TagResolver> resolvers = new LinkedHashMap<>();
        resolvers.put("gradient", StandardTags.gradient());
        resolvers.put("transition", StandardTags.transition());
        resolvers.put("rainbow", StandardTags.rainbow());
        resolvers.put("pride", StandardTags.pride());
        resolvers.put("shadow", StandardTags.shadowColor());
        resolvers.put("font", StandardTags.font());
        resolvers.put("reset", StandardTags.reset());
        resolvers.put("newline", StandardTags.newline());
        return Map.copyOf(resolvers);
    }

    private static Set<String> knownFormats() {
        HashSet<String> formats = new HashSet<>(NAMED_COLORS);
        formats.addAll(DECORATIONS);
        formats.addAll(EFFECTS);
        return Set.copyOf(formats);
    }
}
