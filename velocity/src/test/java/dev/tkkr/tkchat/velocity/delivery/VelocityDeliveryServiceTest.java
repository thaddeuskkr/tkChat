package dev.tkkr.tkchat.velocity.delivery;

import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelDefinition;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.RouteKind;
import dev.tkkr.tkchat.core.service.ChannelRegistry;
import dev.tkkr.tkchat.velocity.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityDeliveryServiceTest {
    @Test
    void actionUsesMeFormatWithoutChangingItsExistingRouteKind() {
        ChannelDefinition global = new ChannelDefinition(
                "global", "Global", ChannelScope.GLOBAL,
                "send", "receive", "bypass", List.of("g"), "channel format");
        ChannelRegistry channels = new ChannelRegistry(List.of(global));
        AppConfig.Formats formats = new AppConfig.Formats();
        formats.me = "action format";
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "global", "Global", "global", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "waves",
                Set.of(), null, Set.of()).asAction();

        String selected = VelocityDeliveryService.selectTemplate(
                message, UUID.randomUUID(), channels, formats);

        assertEquals(RouteKind.CHANNEL, message.routeKind());
        assertEquals("action format", selected);
        assertEquals("channel format", VelocityDeliveryService.selectTemplate(
                message.withFormatting(Set.of()), UUID.randomUUID(), channels, formats));
    }
}
