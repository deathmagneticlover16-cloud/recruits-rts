package ee.lastpagans.recruitsrtscamera.network;

import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsSaveData;
import ee.lastpagans.recruitsrtscamera.persistence.RTSGroupFlagSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public record SetGroupFlagPacket(UUID groupId, String paintingId) {
    public SetGroupFlagPacket {
        paintingId = paintingId == null ? "" : paintingId;
    }

    public static void encode(SetGroupFlagPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.groupId);
        buffer.writeUtf(packet.paintingId, 256);
    }

    public static SetGroupFlagPacket decode(FriendlyByteBuf buffer) {
        return new SetGroupFlagPacket(buffer.readUUID(), buffer.readUtf(256));
    }

    public static void handle(SetGroupFlagPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> apply(context.getSender(), packet));
        context.setPacketHandled(true);
    }

    private static void apply(ServerPlayer sender, SetGroupFlagPacket packet) {
        if (sender == null || packet.groupId == null || packet.paintingId.length() > 256) return;
        RecruitsGroup owned = null;
        for (RecruitsGroup group : RecruitsGroupsSaveData.get(sender.serverLevel()).getAllGroups()) {
            if (group != null && !group.removed && packet.groupId.equals(group.getUUID())
                    && sender.getUUID().equals(group.getPlayerUUID())) {
                owned = group;
                break;
            }
        }
        if (owned == null) return;
        ResourceLocation selected = packet.paintingId.isBlank()
                ? null : ResourceLocation.tryParse(packet.paintingId);
        if (!packet.paintingId.isBlank() && selected == null) return;
        RTSGroupFlagSavedData.get(sender.serverLevel()).set(packet.groupId, selected);
        String synced = selected == null ? "" : selected.toString();
        RTSCameraNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new GroupFlagStateSyncPacket(Map.of(packet.groupId, synced), false));
    }
}
