package ee.lastpagans.recruitsrtscamera.client;

/**
 * Visual battlefield classification used by the private-server Commander interface.
 *
 * <p>0.10.7 uses the user's trim-matched icon family. Card cells remain authored at
 * 32x32, while the matching floating-banner emblem cells remain authored at 64x128.
 * Shield infantry intentionally reuses the sword-infantry emblem because the supplied
 * set omits a redundant dedicated shieldman symbol.</p>
 */
public enum CommanderUnitType {
    SWORD("Sword Infantry", 0, BannerShape.SQUARE),
    SHIELDMAN("Shield Infantry", 0, BannerShape.SQUARE),
    SPEAR("Spearmen", 1, BannerShape.SQUARE),
    BOW("Archers", 2, BannerShape.POINTED),
    CROSSBOW("Crossbowmen", 3, BannerShape.POINTED),
    CAVALRY("Cavalry", 4, BannerShape.ROUNDED),
    NOMAD("Horse Archers", 5, BannerShape.ROUNDED),
    SHIP("Ship", 6, BannerShape.ROUNDED),
    ARTILLERY("Artillery", 7, BannerShape.POINTED);

    private final String displayName;
    private final int spriteRow;
    private final BannerShape bannerShape;

    CommanderUnitType(String displayName, int spriteRow, BannerShape bannerShape) {
        this.displayName = displayName;
        this.spriteRow = spriteRow;
        this.bannerShape = bannerShape;
    }

    public String displayName() {
        return displayName;
    }

    /** Row used by both the 32x32 card sheet and the 64x128 banner-emblem sheets. */
    public int spriteRow() {
        return spriteRow;
    }

    public BannerShape bannerShape() {
        return bannerShape;
    }

    public enum BannerShape {
        POINTED,
        ROUNDED,
        SQUARE
    }
}
