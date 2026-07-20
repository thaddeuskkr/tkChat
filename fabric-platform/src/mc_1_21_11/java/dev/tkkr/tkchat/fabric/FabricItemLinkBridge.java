package dev.tkkr.tkchat.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

final class FabricItemLinkBridge {
    private FabricItemLinkBridge() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(ItemPayload.TYPE, ItemPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ItemPayload.TYPE, ItemPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ItemPayload.TYPE, (payload, context) -> {
            byte[] response = response(payload.data(), context.player().getMainHandItem());
            if (response != null) {
                ServerPlayNetworking.send(context.player(), new ItemPayload(response));
            }
        });
    }

    private static byte[] response(byte[] data, ItemStack item) {
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
                output.writeBoolean(!item.isEmpty());
                if (!item.isEmpty()) {
                    output.writeUTF(BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                    output.writeInt(item.getCount());
                    output.writeUTF(item.getHoverName().getString());
                }
            }
            return bytes.toByteArray();
        } catch (IOException ignored) {
            return null;
        }
    }

    private record ItemPayload(byte[] data) implements CustomPacketPayload {
        private static final Type<ItemPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("tkchat", "item"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ItemPayload> CODEC =
                StreamCodec.of(
                        (buffer, payload) -> buffer.writeBytes(payload.data),
                        buffer -> {
                            byte[] data = new byte[buffer.readableBytes()];
                            buffer.readBytes(data);
                            return new ItemPayload(data);
                        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
