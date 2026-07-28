package dev.tkkr.tkchat.velocity.service;

import dev.tkkr.tkchat.core.model.Coordinates;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordinateServiceTest {
    @Test
    void requestUsesVersionedCoordinateProtocol() throws Exception {
        UUID requestId = UUID.randomUUID();

        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(CoordinateService.request(requestId)))) {
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(requestId, new UUID(input.readLong(), input.readLong()));
            assertEquals(0, input.available());
        }
    }

    @Test
    void decodesBackendCoordinateResponse() throws Exception {
        UUID requestId = UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeByte(1);
            output.writeLong(requestId.getMostSignificantBits());
            output.writeLong(requestId.getLeastSignificantBits());
            output.writeInt(-120);
            output.writeInt(72);
            output.writeInt(884);
            output.writeUTF("minecraft:the_end");
        }

        CoordinateService.CoordinateResponse response =
                CoordinateService.decodeResponse(bytes.toByteArray());

        assertEquals(requestId, response.requestId());
        assertEquals(new Coordinates(-120, 72, 884, "minecraft:the_end"),
                response.coordinates());
    }
}
