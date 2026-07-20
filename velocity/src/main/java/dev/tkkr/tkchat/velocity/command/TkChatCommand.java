package dev.tkkr.tkchat.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.tkkr.tkchat.velocity.config.ResponseKey;
import dev.tkkr.tkchat.velocity.service.ResponseService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TkChatCommand implements SimpleCommand {
    public record Child(String name, String permission, SimpleCommand command) {
    }

    private final Map<String, Child> children;
    private final ResponseService responses;

    public TkChatCommand(List<Child> children, ResponseService responses) {
        LinkedHashMap<String, Child> indexed = new LinkedHashMap<>();
        for (Child child : children) {
            String name = child.name().toLowerCase(Locale.ROOT);
            if (!name.equals(child.name()) || indexed.put(name, child) != null) {
                throw new IllegalArgumentException("Duplicate or non-normalized tkchat subcommand " + child.name());
            }
        }
        this.children = Map.copyOf(indexed);
        this.responses = responses;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            showAvailable(invocation.source());
            return;
        }
        Child child = children.get(arguments[0].toLowerCase(Locale.ROOT));
        if (child == null) {
            invocation.source().sendMessage(responses.message(ResponseKey.ROOT_UNKNOWN));
            showAvailable(invocation.source());
            return;
        }
        if (!invocation.source().hasPermission(child.permission())) {
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
            String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
            return available(invocation.source()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }
        Child child = children.get(arguments[0].toLowerCase(Locale.ROOT));
        if (child == null || !invocation.source().hasPermission(child.permission())) {
            return List.of();
        }
        return child.command().suggest(childInvocation(invocation, child.name(),
                Arrays.copyOfRange(arguments, 1, arguments.length)));
    }

    private void showAvailable(CommandSource source) {
        List<String> available = available(source);
        if (available.isEmpty()) {
            source.sendMessage(responses.message(ResponseKey.ROOT_NO_AVAILABLE));
            return;
        }
        source.sendMessage(responses.message(
                ResponseKey.ROOT_AVAILABLE,
                ResponseService.text("commands", String.join(", ", available))));
    }

    private List<String> available(CommandSource source) {
        return children.values().stream()
                .filter(child -> source.hasPermission(child.permission()))
                .map(Child::name)
                .sorted()
                .toList();
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
