package dev.tkkr.tkchat.velocity;

import java.util.Locale;

public final class Permissions {
    public static final String CHANNEL_OTHERS = "tkchat.command.channel.others";
    public static final String BYPASS_RATE_LIMIT = "tkchat.bypass.ratelimit";
    public static final String BYPASS_LINKS = "tkchat.bypass.links";
    public static final String BYPASS_PRIVATE_GROUPS = "tkchat.bypass.private_groups";
    public static final String BYPASS_GROUP_JOIN_NOTIFICATIONS =
            "tkchat.bypass.group_join_notifications";
    public static final String BYPASS_CHANNEL_RESTRICTIONS = "tkchat.bypass.channel_restrictions";
    public static final String BYPASS_CHAT_CLEAR = "tkchat.bypass.chat_clear";

    private Permissions() {
    }

    public static String command(String command) {
        return "tkchat.command." + normalize(command);
    }

    public static String channelSend(String channel) {
        return "tkchat.channel." + normalize(channel) + ".send";
    }

    public static String channelReceive(String channel) {
        return "tkchat.channel." + normalize(channel) + ".receive";
    }

    public static String format(String format) {
        return "tkchat.format." + normalize(format);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
