package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.GroupFlagClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record OpenGroupFlagEditorPacket(UUID groupId) {
    public static void encode(OpenGroupFlagEditorPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.groupId);
    }

    public static OpenGroupFlagEditorPacket decode(FriendlyByteBuf buffer) {
        return new OpenGroupFlagEditorPacket(buffer.readUUID());
    }

    public static void handle(OpenGroupFlagEditorPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> GroupFlagClientState.open(packet.groupId)));
        context.setPacketHandled(true);
    }
}
