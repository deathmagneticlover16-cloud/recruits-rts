package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.compat.EpicKnightsLanceCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Mirrors Epic Knights' entity-local lance pose to tracking clients. */
public record LancePoseSyncPacket(int entityId, boolean raised) {
    public static void encode(LancePoseSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeBoolean(packet.raised);
    }

    public static LancePoseSyncPacket decode(FriendlyByteBuf buffer) {
        return new LancePoseSyncPacket(buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(LancePoseSyncPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (Minecraft.getInstance().level == null) return;
            Entity entity = Minecraft.getInstance().level.getEntity(packet.entityId);
            if (entity instanceof LivingEntity rider) {
                EpicKnightsLanceCompat.setRaised(rider, packet.raised);
            }
        }));
        context.setPacketHandled(true);
    }
}
