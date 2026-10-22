package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.CavalryRumbleClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative position and gain for one client-side fading cavalry loop. */
public record CavalryRumblePacket(UUID groupId, boolean active, Vec3 center, float volume, float pitch) {
    public static void encode(CavalryRumblePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.groupId);
        buffer.writeBoolean(packet.active);
        buffer.writeDouble(packet.center.x);
        buffer.writeDouble(packet.center.y);
        buffer.writeDouble(packet.center.z);
        buffer.writeFloat(packet.volume);
        buffer.writeFloat(packet.pitch);
    }

    public static CavalryRumblePacket decode(FriendlyByteBuf buffer) {
        UUID groupId = buffer.readUUID();
        boolean active = buffer.readBoolean();
        Vec3 center = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        float volume = Math.max(0.0F, Math.min(1.5F, buffer.readFloat()));
        float pitch = Math.max(0.5F, Math.min(2.0F, buffer.readFloat()));
        return new CavalryRumblePacket(groupId, active, center, volume, pitch);
    }

    public static void handle(CavalryRumblePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CavalryRumbleClient.update(packet)));
        context.setPacketHandled(true);
    }
}
