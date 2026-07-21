package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TkChatCommand implements SimpleCommand {
    private static final String HELP_NAME = "help";
    private static final Help HELP = new Help(
            "/tkchat help [command]",
            "Show the commands available to you, or detailed help for one command.",
            List.of());

    public record Help(String usage, String description, List<String> aliases) {
        public Help {
            usage = Objects.requireNonNull(usage, "usage");
            description = Objects.requireNonNull(description, "description");
            aliases = aliases == null
                    ? List.of()
                    : aliases.stream()
                    .map(TkChatCommand::normalize)
                    .distinct()
                    .toList();
        }
    }

    public record Child(String name, String permission, SimpleCommand command, Help help) {
        public Child {
            name = normalize(name);
            permission = Objects.requireNonNull(permission, "permission");
            command = Objects.requireNonNull(command, "command");
            help = Objects.requireNonNull(help, "help");
        }
    }

    private record HelpEntry(String name, String permission, Help help) {
    }

    private final Map<String, Child> children;
    private final Map<String, Child> helpTargets;
    private final String version;
    private final ResponseService responses;

    public TkChatCommand(
            List<Child> children,
            String version,
            ResponseService responses
    ) {
        LinkedHashMap<String, Child> indexed = new LinkedHashMap<>();
        for (Child child : children) {
            if (HELP_NAME.equals(child.name())) {
                throw new IllegalArgumentException("The tkchat help subcommand is reserved");
            }
            if (indexed.put(child.name(), child) != null) {
                throw new IllegalArgumentException(
                        "Duplicate tkchat subcommand " + child.name());
            }
        }

        LinkedHashMap<String, Child> targets = new LinkedHashMap<>(indexed);
        for (Child child : indexed.values()) {
            for (String alias : child.help().aliases()) {
                Child existing = targets.putIfAbsent(alias, child);
                if (existing != null && existing != child) {
                    throw new IllegalArgumentException(
                            "Ambiguous tkchat help target " + alias);
                }
            }
        }

        this.children = Collections.unmodifiableMap(indexed);
        this.helpTargets = Collections.unmodifiableMap(targets);
        this.version = Objects.requireNonNull(version, "version");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            showStatus(invocation.source());
            return;
        }
        if (HELP_NAME.equals(normalize(arguments[0]))) {
            showHelp(invocation.source(), Arrays.copyOfRange(arguments, 1, arguments.length));
            return;
        }

        Child child = children.get(normalize(arguments[0]));
        if (child == null) {
            invocation.source().sendMessage(responses.message(ResponseKey.ROOT_UNKNOWN));
            invocation.source().sendMessage(responses.message(ResponseKey.ROOT_STATUS_HELP));
            return;
        }
        if (!canUse(invocation.source(), child)) {
            invocation.source().sendMessage(responses.message(ResponseKey.GENERAL_NO_PERMISSION));
            return;
        }
        child.command().execute(childInvocation(invocation, child.name(),
                Arrays.copyOfRange(arguments, 1, arguments.length)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length <= 1) {
            String prefix = arguments.length == 0 ? "" : normalize(arguments[0]);
            return availableNames(invocation.source()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }
        if (HELP_NAME.equals(normalize(arguments[0]))) {
            if (arguments.length != 2) {
                return List.of();
            }
            String prefix = normalize(arguments[1]);
            return availableNames(invocation.source()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }

        Child child = children.get(normalize(arguments[0]));
        if (child == null || !canUse(invocation.source(), child)) {
            return List.of();
        }
        return child.command().suggest(childInvocation(invocation, child.name(),
                Arrays.copyOfRange(arguments, 1, arguments.length)));
    }

    private void showStatus(CommandSource source) {
        showVersion(source);
        source.sendMessage(responses.message(ResponseKey.ROOT_STATUS_HELP));
    }

    private void showVersion(CommandSource source) {
        source.sendMessage(responses.message(
                ResponseKey.ROOT_STATUS,
                ResponseService.text("version", version)));
    }

    private void showHelp(CommandSource source, String[] arguments) {
        if (arguments.length == 0) {
            showVersion(source);
            for (HelpEntry entry : availableEntries(source)) {
                source.sendMessage(responses.message(
                        ResponseKey.ROOT_HELP_ENTRY,
                        ResponseService.text("usage", entry.help().usage())));
            }
            return;
        }
        if (arguments.length != 1) {
            source.sendMessage(responses.message(ResponseKey.ROOT_HELP_USAGE));
            return;
        }

        HelpEntry entry = helpEntry(source, arguments[0]);
        if (entry == null) {
            source.sendMessage(responses.message(ResponseKey.ROOT_HELP_UNKNOWN));
            return;
        }
        source.sendMessage(responses.message(
                ResponseKey.ROOT_HELP_DETAIL_HEADING,
                ResponseService.text("command", entry.name())));
        source.sendMessage(responses.message(
                ResponseKey.ROOT_HELP_DETAIL_DESCRIPTION,
                ResponseService.text("description", entry.help().description())));
        source.sendMessage(responses.message(
                ResponseKey.ROOT_HELP_DETAIL_USAGE,
                ResponseService.text("usage", entry.help().usage())));
        if (!entry.help().aliases().isEmpty()) {
            source.sendMessage(responses.message(
                    ResponseKey.ROOT_HELP_DETAIL_ALIASES,
                    ResponseService.text("aliases", entry.help().aliases().stream()
                            .map(alias -> "/" + alias)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse(""))));
        }
        if (!entry.permission().isBlank()) {
            source.sendMessage(responses.message(
                    ResponseKey.ROOT_HELP_DETAIL_PERMISSION,
                    ResponseService.text("permission", entry.permission())));
        }
    }

    private HelpEntry helpEntry(CommandSource source, String requested) {
        String name = normalize(requested.startsWith("/") ? requested.substring(1) : requested);
        if (HELP_NAME.equals(name)) {
            return new HelpEntry(HELP_NAME, "", HELP);
        }
        Child child = helpTargets.get(name);
        return child != null && canUse(source, child)
                ? new HelpEntry(child.name(), child.permission(), child.help())
                : null;
    }

    private List<HelpEntry> availableEntries(CommandSource source) {
        ArrayList<HelpEntry> available = new ArrayList<>();
        available.add(new HelpEntry(HELP_NAME, "", HELP));
        children.values().stream()
                .filter(child -> canUse(source, child))
                .map(child -> new HelpEntry(child.name(), child.permission(), child.help()))
                .forEach(available::add);
        return available.stream().sorted(java.util.Comparator.comparing(HelpEntry::name)).toList();
    }

    private List<String> availableNames(CommandSource source) {
        return availableEntries(source).stream().map(HelpEntry::name).toList();
    }

    private static boolean canUse(CommandSource source, Child child) {
        return child.permission().isBlank() || source.hasPermission(child.permission());
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "name").toLowerCase(Locale.ROOT);
    }

    private static Invocation childInvocation(
            Invocation parent,
            String alias,
            String[] arguments
    ) {
        return new Invocation() {
            @Override
            public CommandSource source() {
                return parent.source();
            }

            @Override
            public String[] arguments() {
                return arguments;
            }

            @Override
            public String alias() {
                return alias;
            }
        };
    }
}
