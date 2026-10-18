package ee.lastpagans.recruitsrtscamera.client;

/** Last authoritative RTS policy/optional-compat state received from the server. */
public final class ClientPermissionState {
    private static volatile boolean known;
    private static volatile boolean allowed;
    private static volatile boolean whitelistMode;
    private static volatile boolean siegeWeaponsAvailable;
    private static volatile boolean smallShipsAvailable;
    private static volatile boolean fogEnabled;
    private static volatile int playerVisionRadius = 48;
    private static volatile int recruitVisionRadius = 40;
    private static volatile int cavalryVisionRadius = 48;
    private static volatile int shipVisionRadius = 64;

    private ClientPermissionState() {
    }

    public static void update(
            boolean allowedNow,
            boolean whitelistNow,
            boolean siegeAvailable,
            boolean shipsAvailable,
            boolean fogNow,
            int playerVision,
            int recruitVision,
            int cavalryVision,
            int shipVision
    ) {
        known = true;
        allowed = allowedNow;
        whitelistMode = whitelistNow;
        siegeWeaponsAvailable = siegeAvailable;
        smallShipsAvailable = shipsAvailable;
        fogEnabled = fogNow;
        playerVisionRadius = Math.max(1, playerVision);
        recruitVisionRadius = Math.max(1, recruitVision);
        cavalryVisionRadius = Math.max(1, cavalryVision);
        shipVisionRadius = Math.max(1, shipVision);
    }

    public static void reset() {
        known = false;
        allowed = false;
        whitelistMode = false;
        siegeWeaponsAvailable = false;
        smallShipsAvailable = false;
        fogEnabled = false;
        playerVisionRadius = 48;
        recruitVisionRadius = 40;
        cavalryVisionRadius = 48;
        shipVisionRadius = 64;
    }

    public static boolean known() { return known; }
    public static boolean allowed() { return known && allowed; }
    public static boolean whitelistMode() { return whitelistMode; }
    public static boolean siegeWeaponsAvailable() { return siegeWeaponsAvailable; }
    public static boolean smallShipsAvailable() { return smallShipsAvailable; }
    public static boolean fogEnabled() { return fogEnabled; }
    public static int playerVisionRadius() { return playerVisionRadius; }
    public static int recruitVisionRadius() { return recruitVisionRadius; }
    public static int cavalryVisionRadius() { return cavalryVisionRadius; }
    public static int shipVisionRadius() { return shipVisionRadius; }
}
