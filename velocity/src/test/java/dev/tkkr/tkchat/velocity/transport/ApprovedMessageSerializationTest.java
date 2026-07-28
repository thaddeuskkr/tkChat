package dev.tkkr.tkchat.velocity.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.tkkr.tkchat.core.model.ApprovedMessage;
import dev.tkkr.tkchat.core.model.ChannelScope;
import dev.tkkr.tkchat.core.model.Coordinates;
import dev.tkkr.tkchat.core.model.ItemLink;
import dev.tkkr.tkchat.core.model.RouteKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void preservesBackwardCompatibleActionMarkerAcrossNetworkSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ApprovedMessage original = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "global", "Global", "global", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "waves",
                Set.of(), null, Set.of()).asAction();

        ApprovedMessage decoded = mapper.readValue(
                mapper.writeValueAsBytes(original), ApprovedMessage.class);

        assertEquals(original, decoded);
        org.junit.jupiter.api.Assertions.assertTrue(decoded.hasActionMarker());
    }

    @Test
    void preservesCoordinatesWithoutChangingTheWireSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Coordinates coordinates = new Coordinates(-123, 64, 456, "minecraft:the_nether");
        ApprovedMessage original = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.BROADCAST,
                "broadcast", "Broadcast", "broadcast", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "Meet at [coords]",
                Set.of("bold"), null, Set.of()).withCoordinates(coordinates);

        byte[] encoded = mapper.writeValueAsBytes(original);
        ApprovedMessage decoded = mapper.readValue(encoded, ApprovedMessage.class);

        assertEquals(original, decoded);
        assertEquals(coordinates, decoded.findCoordinates().orElseThrow());
        assertFalse(mapper.readTree(encoded).has("coordinates"));
    }

    @Test
    void ignoresMalformedCoordinateMarkers() {
        ApprovedMessage message = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "global", "Global", "global", ChannelScope.GLOBAL,
                UUID.randomUUID(), "Alice", "alpha", "", "", "[coords]",
                Set.of("tkchat:coords:not-valid"), null, Set.of());

        assertFalse(message.findCoordinates().isPresent());
    }

    @Test
    void preservesLifecycleMarkersAcrossNetworkSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ApprovedMessage original = new ApprovedMessage(
                UUID.randomUUID(), Instant.parse("2026-07-20T12:00:00Z"), RouteKind.CHANNEL,
                "lobby", "survival", "presence", ChannelScope.SERVER,
                UUID.randomUUID(), "Alice", "", "", "",
                "Alice left lobby and joined survival.",
                Set.of(), null, Set.of())
                .asJoinMessage()
                .asServerSwitchMessage();

        ApprovedMessage decoded = mapper.readValue(
                mapper.writeValueAsBytes(original), ApprovedMessage.class);

        assertEquals(original, decoded);
        org.junit.jupiter.api.Assertions.assertTrue(decoded.hasJoinMarker());
        org.junit.jupiter.api.Assertions.assertTrue(decoded.hasServerSwitchMarker());
    }
}
