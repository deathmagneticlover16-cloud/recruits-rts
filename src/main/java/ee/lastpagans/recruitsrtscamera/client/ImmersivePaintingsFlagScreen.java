package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.mixin.ImmersivePaintingScreenBridge;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.network.SetGroupFlagPacket;
import immersive_paintings.Entities;
import immersive_paintings.client.gui.ImmersivePaintingScreen;
import immersive_paintings.entity.ImmersivePaintingEntity;
import immersive_paintings.resources.ClientPaintingManager;
import immersive_paintings.resources.Painting;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * The real Immersive Paintings editor, redirected from a temporary client painting to an RTS
 * group flag. All browser pages, uploads, filters, pagination and tooltips remain owned by
 * Immersive Paintings; only the final motive selection is redirected to our server packet.
 */
public final class ImmersivePaintingsFlagScreen extends ImmersivePaintingScreen {
    private final UUID groupId;
    private boolean ready;

    public ImmersivePaintingsFlagScreen(UUID groupId) {
        // These are Immersive Paintings' own default server values in 0.6.13.
        super(Integer.MIN_VALUE, 8, 256, true, 0);
        this.groupId = groupId;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            ImmersivePaintingEntity proxy = new ImmersivePaintingEntity(
                    Entities.PAINTING.get(), minecraft.level);
            // Mixin adds the bridge to the third-party superclass at runtime. Route through
            // Object so javac accepts the cast for this final subclass before transformation.
            ((ImmersivePaintingScreenBridge) (Object) this).recruitsRts$setEntity(proxy);
            ready = true;
        }
    }

    public static GroupFlagClientState.FlagTexture texture(ResourceLocation paintingId) {
        if (paintingId == null || !ClientPaintingManager.getPaintings().containsKey(paintingId)) return null;
        Painting painting = ClientPaintingManager.getPainting(paintingId);
        ResourceLocation texture = ClientPaintingManager.getPaintingTexture(
                paintingId, Painting.Type.FULL).textureIdentifier;
        double aspect = painting.height <= 0 ? 1.5D
                : Math.max(0.5D, Math.min(4.0D, painting.width / (double) painting.height));
        return new GroupFlagClientState.FlagTexture(texture, aspect);
    }

    @Override
    public void setPage(Page page) {
        if (ready && page == Page.FRAME && entity != null && entity.getMotive() != null) {
            RTSCameraNetwork.CHANNEL.sendToServer(
                    new SetGroupFlagPacket(groupId, entity.getMotive().toString()));
            Minecraft.getInstance().setScreen(null);
            return;
        }
        super.setPage(page);
    }

    @Override
    public void onClose() {
        // The parent constructor calls this when its ordinary world-entity lookup fails.
        // Ignore only that construction-time close; afterward Esc behaves normally.
        if (ready) super.onClose();
    }
}
