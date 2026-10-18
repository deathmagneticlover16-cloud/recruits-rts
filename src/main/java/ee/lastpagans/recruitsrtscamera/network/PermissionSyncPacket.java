package ee.lastpagans.recruitsrtscamera.network;

import ee.lastpagans.recruitsrtscamera.client.ClientPermissionState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server policy/optional-compat state needed by the client RTS UI. */
public record PermissionSyncPacket(
        boolean allowed,
        boolean whitelistMode,
        boolean siegeWeaponsAvailable,
        boolean smallShipsAvailable,
        boolean fogEnabled,
        int playerVisionRadius,
        int recruitVisionRadius,
        int cavalryVisionRadius,
        int shipVisionRadius
) {
    public static void encode(PermissionSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.allowed);
        buffer.writeBoolean(packet.whitelistMode);
        buffer.writeBoolean(packet.siegeWeaponsAvailable);
        buffer.writeBoolean(packet.smallShipsAvailable);
        buffer.writeBoolean(packet.fogEnabled);
        buffer.writeVarInt(packet.playerVisionRadius);
        buffer.writeVarInt(packet.recruitVisionRadius);
        buffer.writeVarInt(packet.cavalryVisionRadius);
        buffer.writeVarInt(packet.shipVisionRadius);
    }

    public static PermissionSyncPacket decode(FriendlyByteBuf buffer) {
        return new PermissionSyncPacket(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    public static void handle(PermissionSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientPermissionState.update(
                        packet.allowed,
                        packet.whitelistMode,
                        packet.siegeWeaponsAvailable,
                        packet.smallShipsAvailable,
                        packet.fogEnabled,
                        packet.playerVisionRadius,
                        packet.recruitVisionRadius,
                        packet.cavalryVisionRadius,
                        packet.shipVisionRadius
                )
        ));
        context.setPacketHandled(true);
    }
}
