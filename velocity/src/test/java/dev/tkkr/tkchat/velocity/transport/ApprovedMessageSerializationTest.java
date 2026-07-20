package dev.tkkr.tkchat.velocity.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.ItemLink;
import dev.tkkr.tkchat.core.model.RouteKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovedMessageSerializationTest {
    @Test
    void preservesItemLinkAcrossNetworkSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ApprovedMessage original = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "global", "Global", "global", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "Look: <item>",
                Set.of("bold", "red"),
                new ItemLink("minecraft:diamond_sword", 1, "Diamond Sword"), Set.of());

        ApprovedMessage decoded = mapper.readValue(mapper.writeValueAsBytes(original), ApprovedMessage.class);

        assertEquals(original, decoded);
    }
}
