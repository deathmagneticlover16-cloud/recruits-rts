package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.network.SetGroupFlagPacket;
import immersive_paintings.resources.ClientPaintingManager;
import immersive_paintings.resources.Painting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Group-oriented browser over the painting library supplied by Immersive Paintings. */
public final class ImmersivePaintingsFlagScreen extends Screen {
    private static final int COLUMNS = 8;
    private static final int ROWS = 3;
    private static final int PAGE_SIZE = COLUMNS * ROWS;
    private final UUID groupId;
    private List<Map.Entry<ResourceLocation, Painting>> paintings = List.of();
    private int page;

    public ImmersivePaintingsFlagScreen(UUID groupId) {
        super(Component.literal("Choose Group Flag"));
        this.groupId = groupId;
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
    protected void init() {
        paintings = new ArrayList<>(ClientPaintingManager.getPaintings().entrySet());
        paintings.sort(Comparator
                .comparing((Map.Entry<ResourceLocation, Painting> entry) -> entry.getValue().name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.getKey().toString()));
        page = Math.max(0, Math.min(page, maximumPage()));
        rebuildPage();
    }

    private void rebuildPage() {
        clearWidgets();
        int gridWidth = COLUMNS * 48;
        int startX = (width - gridWidth) / 2;
        int startY = height / 2 - 70;
        int first = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && first + slot < paintings.size(); slot++) {
            Map.Entry<ResourceLocation, Painting> entry = paintings.get(first + slot);
            int x = startX + slot % COLUMNS * 48 + 1;
            int y = startY + slot / COLUMNS * 48 + 1;
            addRenderableWidget(new PaintingChoice(entry.getKey(), entry.getValue(), x, y));
        }
        addRenderableWidget(Button.builder(Component.literal("Default"), button -> select(null))
                .bounds(width / 2 - 142, startY + 151, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
                .bounds(width / 2 - 55, startY + 151, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
                .bounds(width / 2 + 25, startY + 151, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 + 62, startY + 151, 80, 20).build());
    }

    private void changePage(int direction) {
        page = Math.max(0, Math.min(maximumPage(), page + direction));
        rebuildPage();
    }

    private int maximumPage() {
        return Math.max(0, (paintings.size() - 1) / PAGE_SIZE);
    }

    private void select(ResourceLocation paintingId) {
        RTSCameraNetwork.CHANNEL.sendToServer(new SetGroupFlagPacket(
                groupId, paintingId == null ? "" : paintingId.toString()));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 92, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal((page + 1) + " / " + (maximumPage() + 1)),
                width / 2, height / 2 + 86, 0xFFBEB8AA);
        if (paintings.isEmpty()) graphics.drawCenteredString(font,
                Component.literal("No synchronized paintings are available."),
                width / 2, height / 2 - 4, 0xFFFFAA66);
    }

    private final class PaintingChoice extends AbstractButton {
        private final ResourceLocation paintingId;
        private final Painting painting;

        PaintingChoice(ResourceLocation paintingId, Painting painting, int x, int y) {
            super(x, y, 46, 46, Component.empty());
            this.paintingId = paintingId;
            this.painting = painting;
            Component name = Component.literal(painting.name == null || painting.name.isBlank()
                    ? paintingId.toString() : painting.name);
            Component author = Component.literal(painting.author == null ? "" : painting.author)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            setTooltip(Tooltip.create(Component.empty().append(name).append("\n").append(author)));
        }

        @Override
        public void onPress() {
            select(paintingId);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF786F5A;
            graphics.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 1, border);
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF171612);
            Painting.Texture thumbnail = ClientPaintingManager.getPaintingTexture(
                    paintingId, Painting.Type.THUMBNAIL);
            graphics.blit(thumbnail.textureIdentifier, getX() + 2, getY() + 2,
                    0.0F, 0.0F, width - 4, height - 4, width - 4, height - 4);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
