package dev.tkkr.tkchat.velocity.listener;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.ResponseTestFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class VanillaCommandBypassListenerTest {
    @Test
    void namespacedDirectMessagesRequireTheTkChatMessagePermission() {
        AtomicInteger deliveredMessages = new AtomicInteger();
        Player sender = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> false;
                    case "sendMessage" -> {
                        deliveredMessages.incrementAndGet();
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        CommandExecuteEvent event = new CommandExecuteEvent(
                sender, "minecraft:msg Recipient hello");
        VanillaCommandBypassListener listener = new VanillaCommandBypassListener(
                null, null, ResponseTestFixtures.responses());

        assertNull(listener.onCommand(event));

        assertFalse(event.getResult().isAllowed());
        org.junit.jupiter.api.Assertions.assertEquals(1, deliveredMessages.get());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
