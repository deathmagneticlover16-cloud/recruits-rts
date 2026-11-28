package ee.lastpagans.recruitsrtscamera.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** A positional sound explicitly delivered to a detached RTS listener. */
public record CameraSoundPacket(ResourceLocation sound, SoundSource source, double x, double y,
                                double z, float volume, float pitch) {
    public static void encode(CameraSoundPacket packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.sound);
        buffer.writeEnum(packet.source);
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeFloat(packet.volume);
        buffer.writeFloat(packet.pitch);
    }

    public static CameraSoundPacket decode(FriendlyByteBuf buffer) {
        return new CameraSoundPacket(buffer.readResourceLocation(), buffer.readEnum(SoundSource.class),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat(), buffer.readFloat());
    }

    public static void handle(CameraSoundPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return;
            var sound = BuiltInRegistries.SOUND_EVENT.get(packet.sound);
            minecraft.level.playLocalSound(packet.x, packet.y, packet.z, sound, packet.source,
                    packet.volume, packet.pitch, false);
        });
        context.setPacketHandled(true);
    }
}
