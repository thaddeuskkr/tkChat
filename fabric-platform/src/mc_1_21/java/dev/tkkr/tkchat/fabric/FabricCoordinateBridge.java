package dev.tkkr.tkchat.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

final class FabricCoordinateBridge {
    private FabricCoordinateBridge() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(CoordinatePayload.TYPE, CoordinatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CoordinatePayload.TYPE, CoordinatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CoordinatePayload.TYPE, (payload, context) -> {
            var player = context.player();
            byte[] response = response(
                    payload.data(), player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                    player.level().dimension().location().toString());
            if (response != null) {
                ServerPlayNetworking.send(player, new CoordinatePayload(response));
            }
        });
    }

    private static byte[] response(byte[] data, int x, int y, int z, String world) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                return null;
            }
            UUID requestId = new UUID(input.readLong(), input.readLong());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(1);
                output.writeByte(1);
                output.writeLong(requestId.getMostSignificantBits());
                output.writeLong(requestId.getLeastSignificantBits());
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeUTF(world);
            }
            return bytes.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }

    private record CoordinatePayload(byte[] data) implements CustomPacketPayload {
        private static final Type<CoordinatePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("tkchat", "coords"));
        private static final StreamCodec<RegistryFriendlyByteBuf, CoordinatePayload> CODEC =
                StreamCodec.of(
                        (buffer, payload) -> buffer.writeBytes(payload.data),
                        buffer -> {
                            byte[] data = new byte[buffer.readableBytes()];
                            buffer.readBytes(data);
                            return new CoordinatePayload(data);
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
