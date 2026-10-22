package ee.lastpagans.recruitsrtscamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CaptainEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import ee.lastpagans.recruitsrtscamera.formation.ArmyPreset;
import ee.lastpagans.recruitsrtscamera.formation.ArtilleryFormationMath;
import ee.lastpagans.recruitsrtscamera.formation.CustomFormationMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.FrontageMath;
import ee.lastpagans.recruitsrtscamera.formation.FormationRotationMath;
import ee.lastpagans.recruitsrtscamera.compat.SiegeWeaponsCompat;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import ee.lastpagans.recruitsrtscamera.math.ScreenProjection;
import ee.lastpagans.recruitsrtscamera.network.ArmyPresetPacket;
import ee.lastpagans.recruitsrtscamera.network.AttackGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.CustomFormationPacket;
import ee.lastpagans.recruitsrtscamera.network.FrontageGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.HastyMoveGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.SlowMoveGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.FormationSpacingPacket;
import ee.lastpagans.recruitsrtscamera.network.HaltGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.RouteGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.StrategicFirePacket;
import ee.lastpagans.recruitsrtscamera.network.FireControlPacket;
import ee.lastpagans.recruitsrtscamera.network.MovementPacePacket;
import ee.lastpagans.recruitsrtscamera.network.TranslateGroupsPacket;
import ee.lastpagans.recruitsrtscamera.network.SetFormationPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.network.RTSModeStatePacket;
import ee.lastpagans.recruitsrtscamera.network.RotateFormationPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.xolt.freecam.Freecam;
import net.xolt.freecam.config.FreecamConfig;
import net.xolt.freecam.util.FreeCamera;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side RTS command controller. The proven Freecam camera, screen-space selection,
 * and terrain ray remain the input foundation. Later releases layer army-layout preservation,
 * custom formations, rotation, and deployment presets on top without replacing that proven core.
 */
public final class RTSCameraController {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final double CAMERA_RADIUS = 96.0D;
    private static final double ORDER_RADIUS = 288.0D;
    private static final double MAX_RAY_DISTANCE = 192.0D;
    private static final double SELECTION_RANGE = 192.0D;
    private static final double CLICK_DRAG_THRESHOLD = 5.0D;
    private static final double FRONTAGE_DRAG_THRESHOLD = 10.0D;
    private static final double CUSTOM_SCREEN_SAMPLE_STEP = 3.0D;
    private static final double ATTACK_HITBOX_INFLATE = 0.65D;
    private static final double ATTACK_SCREEN_ASSIST_PX = 6.0D;
    private static final int MAX_CLIENT_ROUTE_POINTS = 32;
    private static final long HASTY_DOUBLE_CLICK_MS = 340L;
    private static final int HASTY_TARGET_TOLERANCE = 2;
    private static final double ROTATE_MIN_RADIUS = 1.5D;

    private static final Set<UUID> SELECTED_GROUPS = new LinkedHashSet<>();
    private static final Map<UUID, GlowState> MODIFIED_GLOW = new HashMap<>();
    private static final Map<UUID, FormationKind> LOCAL_FORMATIONS = new HashMap<>();
    private static final Set<UUID> LOCAL_CUSTOM_GROUPS = new LinkedHashSet<>();
    private static final Set<UUID> LOCAL_RUNNING_GROUPS = new LinkedHashSet<>();
    private static final Set<UUID> LOCAL_LOOSE_SPACING_GROUPS = new LinkedHashSet<>();
    private static final Set<UUID> LOCAL_FIRE_AT_WILL_GROUPS = new LinkedHashSet<>();
    private static final Map<UUID, CurrentOrderPreview> CURRENT_ORDER_PREVIEWS = new HashMap<>();
    private static FormationKind formationTool = FormationKind.MARCH;
    private static ArmyPreset armyPresetTool = ArmyPreset.BALANCED;

    private static boolean active;
    private static boolean commandCursor = true;
    private static boolean middleMousePanning;
    private static double middlePanReturnX;
    private static double middlePanReturnY;
    private static boolean draggingSelection;
    private static boolean draggingTranslation;
    private static boolean draggingFrontage;
    private static boolean draggingCustom;
    private static boolean rotatingFormation;
    private static boolean rotateKeyWasDown;
    private static boolean selectionAdditive;
    private static boolean screenWasOpen;
    private static boolean recruitsScreenWasOpen;
    private static boolean routeMode;
    private static final List<BlockPos> ROUTE_POINTS = new ArrayList<>();
    private static final Set<CapturedCommand> CAPTURED_COMMANDS = EnumSet.noneOf(CapturedCommand.class);
    private static boolean capturedRotateDown;
    private static boolean capturedCameraUpDown;
    private static boolean capturedCameraDownDown;
    private static boolean capturedShowOrdersDown;
    private static int setupTicks;

    private static Vec3 anchor = Vec3.ZERO;
    private static double cursorX;
    private static double cursorY;
    private static double selectionStartX;
    private static double selectionStartY;
    private static double selectionCurrentX;
    private static double selectionCurrentY;
    private static Vec3 translationStartGround;
    private static Vec3 translationCurrentGround;
    private static Vec3 translationFrozenDelta = Vec3.ZERO;
    private static Vec3 translationRotationPivot = Vec3.ZERO;
    private static Vec3 translationRotationStart = Vec3.ZERO;
    private static double translationRotationDegrees;
    private static double translationRotationBaseDegrees;
    private static boolean translationControlWasDown;
    private static double frontageStartX;
    private static double frontageStartY;
    private static double frontageCurrentX;
    private static double frontageCurrentY;
    private static GroundHit frontageStartGround;
    private static final List<CustomStrokePoint> CUSTOM_STROKE = new ArrayList<>();
    private static int customRanks = 1;
    private static Vec3 rotationPivot = Vec3.ZERO;
    private static Vec3 rotationStartGround = Vec3.ZERO;
    private static double rotationDegrees;
    private static float rotationBaseFacingYaw;
    private static final List<RotationPreviewPoint> ROTATION_PREVIEW = new ArrayList<>();

    private static AbstractRecruitEntity hoveredRecruit;
    private static UUID hoveredFriendlyGroupId;
    private static LivingEntity hoveredTarget;
    private static BlockPos hoveredGround;
    private static String hoveredGroundSource = "MISS";
    private static String status = "";
    private static int statusTicks;
    private static long lastTerrainOrderMillis;
    private static BlockPos lastTerrainOrderTarget;
    private static long lastEnemyOrderMillis;
    private static UUID lastEnemyOrderTarget;

    private static boolean oldAllowInteract;
    private static boolean oldFreezePlayer;
    private static boolean oldShowPlayer;
    private static boolean oldIgnoreAllCollision;
    private static boolean oldAlwaysCheckCollision;
    private static boolean oldIgnoreTransparentBlocks;
    private static boolean oldIgnoreOpenableBlocks;

    private RTSCameraController() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Apply the server's collision-resolved General/Bodyguard carry to the real local body. */
    public static void applyBodyguardCarry(double x, double y, double z, float yaw) {
        if (!active || MC.player == null || ClientCommanderState.bodyguardGroup() == null) {
            return;
        }
        // Freecam's freeze mixin blocks LocalPlayer position changes. Bodyguard mode is the
        // one Commander case where the real body must remain mobile while the camera stays detached.
        boolean freezeWasEnabled = FreecamConfig.FREEZE_PLAYER.get();
        FreecamConfig.FREEZE_PLAYER.set(false);
        MC.player.setPos(x, y, z);
        MC.player.setDeltaMovement(Vec3.ZERO);
        MC.player.fallDistance = 0.0F;
        // Keep ordinary RTS input from moving the body after the authoritative carry update.
        // We only unfreeze for the instant in which the server's synthetic formation slot is applied.
        FreecamConfig.FREEZE_PLAYER.set(freezeWasEnabled);
        if (Float.isFinite(yaw)) {
            MC.player.setYRot(yaw);
            MC.player.setYHeadRot(yaw);
            MC.player.yBodyRot = yaw;
            MC.player.yBodyRotO = yaw;
        }
        // The RTS operating area follows the real commander. The camera remains detached at
        // its own world position until it reaches the normal radius, at which point the usual
        // clamp gently brings it along instead of leaving the commander hundreds of blocks away.
        anchor = new Vec3(x, y, z);
    }

    // ---- Commander overlay bridge ---------------------------------------------------------
    // Keep these narrow: the private-server UI must drive the proven controller rather than
    // developing a second copy of RTS selection/command state.
    static boolean commanderCommandCursor() {
        return commandCursor;
    }

    static Set<UUID> commanderSelectedGroups() {
        return Set.copyOf(SELECTED_GROUPS);
    }

    static UUID commanderHoveredFriendlyGroup() {
        return hoveredFriendlyGroupId;
    }

    static List<AbstractRecruitEntity> commanderLoadedOwnedRecruits() {
        return loadedOwnedRecruits();
    }

    static void commanderDropRoutedGroups(Set<UUID> routedGroups) {
        if (routedGroups == null || routedGroups.isEmpty() || SELECTED_GROUPS.isEmpty()) return;
        boolean changed = SELECTED_GROUPS.removeIf(routedGroups::contains);
        if (!changed) return;
        applySelectionToRecruits();
        updateGlow();
        clearCurrentOrderPreviews(routedGroups);
        setStatus(SELECTED_GROUPS.isEmpty()
                ? "Routed unit left command."
                : "Routed unit removed from selection.");
    }

    static boolean commanderAttackEntity(int entityId) {
        if (!active || MC.level == null || SELECTED_GROUPS.isEmpty()) return false;
        var entity = MC.level.getEntity(entityId);
        if (!(entity instanceof LivingEntity living) || !isAttackableTarget(living)) return false;
        sendAttackOrder(living);
        return true;
    }

    static String commanderSelectedFormationText() {
        return selectedFormationText();
    }

    static String commanderContextText() {
        return currentContextLine(false);
    }

    static String commanderStatusText() {
        return status;
    }

    static boolean commanderStatusVisible() {
        return statusTicks > 0 && status != null && !status.isBlank();
    }

    static void commanderSelectGroup(UUID groupId, boolean additive) {
        if (!active || groupId == null || findOwnedClientGroup(groupId) == null) {
            return;
        }
        clearEnemyDoubleClick();
        if (!additive) {
            SELECTED_GROUPS.clear();
        }
        if (additive && SELECTED_GROUPS.contains(groupId)) {
            SELECTED_GROUPS.remove(groupId);
            setStatus("Group removed from selection.");
        } else {
            SELECTED_GROUPS.add(groupId);
            RecruitsGroup group = findOwnedClientGroup(groupId);
            setStatus(group == null ? "Group selected." : "Selected " + group.getName() + ".");
        }
        applySelectionToRecruits();
        updateGlow();
    }

    static void commanderSelectGroups(Collection<UUID> groupIds) {
        if (!active || groupIds == null) return;
        SELECTED_GROUPS.clear();
        for (UUID id : groupIds) {
            if (id != null && findOwnedClientGroup(id) != null) SELECTED_GROUPS.add(id);
        }
        applySelectionToRecruits();
        updateGlow();
        RTSCommandFacade.invalidateCards();
    }

    static void commanderStatus(String text) {
        setStatus(text);
    }

    static void commanderCycleFormation() {
        cycleFormation();
    }

    static void commanderSetFormation(FormationKind kind) {
        setFormation(kind);
    }

    static FormationKind commanderCommonFormationKind() {
        FormationKind common = null;
        for (UUID groupId : SELECTED_GROUPS) {
            FormationKind known = LOCAL_FORMATIONS.get(groupId);
            if (known == null) continue;
            if (common == null) common = known;
            else if (common != known) return null;
        }
        return common;
    }

    static boolean commanderRunningEnabled() {
        return !SELECTED_GROUPS.isEmpty() && SELECTED_GROUPS.stream().allMatch(LOCAL_RUNNING_GROUPS::contains);
    }

    static void commanderToggleMovementPace() {
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group first.");
            return;
        }
        boolean running = !commanderRunningEnabled();
        if (running) LOCAL_RUNNING_GROUPS.addAll(SELECTED_GROUPS);
        else LOCAL_RUNNING_GROUPS.removeAll(SELECTED_GROUPS);
        RTSCameraNetwork.CHANNEL.sendToServer(new MovementPacePacket(new ArrayList<>(SELECTED_GROUPS), running));
        setStatus(running ? "Run pace ordered." : "Walk pace ordered.");
    }

    static boolean commanderLooseSpacingEnabled() {
        List<UUID> applicable = SELECTED_GROUPS.stream()
                .filter(id -> !LOCAL_CUSTOM_GROUPS.contains(id))
                .toList();
        return !applicable.isEmpty() && applicable.stream().allMatch(LOCAL_LOOSE_SPACING_GROUPS::contains);
    }

    static void commanderToggleLooseSpacing() {
        List<UUID> applicable = SELECTED_GROUPS.stream()
                .filter(id -> !LOCAL_CUSTOM_GROUPS.contains(id))
                .toList();
        if (applicable.isEmpty()) {
            setStatus(SELECTED_GROUPS.isEmpty()
                    ? "Select at least one group first."
                    : "Loose spacing does not alter custom freehand formations.");
            return;
        }
        boolean enable = !applicable.stream().allMatch(LOCAL_LOOSE_SPACING_GROUPS::contains);
        if (enable) LOCAL_LOOSE_SPACING_GROUPS.addAll(applicable);
        else LOCAL_LOOSE_SPACING_GROUPS.removeAll(applicable);
        RTSCameraNetwork.CHANNEL.sendToServer(new FormationSpacingPacket(
                new ArrayList<>(applicable), enable
        ));
        setStatus(enable ? "Loose spacing ordered." : "Normal spacing ordered.");
    }

    static void commanderFireControl(List<UUID> groupIds, FireControlPacket.Mode mode) {
        sendFireControl(groupIds, mode);
    }

    static boolean commanderFireAtWillEnabled() {
        return !SELECTED_GROUPS.isEmpty() && SELECTED_GROUPS.stream().allMatch(LOCAL_FIRE_AT_WILL_GROUPS::contains);
    }

    static ScreenProjection.ProjectedPoint commanderProjectWorld(double x, double y, double z) {
        if (!active) return null;
        ScreenProjection.CameraSpec camera = cameraSpec();
        return camera == null ? null : ScreenProjection.project(camera, x, y, z);
    }

    static void commanderHaltSelection() {
        haltSelection();
    }

    static boolean commanderHasCancelablePlanning() {
        return draggingFrontage || draggingCustom || draggingTranslation
                || rotatingFormation || routeMode || !ROUTE_POINTS.isEmpty();
    }

    static void commanderCancelPlanning() {
        draggingSelection = false;
        draggingTranslation = false;
        translationStartGround = null;
        translationCurrentGround = null;
        draggingFrontage = false;
        frontageStartGround = null;
        draggingCustom = false;
        CUSTOM_STROKE.clear();
        cancelRotation();
        ROUTE_POINTS.clear();
        routeMode = false;
        lastTerrainOrderMillis = 0L;
        lastTerrainOrderTarget = null;
        setStatus("Current command planning cancelled.");
    }

    /**
     * Capture RTS command keys before Minecraft/Forge dispatches the raw key event to
     * other mods. This closes the shared-key leak where, for example, RTS Route Mode on
     * Y could still open an unrelated mod GUI also bound to Y.
     */
    public static boolean captureRawKey(int key, int scanCode, int action, int modifiers) {
        if (MC.screen != null) {
            return false;
        }

        if (active && action == GLFW.GLFW_PRESS && SelectionGroupManager.awaitingHotkey()
                && SelectionGroupManager.handleKeyPress(key, scanCode)) return true;

        if (RTSCameraKeyMappings.TOGGLE.matches(key, scanCode)) {
            if (action == GLFW.GLFW_PRESS) {
                CAPTURED_COMMANDS.add(CapturedCommand.TOGGLE);
            }
            return true;
        }
        if (!active) {
            return false;
        }

        if (action == GLFW.GLFW_PRESS && SelectionGroupManager.handleKeyPress(key, scanCode)) return true;

        if (RTSCameraKeyMappings.CAMERA_UP.matches(key, scanCode)) {
            capturedCameraUpDown = action != GLFW.GLFW_RELEASE;
            return true;
        }
        if (RTSCameraKeyMappings.CAMERA_DOWN.matches(key, scanCode)) {
            capturedCameraDownDown = action != GLFW.GLFW_RELEASE;
            return true;
        }
        if (RTSCameraKeyMappings.SHOW_PLANNED_ORDERS.matches(key, scanCode)) {
            capturedShowOrdersDown = action != GLFW.GLFW_RELEASE;
            return true;
        }

        CapturedCommand command = null;
        if (RTSCameraKeyMappings.CLEAR.matches(key, scanCode)) command = CapturedCommand.CLEAR;
        else if (RTSCameraKeyMappings.MOVEMENT_PACE.matches(key, scanCode)) command = CapturedCommand.MOVEMENT_PACE;
        else if (RTSCameraKeyMappings.SELECTION_GROUP.matches(key, scanCode)) command = CapturedCommand.SELECTION_GROUP;
        else if (RTSCameraKeyMappings.ROTATE.matches(key, scanCode)) command = CapturedCommand.ROTATE;
        else if (RTSCameraKeyMappings.ARMY_PRESET.matches(key, scanCode)) command = CapturedCommand.ARMY_PRESET;
        else if (RTSCameraKeyMappings.STRATEGIC_FIRE.matches(key, scanCode)) command = CapturedCommand.STRATEGIC_FIRE;
        else if (RTSCameraKeyMappings.HOLD_FIRE.matches(key, scanCode)) command = CapturedCommand.HOLD_FIRE;
        else if (RTSCameraKeyMappings.FIRE_AT_WILL.matches(key, scanCode)) command = CapturedCommand.FIRE_AT_WILL;
        else if (RTSCameraKeyMappings.ROUTE_MODE.matches(key, scanCode)) command = CapturedCommand.ROUTE_MODE;
        else if (RTSCameraKeyMappings.COMMIT_ROUTE.matches(key, scanCode)) command = CapturedCommand.COMMIT_ROUTE;
        else if (RTSCameraKeyMappings.RESET_ROUTE.matches(key, scanCode)) command = CapturedCommand.RESET_ROUTE;
        else if (RTSCameraKeyMappings.HALT.matches(key, scanCode)) command = CapturedCommand.HALT;

        if (command == null) {
            return false;
        }
        if (command == CapturedCommand.ROTATE) {
            capturedRotateDown = action != GLFW.GLFW_RELEASE;
        } else if (action == GLFW.GLFW_PRESS) {
            CAPTURED_COMMANDS.add(command);
        }
        return true;
    }

    private static void processCapturedCommands() {
        if (CAPTURED_COMMANDS.isEmpty()) {
            return;
        }
        Set<CapturedCommand> commands = EnumSet.copyOf(CAPTURED_COMMANDS);
        CAPTURED_COMMANDS.clear();

        if (commands.remove(CapturedCommand.TOGGLE)) {
            if (active) {
                stop("RTS Camera disabled.");
            } else {
                start();
            }
        }
        if (!active) {
            return;
        }
        if (commands.contains(CapturedCommand.CLEAR)) clearSelection();
        if (commands.contains(CapturedCommand.MOVEMENT_PACE)) commanderToggleMovementPace();
        if (commands.contains(CapturedCommand.SELECTION_GROUP)) RTSCommandFacade.makeSelectionGroup();
        if (commands.contains(CapturedCommand.ARMY_PRESET)) applyArmyPreset();
        if (commands.contains(CapturedCommand.STRATEGIC_FIRE)) designateStrategicFire();
        if (commands.contains(CapturedCommand.HOLD_FIRE)) sendFireControl(FireControlPacket.Mode.HOLD_FIRE);
        if (commands.contains(CapturedCommand.FIRE_AT_WILL)) sendFireControl(FireControlPacket.Mode.FIRE_AT_WILL);
        if (commands.contains(CapturedCommand.ROUTE_MODE)) toggleRouteMode();
        if (commands.contains(CapturedCommand.COMMIT_ROUTE)) commitRoute();
        if (commands.contains(CapturedCommand.RESET_ROUTE)) commanderCancelPlanning();
        if (commands.contains(CapturedCommand.HALT)) haltSelection();
    }

    public static void clientTick() {
        processCapturedCommands();
        while (RTSCameraKeyMappings.TOGGLE.consumeClick()) {
            if (active) {
                stop("RTS Camera disabled.");
            } else {
                start();
            }
        }

        if (!active) {
            return;
        }
        if (ClientPermissionState.known() && !ClientPermissionState.allowed()) {
            stop("RTS Camera access was revoked by the server.");
            return;
        }

        while (RTSCameraKeyMappings.CLEAR.consumeClick()) {
            clearSelection();
        }
        while (RTSCameraKeyMappings.MOVEMENT_PACE.consumeClick()) {
            commanderToggleMovementPace();
        }
        while (RTSCameraKeyMappings.SELECTION_GROUP.consumeClick()) {
            RTSCommandFacade.makeSelectionGroup();
        }
        while (RTSCameraKeyMappings.ARMY_PRESET.consumeClick()) {
            applyArmyPreset();
        }
        while (RTSCameraKeyMappings.STRATEGIC_FIRE.consumeClick()) {
            designateStrategicFire();
        }
        while (RTSCameraKeyMappings.HOLD_FIRE.consumeClick()) {
            sendFireControl(FireControlPacket.Mode.HOLD_FIRE);
        }
        while (RTSCameraKeyMappings.FIRE_AT_WILL.consumeClick()) {
            sendFireControl(FireControlPacket.Mode.FIRE_AT_WILL);
        }
        while (RTSCameraKeyMappings.ROUTE_MODE.consumeClick()) {
            toggleRouteMode();
        }
        while (RTSCameraKeyMappings.COMMIT_ROUTE.consumeClick()) {
            commitRoute();
        }
        while (RTSCameraKeyMappings.RESET_ROUTE.consumeClick()) {
            commanderCancelPlanning();
        }
        while (RTSCameraKeyMappings.HALT.consumeClick()) {
            haltSelection();
        }

        if (MC.player == null || MC.level == null) {
            stop("RTS Camera closed because the world was unloaded.");
            return;
        }
        if (!Freecam.isEnabled()) {
            stop("RTS Camera closed because Freecam was disabled.");
            return;
        }
        if (Freecam.isPlayerControlEnabled()) {
            Freecam.switchControls();
        }
        // Freecam's FREEZE_PLAYER mixin cancels client position updates for the real player.
        // Bodyguard carry is server-authoritative, so unfreeze only while a Bodyguard is
        // assigned; Freecam still owns camera/input and the player cannot walk manually.
        FreecamConfig.FREEZE_PLAYER.set(ClientCommanderState.bodyguardGroup() == null);

        FreeCamera freeCamera = Freecam.getFreeCamera();
        if (freeCamera == null) {
            return;
        }
        if (setupTicks > 0) {
            setupTicks--;
            freeCamera.setPos(anchor.x, anchor.y + 14.0D, anchor.z);
            freeCamera.setYRot(MC.player.getYRot());
            freeCamera.setXRot(65.0F);
            if (setupTicks == 0) {
                applyMouseMode();
            }
        }
        moveCameraVertically(freeCamera);
        clampCamera(freeCamera);

        boolean screenOpen = MC.screen != null;
        if (screenOpen) {
            if (draggingCustom) {
                cancelCustomGesture();
            }
            if (rotatingFormation) {
                cancelRotation();
            }
            screenWasOpen = true;
            releaseVerticalCameraInput();
            capturedShowOrdersDown = false;
            recruitsScreenWasOpen |= isRecruitsScreenOpen();
            return;
        }
        if (screenWasOpen) {
            screenWasOpen = false;
            if (recruitsScreenWasOpen) {
                syncSelectionFromRecruits();
                recruitsScreenWasOpen = false;
                setStatus("Selection updated from the Recruits group screen.");
            }
            applyMouseMode();
        }

        updateCursorPosition();

        boolean rotateDown = capturedRotateDown || RTSCameraKeyMappings.ROTATE.isDown();
        if (commandCursor && !routeMode && !draggingSelection && !draggingTranslation
                && !draggingFrontage && !draggingCustom) {
            if (rotateDown && !rotateKeyWasDown) {
                beginRotation();
            }
            if (rotateDown && rotatingFormation) {
                updateRotation();
            }
            if (!rotateDown && rotateKeyWasDown && rotatingFormation) {
                finishRotation();
            }
        } else if (rotatingFormation) {
            cancelRotation();
        }
        rotateKeyWasDown = rotateDown;

        if (draggingSelection) {
            selectionCurrentX = cursorX;
            selectionCurrentY = cursorY;
        }
        if (draggingTranslation) {
            updateTranslationDrag();
        }
        if (draggingFrontage) {
            frontageCurrentX = cursorX;
            frontageCurrentY = cursorY;
        }
        if (draggingCustom) {
            sampleCustomStrokePoint(false);
        }

        applySelectionToRecruits();
        updateGlow();
        updateHover();
        if (statusTicks > 0) {
            statusTicks--;
        }
    }

    private static void start() {
        if (MC.player == null || MC.level == null) {
            return;
        }
        if (!ClientPermissionState.known()) {
            message("Waiting for RTS Camera permission data from the server. Try again in a moment.");
            return;
        }
        if (!ClientPermissionState.allowed()) {
            message(ClientPermissionState.whitelistMode()
                    ? "RTS Camera is restricted to whitelisted players on this server."
                    : "This server does not permit you to use RTS Camera.");
            return;
        }
        if (Freecam.isEnabled()) {
            message("Disable ordinary Freecam before entering RTS Camera.");
            return;
        }

        oldAllowInteract = FreecamConfig.ALLOW_INTERACT.get();
        oldFreezePlayer = FreecamConfig.FREEZE_PLAYER.get();
        oldShowPlayer = FreecamConfig.SHOW_PLAYER.get();
        oldIgnoreAllCollision = FreecamConfig.IGNORE_ALL_COLLISION.get();
        oldAlwaysCheckCollision = FreecamConfig.ALWAYS_CHECK_COLLISION.get();
        oldIgnoreTransparentBlocks = FreecamConfig.IGNORE_TRANSPARENT_BLOCKS.get();
        oldIgnoreOpenableBlocks = FreecamConfig.IGNORE_OPENABLE_BLOCKS.get();
        FreecamConfig.ALLOW_INTERACT.set(false);
        FreecamConfig.FREEZE_PLAYER.set(true);
        FreecamConfig.SHOW_PLAYER.set(true);
        // RTS Camera is a battlefield viewpoint, not spectator noclip. Force Freecam's
        // strict collision policy while RTS owns it and restore the user's settings later.
        FreecamConfig.IGNORE_ALL_COLLISION.set(false);
        FreecamConfig.ALWAYS_CHECK_COLLISION.set(true);
        FreecamConfig.IGNORE_TRANSPARENT_BLOCKS.set(false);
        FreecamConfig.IGNORE_OPENABLE_BLOCKS.set(false);

        anchor = MC.player.position();
        ClientManager.updateGroups();
        syncSelectionFromRecruits();
        clearGlow();
        active = true;
        RTSCameraNetwork.CHANNEL.sendToServer(new RTSModeStatePacket(true));
        commandCursor = true;
        middleMousePanning = false;
        CommanderOverlay.reset();
        draggingSelection = false;
        draggingTranslation = false;
        translationStartGround = null;
        translationCurrentGround = null;
        draggingFrontage = false;
        draggingCustom = false;
        rotatingFormation = false;
        rotateKeyWasDown = false;
        capturedRotateDown = false;
        releaseVerticalCameraInput();
        capturedShowOrdersDown = false;
        CAPTURED_COMMANDS.clear();
        rotationDegrees = 0.0D;
        ROTATION_PREVIEW.clear();
        CUSTOM_STROKE.clear();
        ROUTE_POINTS.clear();
        routeMode = false;
        frontageStartGround = null;
        screenWasOpen = false;
        recruitsScreenWasOpen = false;
        setupTicks = 3;
        status = "Drag over recruits to select their groups.";
        statusTicks = 120;
        lastTerrainOrderMillis = 0L;
        lastTerrainOrderTarget = null;

        Freecam.toggle();
        if (Freecam.isPlayerControlEnabled()) {
            Freecam.switchControls();
        }
        updateCursorPosition();
        message("RTS Camera enabled. WASD moves; E/Q move up/down; wheel down rises and wheel up lowers; hold middle mouse to look.");
    }

    public static void stop(String reason) {
        clearEnemyDoubleClick();
        if (!active) {
            return;
        }
        applySelectionToRecruits();
        RTSCameraNetwork.CHANNEL.sendToServer(new RTSModeStatePacket(false));
        active = false;
        middleMousePanning = false;
        CommanderOverlay.reset();
        draggingSelection = false;
        draggingTranslation = false;
        translationStartGround = null;
        translationCurrentGround = null;
        draggingFrontage = false;
        draggingCustom = false;
        rotatingFormation = false;
        rotateKeyWasDown = false;
        capturedRotateDown = false;
        releaseVerticalCameraInput();
        capturedShowOrdersDown = false;
        CAPTURED_COMMANDS.clear();
        rotationDegrees = 0.0D;
        ROTATION_PREVIEW.clear();
        CUSTOM_STROKE.clear();
        frontageStartGround = null;
        hoveredRecruit = null;
        hoveredFriendlyGroupId = null;
        hoveredTarget = null;
        hoveredGround = null;
        lastTerrainOrderMillis = 0L;
        lastTerrainOrderTarget = null;
        clearGlow();

        MC.mouseHandler.grabMouse();
        if (Freecam.isEnabled()) {
            Freecam.toggle();
        }
        FreecamConfig.ALLOW_INTERACT.set(oldAllowInteract);
        FreecamConfig.FREEZE_PLAYER.set(oldFreezePlayer);
        FreecamConfig.SHOW_PLAYER.set(oldShowPlayer);
        FreecamConfig.IGNORE_ALL_COLLISION.set(oldIgnoreAllCollision);
        FreecamConfig.ALWAYS_CHECK_COLLISION.set(oldAlwaysCheckCollision);
        FreecamConfig.IGNORE_TRANSPARENT_BLOCKS.set(oldIgnoreTransparentBlocks);
        FreecamConfig.IGNORE_OPENABLE_BLOCKS.set(oldIgnoreOpenableBlocks);
        ROUTE_POINTS.clear();
        routeMode = false;
        message(reason);
    }

    private static void applyMouseMode() {
        if (!active || MC.screen != null) {
            return;
        }
        if (!middleMousePanning) {
            MC.mouseHandler.releaseMouse();
            updateCursorPosition();
        } else {
            MC.mouseHandler.grabMouse();
        }
    }

    private static void clampCamera(FreeCamera camera) {
        double dx = camera.getX() - anchor.x;
        double dz = camera.getZ() - anchor.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= CAMERA_RADIUS || horizontal < 0.001D) {
            return;
        }
        double scale = CAMERA_RADIUS / horizontal;
        camera.setPos(
                anchor.x + dx * scale,
                camera.getY(),
                anchor.z + dz * scale
        );
    }

    private static void moveCameraVertically(FreeCamera camera) {
        if (camera == null || MC.screen != null) return;
        boolean up = physicalKeyState(RTSCameraKeyMappings.CAMERA_UP);
        boolean down = physicalKeyState(RTSCameraKeyMappings.CAMERA_DOWN);
        if (!up) {
            capturedCameraUpDown = false;
            RTSCameraKeyMappings.CAMERA_UP.setDown(false);
        }
        if (!down) {
            capturedCameraDownDown = false;
            RTSCameraKeyMappings.CAMERA_DOWN.setDown(false);
        }
        if (up == down) return;
        double speed = Math.max(0.05D, FreecamConfig.VERTICAL_SPEED.get());
        camera.move(MoverType.SELF, new Vec3(0.0D, up ? speed : -speed, 0.0D));
    }

    private static boolean physicalKeyState(net.minecraft.client.KeyMapping mapping) {
        if (mapping == null) return false;
        InputConstants.Key key = mapping.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return mapping.isDown();
        return InputConstants.isKeyDown(MC.getWindow().getWindow(), key.getValue());
    }

    private static void releaseVerticalCameraInput() {
        capturedCameraUpDown = false;
        capturedCameraDownDown = false;
        RTSCameraKeyMappings.CAMERA_UP.setDown(false);
        RTSCameraKeyMappings.CAMERA_DOWN.setDown(false);
    }

    public static boolean handleMouseButton(int button, int action, int modifiers) {
        if (!active || MC.screen != null) {
            return false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action == GLFW.GLFW_PRESS && !middleMousePanning) {
                middlePanReturnX = MC.mouseHandler.xpos();
                middlePanReturnY = MC.mouseHandler.ypos();
                middleMousePanning = true;
                draggingSelection = false;
                draggingTranslation = false;
                translationStartGround = null;
                translationCurrentGround = null;
                draggingFrontage = false;
                draggingCustom = false;
                MC.mouseHandler.grabMouse();
            } else if (action == GLFW.GLFW_RELEASE && middleMousePanning) {
                middleMousePanning = false;
                MC.mouseHandler.releaseMouse();
                GLFW.glfwSetCursorPos(MC.getWindow().getWindow(), middlePanReturnX, middlePanReturnY);
                updateCursorPosition();
            }
            return true;
        }
        if (middleMousePanning) return true;
        updateCursorPosition();

        // The Total War command layer owns clicks inside its panel. This check happens
        // before any battlefield gesture starts, preventing unit-card clicks from also
        // becoming box selections, moves or attacks behind the HUD.
        if (CommanderOverlay.handleMouseButton(button, action, modifiers)) {
            draggingSelection = false;
            draggingTranslation = false;
            translationStartGround = null;
            translationCurrentGround = null;
            draggingFrontage = false;
            draggingCustom = false;
            return true;
        }

        if (rotatingFormation) {
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS) {
                if ((modifiers & GLFW.GLFW_MOD_ALT) != 0 && beginTranslationDrag()) {
                    return true;
                }
                draggingSelection = true;
                selectionAdditive = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
                selectionStartX = selectionCurrentX = cursorX;
                selectionStartY = selectionCurrentY = cursorY;
            } else if (action == GLFW.GLFW_RELEASE && draggingTranslation) {
                finishTranslationDrag();
            } else if (action == GLFW.GLFW_RELEASE && draggingSelection) {
                draggingSelection = false;
                selectionCurrentX = cursorX;
                selectionCurrentY = cursorY;
                double dragDistance = Math.hypot(
                        selectionCurrentX - selectionStartX,
                        selectionCurrentY - selectionStartY
                );
                if (dragDistance < CLICK_DRAG_THRESHOLD) {
                    selectClickedGroup(selectionAdditive);
                } else {
                    selectGroupsInBox(selectionAdditive);
                }
            }
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (routeMode) {
                if (action == GLFW.GLFW_RELEASE) {
                    addRouteWaypoint();
                }
                return true;
            }
            if (action == GLFW.GLFW_PRESS) {
                boolean customModifier = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
                if (customModifier) {
                    draggingFrontage = false;
                    frontageStartGround = null;
                    draggingCustom = true;
                    CUSTOM_STROKE.clear();
                    sampleCustomStrokePoint(true);
                } else {
                    draggingCustom = false;
                    CUSTOM_STROKE.clear();
                    draggingFrontage = true;
                    frontageStartX = frontageCurrentX = cursorX;
                    frontageStartY = frontageCurrentY = cursorY;
                    frontageStartGround = raycastGroundHit(cursorX, cursorY);
                }
            } else if (action == GLFW.GLFW_RELEASE && draggingCustom) {
                sampleCustomStrokePoint(true);
                draggingCustom = false;
                issueCustomFormationOrder();
                CUSTOM_STROKE.clear();
            } else if (action == GLFW.GLFW_RELEASE && draggingFrontage) {
                draggingFrontage = false;
                frontageCurrentX = cursorX;
                frontageCurrentY = cursorY;
                double dragDistance = Math.hypot(
                        frontageCurrentX - frontageStartX,
                        frontageCurrentY - frontageStartY
                );
                if (dragDistance < FRONTAGE_DRAG_THRESHOLD) {
                    issueContextOrder();
                } else {
                    issueFrontageOrder();
                }
                frontageStartGround = null;
            }
            return true;
        }

        return false;
    }

    private static boolean beginTranslationDrag() {
        if (hoveredFriendlyGroupId != null && !SELECTED_GROUPS.contains(hoveredFriendlyGroupId)) {
            commanderSelectGroup(hoveredFriendlyGroupId, false);
        }
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select a formation before Alt-dragging it.");
            return false;
        }
        GroundHit hit = raycastGroundHit(cursorX, cursorY);
        if (hit == null) {
            setStatus("Alt-drag must begin over valid terrain.");
            return false;
        }
        draggingSelection = false;
        draggingTranslation = true;
        translationStartGround = hit.location();
        translationCurrentGround = hit.location();
        translationFrozenDelta = Vec3.ZERO;
        translationRotationPivot = Vec3.ZERO;
        translationRotationStart = Vec3.ZERO;
        translationRotationDegrees = 0.0D;
        translationRotationBaseDegrees = 0.0D;
        translationControlWasDown = false;
        return true;
    }

    private static void updateTranslationDrag() {
        GroundHit hit = raycastGroundHit(cursorX, cursorY);
        if (hit == null || translationStartGround == null) return;
        Vec3 current = hit.location();
        boolean controlDown = GLFW.glfwGetKey(MC.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(MC.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        if (controlDown) {
            if (!translationControlWasDown) {
                translationFrozenDelta = (translationCurrentGround == null ? current : translationCurrentGround)
                        .subtract(translationStartGround);
                Vec3 armyCenter = selectedArmyCenter();
                translationRotationPivot = armyCenter == null
                        ? current : armyCenter.add(translationFrozenDelta);
                translationRotationStart = current;
                translationRotationBaseDegrees = translationRotationDegrees;
            }
            double ax = translationRotationStart.x - translationRotationPivot.x;
            double az = translationRotationStart.z - translationRotationPivot.z;
            double bx = current.x - translationRotationPivot.x;
            double bz = current.z - translationRotationPivot.z;
            if (Math.hypot(ax, az) >= ROTATE_MIN_RADIUS && Math.hypot(bx, bz) >= ROTATE_MIN_RADIUS) {
                translationRotationDegrees = translationRotationBaseDegrees
                        + FormationRotationMath.signedAngle(ax, az, bx, bz);
            }
            translationCurrentGround = translationStartGround.add(translationFrozenDelta);
        } else {
            if (translationControlWasDown) {
                // Rebase the translation anchor so releasing Ctrl preserves both the
                // accumulated rotation and the current move delta without a cursor jump.
                translationStartGround = current.subtract(translationFrozenDelta);
            }
            translationCurrentGround = current;
        }
        translationControlWasDown = controlDown;
    }

    private static void finishTranslationDrag() {
        draggingTranslation = false;
        updateTranslationDrag();
        if (translationStartGround == null || translationCurrentGround == null) return;
        Vec3 delta = translationCurrentGround.subtract(translationStartGround);
        boolean moved = delta.x * delta.x + delta.z * delta.z >= 0.20D * 0.20D;
        boolean rotated = Math.abs(translationRotationDegrees) >= 1.0D;
        if (!moved && !rotated) {
            translationStartGround = null;
            translationCurrentGround = null;
            return;
        }
        LOCAL_RUNNING_GROUPS.removeAll(SELECTED_GROUPS);
        if (moved) {
            RTSCameraNetwork.CHANNEL.sendToServer(new TranslateGroupsPacket(new ArrayList<>(SELECTED_GROUPS), delta));
        }
        if (rotated) {
            RTSCameraNetwork.CHANNEL.sendToServer(new RotateFormationPacket(
                    new ArrayList<>(SELECTED_GROUPS), (float) translationRotationDegrees));
        }
        rememberTranslationOrderPreview(delta, translationRotationDegrees);
        setStatus("Formation transform ordered for " + SELECTED_GROUPS.size() + " group(s)."
                + (rotated ? " · " + Math.round(translationRotationDegrees) + "°" : ""));
        translationStartGround = null;
        translationCurrentGround = null;
        translationControlWasDown = false;
        translationRotationDegrees = 0.0D;
    }

    public static boolean handleMouseScroll(double scrollDelta) {
        if (!active || MC.screen != null) {
            return false;
        }
        if (CommanderOverlay.handleMouseScroll(scrollDelta)) {
            return true;
        }
        if (draggingCustom && Math.abs(scrollDelta) > 0.001D) {
            int direction = scrollDelta > 0.0D ? 1 : -1;
            customRanks = Math.max(1, Math.min(CustomFormationMath.MAX_RANKS, customRanks + direction));
            status = "Custom formation depth: " + customRanks + (customRanks == 1 ? " rank" : " ranks");
            statusTicks = 40;
            return true;
        }
        FreeCamera camera = Freecam.getFreeCamera();
        if (camera != null && Math.abs(scrollDelta) > 0.001D) {
            double step = Math.max(0.75D, FreecamConfig.VERTICAL_SPEED.get() * 4.0D);
            camera.move(MoverType.SELF, new Vec3(0.0D, -scrollDelta * step, 0.0D));
            clampCamera(camera);
        }
        // Command mode owns the wheel so the hotbar and unrelated mod bindings cannot
        // change underneath an RTS order. Screens are excluded above.
        return true;
    }

    private static void cancelCustomGesture() {
        draggingCustom = false;
        CUSTOM_STROKE.clear();
    }

    private static void toggleRouteMode() {
        if (!commandCursor) {
            setStatus("Switch to command cursor mode before planning a route.");
            return;
        }
        routeMode = !routeMode;
        draggingFrontage = false;
        draggingCustom = false;
        frontageStartGround = null;
        CUSTOM_STROKE.clear();
        if (routeMode) {
            setStatus("Route Mode: RMB adds waypoints · Enter commits · Backspace cancels planning · Y closes.");
        } else {
            ROUTE_POINTS.clear();
            setStatus("Route Mode closed; uncommitted route cleared.");
        }
    }

    private static void addRouteWaypoint() {
        applySelectionToRecruits();
        if (!routeMode) {
            return;
        }
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before planning a route.");
            return;
        }
        if (ROUTE_POINTS.size() >= MAX_CLIENT_ROUTE_POINTS) {
            setStatus("Route already has the client maximum of " + MAX_CLIENT_ROUTE_POINTS + " waypoints.");
            return;
        }
        GroundHit hit = raycastGroundHit(cursorX, cursorY);
        if (hit == null) {
            setStatus("Route waypoint needs valid terrain under the cursor.");
            return;
        }
        BlockPos point = hit.pos().immutable();
        if (Vec3.atCenterOf(point).distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            setStatus("That route waypoint is outside the tactical radius.");
            return;
        }
        if (!ROUTE_POINTS.isEmpty()) {
            BlockPos previous = ROUTE_POINTS.get(ROUTE_POINTS.size() - 1);
            double dx = previous.getX() - point.getX();
            double dz = previous.getZ() - point.getZ();
            if (dx * dx + dz * dz < 2.25D) {
                return;
            }
        }
        ROUTE_POINTS.add(point);
        setStatus("Route waypoint " + ROUTE_POINTS.size() + " added · Enter commits · Backspace cancels planning.");
    }

    private static void commitRoute() {
        if (!routeMode) {
            return;
        }
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before committing a route.");
            return;
        }
        if (ROUTE_POINTS.isEmpty()) {
            setStatus("Add at least one waypoint with RMB first.");
            return;
        }
        List<BlockPos> committedPoints = new ArrayList<>(ROUTE_POINTS);
        RTSCameraNetwork.CHANNEL.sendToServer(new RouteGroupsPacket(
                new ArrayList<>(SELECTED_GROUPS),
                committedPoints
        ));
        rememberRouteOrderPreview(committedPoints);
        int stages = ROUTE_POINTS.size();
        ROUTE_POINTS.clear();
        routeMode = false;
        setStatus("Route committed: " + stages + " stage(s). Press B at any time to halt.");
    }

    private static void resetPlannedRoute() {
        if (!routeMode) {
            return;
        }
        ROUTE_POINTS.clear();
        setStatus("Planned route reset. Route Mode remains active.");
    }

    private static void haltSelection() {
        clearEnemyDoubleClick();
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group to halt.");
            return;
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new HaltGroupsPacket(new ArrayList<>(SELECTED_GROUPS)));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        setStatus("HALT order sent to " + SELECTED_GROUPS.size() + " group(s). Formation memory preserved.");
    }

    private static void applyArmyPreset() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before applying an army preset.");
            return;
        }
        ArmyPreset applied = armyPresetTool;
        RTSCameraNetwork.CHANNEL.sendToServer(new ArmyPresetPacket(
                new ArrayList<>(SELECTED_GROUPS),
                applied
        ));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        armyPresetTool = armyPresetTool.next();
        setStatus("Army preset: " + applied.displayName() + " — " + applied.description()
                + " · next P: " + armyPresetTool.displayName());
    }

    static void commanderApplyArmyPreset(ArmyPreset preset) {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty() || preset == null) {
            setStatus("Select at least one group before applying an army preset.");
            return;
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new ArmyPresetPacket(new ArrayList<>(SELECTED_GROUPS), preset));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        armyPresetTool = preset.next();
        setStatus("Army preset: " + preset.displayName() + " — " + preset.description());
    }

    private static void designateStrategicFire() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one ranged/artillery group before designating fire.");
            return;
        }
        GroundHit hit = raycastGroundHit(cursorX, cursorY);
        if (hit == null) {
            setStatus("Strategic fire needs valid terrain under the cursor.");
            return;
        }
        BlockPos target = hit.pos();
        if (Vec3.atCenterOf(target).distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            setStatus("That bombardment point is outside the tactical radius.");
            return;
        }
        if (!isClientVisible(Vec3.atCenterOf(target))) {
            setStatus("That bombardment point is hidden by fog of war.");
            return;
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new StrategicFirePacket(
                new ArrayList<>(SELECTED_GROUPS), target
        ));
        setStatus("Strategic fire designated at " + target.getX() + ", " + target.getY() + ", " + target.getZ() + ".");
    }

    private static void sendFireControl(FireControlPacket.Mode mode) {
        sendFireControl(new ArrayList<>(SELECTED_GROUPS), mode);
    }

    private static void sendFireControl(Collection<UUID> groupIds, FireControlPacket.Mode mode) {
        applySelectionToRecruits();
        List<UUID> targets = groupIds == null ? List.of() : groupIds.stream()
                .filter(SELECTED_GROUPS::contains).distinct().toList();
        if (targets.isEmpty()) {
            setStatus("Select at least one ranged/artillery group first.");
            return;
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new FireControlPacket(
                targets, mode
        ));
        if (mode == FireControlPacket.Mode.FIRE_AT_WILL) {
            LOCAL_FIRE_AT_WILL_GROUPS.addAll(targets);
        } else {
            LOCAL_FIRE_AT_WILL_GROUPS.removeAll(targets);
        }
        setStatus(mode == FireControlPacket.Mode.HOLD_FIRE ? "Hold fire order sent." : "Fire at will order sent.");
    }

    private static void beginRotation() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before rotating.");
            return;
        }
        Vec3 pivot = selectedArmyCenter();
        GroundHit ground = raycastGroundHit(cursorX, cursorY);
        if (pivot == null || ground == null) {
            setStatus("Point at terrain beside the formation, then hold Z to rotate.");
            return;
        }
        Vec3 start = ground.location();
        if (Math.hypot(start.x - pivot.x, start.z - pivot.z) < ROTATE_MIN_RADIUS) {
            setStatus("Move the cursor farther from the formation centre before rotating.");
            return;
        }

        rotationPivot = pivot;
        rotationStartGround = start;
        rotationBaseFacingYaw = selectedArmyFacing();
        rotationDegrees = 0.0D;
        ROTATION_PREVIEW.clear();
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId != null && SELECTED_GROUPS.contains(groupId)) {
                ROTATION_PREVIEW.add(new RotationPreviewPoint(recruit.position()));
            }
        }
        rotatingFormation = !ROTATION_PREVIEW.isEmpty();
        if (rotatingFormation) {
            status = "Rotate formation · release Z to commit";
            statusTicks = 40;
        }
    }

    private static void updateRotation() {
        if (!rotatingFormation) {
            return;
        }
        GroundHit ground = raycastGroundHit(cursorX, cursorY);
        if (ground == null) {
            return;
        }
        Vec3 current = ground.location();
        double ax = rotationStartGround.x - rotationPivot.x;
        double az = rotationStartGround.z - rotationPivot.z;
        double bx = current.x - rotationPivot.x;
        double bz = current.z - rotationPivot.z;
        if (Math.hypot(bx, bz) < ROTATE_MIN_RADIUS) {
            return;
        }
        rotationDegrees = FormationRotationMath.signedAngle(ax, az, bx, bz);
    }

    private static void finishRotation() {
        if (!rotatingFormation) {
            return;
        }
        double delta = rotationDegrees;
        rotatingFormation = false;
        ROTATION_PREVIEW.clear();
        rotationDegrees = 0.0D;
        if (Math.abs(delta) < 1.0D) {
            setStatus("Rotation cancelled (less than 1°).");
            return;
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new RotateFormationPacket(
                new ArrayList<>(SELECTED_GROUPS),
                (float) delta
        ));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        setStatus("Rotate order sent: " + Math.round(delta) + "° · " + SELECTED_GROUPS.size() + " group(s).");
    }

    private static void cancelRotation() {
        rotatingFormation = false;
        rotationDegrees = 0.0D;
        ROTATION_PREVIEW.clear();
    }

    /** Equal-weight group centre, matching the server-side army stencil pivot. */
    private static Vec3 selectedArmyCenter() {
        Map<UUID, double[]> sums = new LinkedHashMap<>();
        for (UUID groupId : SELECTED_GROUPS) {
            sums.put(groupId, new double[4]);
        }
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            double[] sum = sums.get(recruit.getGroup());
            if (sum == null) {
                continue;
            }
            sum[0] += recruit.getX();
            sum[1] += recruit.getY();
            sum[2] += recruit.getZ();
            sum[3] += 1.0D;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int groups = 0;
        for (double[] sum : sums.values()) {
            if (sum[3] <= 0.0D) {
                continue;
            }
            x += sum[0] / sum[3];
            y += sum[1] / sum[3];
            z += sum[2] / sum[3];
            groups++;
        }
        return groups == 0 ? null : new Vec3(x / groups, y / groups, z / groups);
    }

    private static float selectedArmyFacing() {
        double sin = 0.0D;
        double cos = 0.0D;
        int count = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId == null || !SELECTED_GROUPS.contains(groupId)) {
                continue;
            }
            float yaw = recruit.isInFormation ? recruit.ownerRot : recruit.getYRot();
            if (!Float.isFinite(yaw)) {
                continue;
            }
            double radians = Math.toRadians(yaw);
            sin += Math.sin(radians);
            cos += Math.cos(radians);
            count++;
        }
        if (count == 0 || Math.abs(sin) + Math.abs(cos) < 1.0E-7D) {
            Camera camera = MC.gameRenderer == null ? null : MC.gameRenderer.getMainCamera();
            return camera == null ? 0.0F : camera.getYRot();
        }
        return FormationRotationMath.normalizeYaw((float) Math.toDegrees(Math.atan2(sin, cos)));
    }

    private static void sampleCustomStrokePoint(boolean force) {
        if (!draggingCustom || MC.level == null || CUSTOM_STROKE.size() >= CustomFormationMath.MAX_STROKE_POINTS) {
            return;
        }
        updateCursorPosition();
        GroundHit hit = raycastGroundHit(cursorX, cursorY);
        if (hit == null) {
            return;
        }
        if (!CUSTOM_STROKE.isEmpty()) {
            CustomStrokePoint last = CUSTOM_STROKE.get(CUSTOM_STROKE.size() - 1);
            double screenDistance = Math.hypot(cursorX - last.screenX(), cursorY - last.screenY());
            double worldDistance = horizontalDistance(hit.location(), last.world());
            if (!force && screenDistance < CUSTOM_SCREEN_SAMPLE_STEP
                    && worldDistance < CustomFormationMath.MIN_POINT_STEP) {
                return;
            }
            if (force && screenDistance < 0.5D && worldDistance < 0.05D) {
                return;
            }
        }
        CUSTOM_STROKE.add(new CustomStrokePoint(cursorX, cursorY, hit.location()));
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static void issueCustomFormationOrder() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before drawing a custom formation.");
            return;
        }
        if (CUSTOM_STROKE.size() < 2) {
            setStatus("Custom formation needs a longer terrain stroke.");
            return;
        }

        List<Vec3> worldPoints = new ArrayList<>(CUSTOM_STROKE.size());
        List<CustomFormationMath.Point> mathPoints = new ArrayList<>(CUSTOM_STROKE.size());
        for (CustomStrokePoint point : CUSTOM_STROKE) {
            worldPoints.add(point.world());
            mathPoints.add(new CustomFormationMath.Point(point.world().x, point.world().z));
        }
        mathPoints = CustomFormationMath.sanitize(mathPoints);
        double length = CustomFormationMath.length(mathPoints);
        if (mathPoints.size() < 2 || length < CustomFormationMath.MIN_STROKE_LENGTH) {
            setStatus("Custom formation is too short; draw at least 2 blocks across terrain.");
            return;
        }
        if (length > CustomFormationMath.MAX_STROKE_LENGTH) {
            setStatus("Custom formation is too long; maximum is 180 blocks of drawn stroke.");
            return;
        }

        RTSCameraNetwork.CHANNEL.sendToServer(new CustomFormationPacket(
                new ArrayList<>(SELECTED_GROUPS),
                worldPoints,
                customRanks
        ));
        for (UUID groupId : SELECTED_GROUPS) {
            LOCAL_CUSTOM_GROUPS.add(groupId);
            LOCAL_FORMATIONS.remove(groupId);
        }
        setStatus("Custom formation sent: " + SELECTED_GROUPS.size() + " group(s) · "
                + customRanks + " rank(s) · "
                + String.format(java.util.Locale.ROOT, "%.1f", length) + " blocks.");
    }

    public static void clearSelection() {
        clearEnemyDoubleClick();
        if (!active) {
            return;
        }
        SELECTED_GROUPS.clear();
        applySelectionToRecruits();
        updateGlow();
        setStatus("Selection cleared.");
    }

    private static void selectClickedGroup(boolean additive) {
        clearEnemyDoubleClick();
        UUID groupId = RTSClientConfig.usesGroundMarkers()
                ? markerGroupAtCursor(cursorX, cursorY)
                : null;
        AbstractRecruitEntity recruit = groupId == null ? raycastRecruit(cursorX, cursorY) : null;
        if (groupId == null && recruit != null) {
            groupId = validOwnedGroupId(recruit);
        }
        if (groupId == null) {
            if (!additive) {
                SELECTED_GROUPS.clear();
                applySelectionToRecruits();
                updateGlow();
            }
            setStatus("No owned recruit or selection marker under the cursor.");
            return;
        }

        if (!additive) {
            SELECTED_GROUPS.clear();
        }
        if (additive && SELECTED_GROUPS.contains(groupId)) {
            SELECTED_GROUPS.remove(groupId);
            setStatus("Group removed from selection.");
        } else {
            SELECTED_GROUPS.add(groupId);
            setStatus("Selected " + SELECTED_GROUPS.size() + " group(s). ");
        }
        applySelectionToRecruits();
        updateGlow();
    }

    private static void selectGroupsInBox(boolean additive) {
        clearEnemyDoubleClick();
        Set<UUID> inside = groupsInsideCurrentBox();
        if (!additive) {
            SELECTED_GROUPS.clear();
        }
        SELECTED_GROUPS.addAll(inside);
        applySelectionToRecruits();
        updateGlow();
        if (inside.isEmpty()) {
            setStatus("No owned recruit bodies intersected the selection box.");
        } else {
            setStatus("Box-selected " + inside.size() + " group(s); "
                    + SELECTED_GROUPS.size() + " total selected.");
        }
    }

    private static Set<UUID> groupsInsideCurrentBox() {
        double minX = Math.min(selectionStartX, selectionCurrentX);
        double maxX = Math.max(selectionStartX, selectionCurrentX);
        double minY = Math.min(selectionStartY, selectionCurrentY);
        double maxY = Math.max(selectionStartY, selectionCurrentY);
        Set<UUID> inside = new LinkedHashSet<>();
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) {
            return inside;
        }

        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = validOwnedGroupId(recruit);
            if (groupId == null || inside.contains(groupId)) {
                continue;
            }
            AABB entityBox = recruit.getBoundingBox();
            ScreenProjection.ProjectedBounds bounds = ScreenProjection.projectBox(
                    camera,
                    entityBox.minX, entityBox.minY, entityBox.minZ,
                    entityBox.maxX, entityBox.maxY, entityBox.maxZ
            );
            if (bounds != null && ScreenProjection.rectanglesIntersect(
                    minX, minY, maxX, maxY,
                    bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY()
            )) {
                inside.add(groupId);
            }
        }
        return inside;
    }

    private static void cycleFormation() {
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before changing formation.");
            return;
        }

        FormationKind common = null;
        boolean mixed = false;
        boolean unknown = false;
        for (UUID groupId : SELECTED_GROUPS) {
            FormationKind known = LOCAL_FORMATIONS.get(groupId);
            if (known == null) {
                unknown = true;
                continue;
            }
            if (common == null) {
                common = known;
            } else if (common != known) {
                mixed = true;
                break;
            }
        }

        FormationKind base;
        if (!mixed && !unknown && common != null) {
            base = common;
        } else if (common == null) {
            // A freshly selected group has no RTS Camera preference yet. Start from
            // March so the first G press predictably selects Line.
            base = FormationKind.MARCH;
        } else {
            base = formationTool;
        }
        setFormation(base.next());
    }

    private static void setFormation(FormationKind kind) {
        if (kind == null) {
            return;
        }
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group before changing formation.");
            return;
        }
        formationTool = kind;
        for (UUID groupId : SELECTED_GROUPS) {
            LOCAL_FORMATIONS.put(groupId, kind);
            LOCAL_CUSTOM_GROUPS.remove(groupId);
        }
        RTSCameraNetwork.CHANNEL.sendToServer(new SetFormationPacket(
                new ArrayList<>(SELECTED_GROUPS),
                kind
        ));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        setStatus("Formation set to " + kind.displayName() + " for " + SELECTED_GROUPS.size() + " group(s).");
    }

    private static void issueContextOrder() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group first.");
            return;
        }

        LivingEntity target = raycastAttackTarget(cursorX, cursorY);
        if (isAttackableTarget(target)) {
            sendAttackOrder(target);
            return;
        }
        clearEnemyDoubleClick();
        GroundHit groundHit = raycastGroundHit(cursorX, cursorY);
        BlockPos targetPos = groundHit == null ? null : groundHit.pos();
        if (targetPos == null) {
            setStatus("No valid terrain under the cursor (terrain ray: MISS).");
            return;
        }
        if (Vec3.atCenterOf(targetPos).distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            setStatus("That destination is outside the tactical radius.");
            return;
        }
        long now = System.currentTimeMillis();
        boolean hasty = isHastyDoubleClick(targetPos, now);
        if (hasty) {
            LOCAL_RUNNING_GROUPS.addAll(SELECTED_GROUPS);
            RTSCameraNetwork.CHANNEL.sendToServer(new HastyMoveGroupsPacket(
                    new ArrayList<>(SELECTED_GROUPS),
                    targetPos
            ));
            rememberMoveOrderPreview(new Vec3(targetPos.getX() + 0.5D, groundHit.location().y, targetPos.getZ() + 0.5D));
            lastTerrainOrderMillis = 0L;
            lastTerrainOrderTarget = null;
            setStatus("Hasty move sent to " + SELECTED_GROUPS.size() + " group(s): "
                    + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ());
        } else {
            boolean running = commanderRunningEnabled();
            if (!running) {
                RTSCameraNetwork.CHANNEL.sendToServer(new SlowMoveGroupsPacket(
                        new ArrayList<>(SELECTED_GROUPS),
                        targetPos
                ));
            } else {
                RTSCameraNetwork.CHANNEL.sendToServer(new HastyMoveGroupsPacket(
                        new ArrayList<>(SELECTED_GROUPS),
                        targetPos
                ));
            }
            rememberMoveOrderPreview(new Vec3(targetPos.getX() + 0.5D, groundHit.location().y, targetPos.getZ() + 0.5D));
            lastTerrainOrderMillis = now;
            lastTerrainOrderTarget = targetPos;
            setStatus((running ? "Run" : "Walk") + " order sent to " + SELECTED_GROUPS.size() + " group(s): "
                    + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()
                    + " [" + groundHit.source() + "] · double-RMB for hasty move");
        }
    }

    private static boolean isHastyDoubleClick(BlockPos targetPos, long now) {
        if (lastTerrainOrderTarget == null || lastTerrainOrderMillis <= 0L) {
            return false;
        }
        if (now - lastTerrainOrderMillis > HASTY_DOUBLE_CLICK_MS) {
            return false;
        }
        return Math.abs(targetPos.getX() - lastTerrainOrderTarget.getX()) <= HASTY_TARGET_TOLERANCE
                && Math.abs(targetPos.getY() - lastTerrainOrderTarget.getY()) <= HASTY_TARGET_TOLERANCE
                && Math.abs(targetPos.getZ() - lastTerrainOrderTarget.getZ()) <= HASTY_TARGET_TOLERANCE;
    }

    private static void sendAttackOrder(LivingEntity target) {
        long now = System.currentTimeMillis();
        UUID targetKey = target.getUUID();
        if (target instanceof AbstractRecruitEntity recruit && recruit.getGroup() != null) {
            targetKey = recruit.getGroup();
        }
        boolean charge = targetKey.equals(lastEnemyOrderTarget)
                && lastEnemyOrderMillis > 0L
                && now - lastEnemyOrderMillis <= HASTY_DOUBLE_CLICK_MS;
        RTSCameraNetwork.CHANNEL.sendToServer(new AttackGroupsPacket(
                new ArrayList<>(SELECTED_GROUPS), target.getId(), charge));
        clearCurrentOrderPreviews(SELECTED_GROUPS);
        lastTerrainOrderMillis = 0L;
        lastTerrainOrderTarget = null;
        lastEnemyOrderMillis = charge ? 0L : now;
        lastEnemyOrderTarget = charge ? null : targetKey;
        setStatus((charge ? "Charge order" : selectedAttackPrefersRangedTargeting()
                ? "Tracked ranged target" : "Attack order")
                + " sent to " + SELECTED_GROUPS.size() + " group(s)."
                + (charge ? "" : " Double-RMB the same enemy to charge."));
    }

    private static void clearEnemyDoubleClick() {
        lastEnemyOrderMillis = 0L;
        lastEnemyOrderTarget = null;
    }

    private static void issueFrontageOrder() {
        applySelectionToRecruits();
        if (SELECTED_GROUPS.isEmpty()) {
            setStatus("Select at least one group first.");
            return;
        }
        if (frontageStartGround == null) {
            setStatus("Frontage start was not on valid terrain.");
            return;
        }

        GroundHit endHit = raycastGroundHit(frontageCurrentX, frontageCurrentY);
        if (endHit == null) {
            setStatus("Frontage end is not on valid terrain.");
            return;
        }

        Vec3 start = frontageStartGround.location();
        Vec3 end = endHit.location();
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double width = Math.sqrt(dx * dx + dz * dz);
        if (width < FrontageMath.MIN_FRONTAGE) {
            issueContextOrder();
            return;
        }
        if (width > FrontageMath.MAX_FRONTAGE) {
            double scale = FrontageMath.MAX_FRONTAGE / width;
            end = new Vec3(start.x + dx * scale, start.y + (end.y - start.y) * scale, start.z + dz * scale);
            width = FrontageMath.MAX_FRONTAGE;
        }
        Vec3 midpoint = start.add(end).scale(0.5D);
        if (midpoint.distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            setStatus("That frontage is outside the tactical radius.");
            return;
        }

        RTSCameraNetwork.CHANNEL.sendToServer(new FrontageGroupsPacket(
                new ArrayList<>(SELECTED_GROUPS),
                start,
                end
        ));
        rememberFrontageOrderPreview(start, end);
        for (UUID groupId : SELECTED_GROUPS) {
            LOCAL_FORMATIONS.put(groupId, FormationKind.LINE);
            LOCAL_CUSTOM_GROUPS.remove(groupId);
        }
        setStatus("Frontage order sent: " + String.format(java.util.Locale.ROOT, "%.1f", width)
                + " blocks · " + SELECTED_GROUPS.size() + " group(s). ");
    }

    private static void updateHover() {
        if (!commandCursor || draggingSelection || draggingTranslation
                || draggingFrontage || draggingCustom || rotatingFormation) {
            hoveredRecruit = null;
            hoveredFriendlyGroupId = null;
            hoveredTarget = null;
            hoveredGround = null;
            return;
        }
        hoveredRecruit = raycastRecruit(cursorX, cursorY);
        hoveredFriendlyGroupId = hoveredRecruit == null
                ? markerGroupAtCursor(cursorX, cursorY)
                : validOwnedGroupId(hoveredRecruit);
        hoveredTarget = hoveredRecruit == null && hoveredFriendlyGroupId == null
                ? raycastAttackTarget(cursorX, cursorY)
                : null;
        GroundHit groundHit = raycastGroundHit(cursorX, cursorY);
        hoveredGround = groundHit == null ? null : groundHit.pos();
        hoveredGroundSource = groundHit == null ? "MISS" : groundHit.source();
    }

    private static UUID markerGroupAtCursor(double screenX, double screenY) {
        if (!RTSClientConfig.usesGroundMarkers() || MC.level == null || MC.player == null) {
            return null;
        }
        final double hitRadiusPx = 15.0D;
        final double hitRadiusSq = hitRadiusPx * hitRadiusPx;
        UUID bestGroup = null;
        double bestDepth = Double.POSITIVE_INFINITY;
        double bestScreenDistance = Double.POSITIVE_INFINITY;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = validOwnedGroupId(recruit);
            if (groupId == null) continue;
            Entity controlled = recruit.getVehicle() != null ? recruit.getVehicle() : recruit;
            Vec3 pos = controlled.position();
            ScreenProjection.ProjectedPoint point = commanderProjectWorld(pos.x, controlled.getBoundingBox().minY + 0.06D, pos.z);
            if (point == null || point.depth() <= 0.0D) continue;
            double dx = point.x() - screenX;
            double dy = point.y() - screenY;
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq > hitRadiusSq) continue;
            if (point.depth() < bestDepth - 0.001D
                    || (Math.abs(point.depth() - bestDepth) < 0.001D && distanceSq < bestScreenDistance)) {
                bestDepth = point.depth();
                bestScreenDistance = distanceSq;
                bestGroup = groupId;
            }
        }
        return bestGroup;
    }

    private static AbstractRecruitEntity raycastRecruit(double screenX, double screenY) {
        RayContext ray = rayContext(screenX, screenY);
        if (ray == null || MC.level == null) {
            return null;
        }
        double closestDistance = blockLimitDistance(ray);
        AbstractRecruitEntity closest = null;
        AABB search = new AABB(ray.start(), ray.end()).inflate(2.0D);
        for (AbstractRecruitEntity recruit : MC.level.getEntitiesOfClass(AbstractRecruitEntity.class, search)) {
            if (validOwnedGroupId(recruit) == null) {
                continue;
            }
            Optional<Vec3> hit = recruit.getBoundingBox().inflate(0.35D).clip(ray.start(), ray.end());
            if (hit.isEmpty()) {
                continue;
            }
            double distance = ray.start().distanceToSqr(hit.get());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = recruit;
            }
        }
        return closest;
    }

    /**
     * RTS attack assist: enemy hitboxes are expanded only for cursor targeting. This does
     * not change Minecraft collision or combat reach; it simply makes high-altitude RTS
     * clicks less pixel-perfect. Friendly/owned entities are skipped before hit testing,
     * so they do not steal an attack click aimed at an enemy behind them.
     */
    private static LivingEntity raycastAttackTarget(double screenX, double screenY) {
        // Recompute from the current camera ray every frame and deliberately suppress
        // attack hover while Freecam is moving. This removes the stale/phantom sword
        // most visible while holding Crouch to descend the camera.
        if (cameraMovementInputActive()) {
            return null;
        }
        RayContext ray = rayContext(screenX, screenY);
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (ray == null || camera == null || MC.level == null) {
            return null;
        }
        double closestDistance = blockLimitDistance(ray);
        LivingEntity closest = null;
        AABB search = new AABB(ray.start(), ray.end()).inflate(4.0D);

        // Pass one is intentionally strict. This prevents the old "ghost sword" where
        // vertical camera movement made a huge inflated world AABB catch an enemy that
        // was nowhere near the visible cursor.
        for (LivingEntity living : MC.level.getEntitiesOfClass(LivingEntity.class, search)) {
            if (!isAttackableTarget(living) || !isClientVisible(living.position())) {
                continue;
            }
            Optional<Vec3> hit = living.getBoundingBox().inflate(0.15D).clip(ray.start(), ray.end());
            if (hit.isEmpty()) {
                continue;
            }
            double distance = ray.start().distanceToSqr(hit.get());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = living;
            }
        }
        if (closest != null) {
            return closest;
        }

        // High-altitude RTS assist: an expanded world hitbox is only accepted when the
        // actual projected body of the entity is also within a small screen-space pad
        // around the cursor. This keeps targeting forgiving without creating phantom
        // attack icons while moving the camera.
        for (LivingEntity living : MC.level.getEntitiesOfClass(LivingEntity.class, search)) {
            if (!isAttackableTarget(living) || !isClientVisible(living.position())) {
                continue;
            }
            AABB box = living.getBoundingBox();
            ScreenProjection.ProjectedBounds bounds = ScreenProjection.projectBox(
                    camera, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ
            );
            if (bounds == null
                    || screenX < bounds.minX() - ATTACK_SCREEN_ASSIST_PX
                    || screenX > bounds.maxX() + ATTACK_SCREEN_ASSIST_PX
                    || screenY < bounds.minY() - ATTACK_SCREEN_ASSIST_PX
                    || screenY > bounds.maxY() + ATTACK_SCREEN_ASSIST_PX) {
                continue;
            }
            double dynamicPadding = ATTACK_HITBOX_INFLATE + Math.min(0.25D, living.getBbWidth() * 0.12D);
            Optional<Vec3> hit = box.inflate(dynamicPadding).clip(ray.start(), ray.end());
            if (hit.isEmpty()) {
                continue;
            }
            double distance = ray.start().distanceToSqr(hit.get());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = living;
            }
        }
        return closest;
    }

    private static boolean cameraMovementInputActive() {
        if (MC.options == null) {
            return false;
        }
        return MC.options.keyUp.isDown()
                || MC.options.keyDown.isDown()
                || MC.options.keyLeft.isDown()
                || MC.options.keyRight.isDown()
                || physicalKeyState(RTSCameraKeyMappings.CAMERA_UP)
                || physicalKeyState(RTSCameraKeyMappings.CAMERA_DOWN);
    }

    /**
     * Client mirror of the server visibility rule. The free camera itself grants no vision.
     * The snapshot form lets the world-space fog renderer evaluate hundreds of cells without
     * repeatedly rebuilding the owned-recruit list.
     */
    public static List<FogVisionSource> fogVisionSources() {
        if (!ClientPermissionState.fogEnabled() || MC.player == null) {
            return List.of();
        }
        List<FogVisionSource> sources = new ArrayList<>();
        sources.add(new FogVisionSource(MC.player.position(), ClientPermissionState.playerVisionRadius()));
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            Vec3 source = recruit.position();
            double radius;
            if (ClientPermissionState.smallShipsAvailable()
                    && recruit instanceof CaptainEntity captain
                    && SmallShipsCompat.isCaptainAboard(captain)) {
                source = SmallShipsCompat.shipPosition(captain);
                radius = ClientPermissionState.shipVisionRadius();
            } else if (recruit.isPassenger()) {
                radius = ClientPermissionState.cavalryVisionRadius();
            } else {
                radius = ClientPermissionState.recruitVisionRadius();
            }
            sources.add(new FogVisionSource(source, radius));
        }
        return sources;
    }

    /**
     * 0 = inside friendly vision, 1 = well inside hidden territory. The 18-block feather is
     * visual only; the server still uses the exact configured radius for command authority.
     */
    public static float fogHiddenFactor(Vec3 target, List<FogVisionSource> sources) {
        return fogHiddenFactor(target, sources, 18.0D);
    }

    /**
     * Visual hidden-factor helper with a caller-selected feather distance. The exact
     * visible/hidden boundary is unchanged: only the cosmetic fade outside that boundary
     * changes. Server-side command visibility remains authoritative and independent.
     */
    public static float fogHiddenFactor(Vec3 target, List<FogVisionSource> sources, double edgeFadeDistance) {
        if (!ClientPermissionState.fogEnabled() || MC.player == null || target == null) {
            return 0.0F;
        }
        double nearestOutside = Double.POSITIVE_INFINITY;
        for (FogVisionSource source : sources) {
            double distance = Math.sqrt(horizontalDistanceSqr(source.position(), target));
            double outside = distance - source.radius();
            if (outside <= 0.0D) {
                return 0.0F;
            }
            nearestOutside = Math.min(nearestOutside, outside);
        }
        if (!Double.isFinite(nearestOutside)) {
            return 1.0F;
        }
        double feather = Math.max(0.001D, edgeFadeDistance);
        return (float) Math.max(0.0D, Math.min(1.0D, nearestOutside / feather));
    }

    public static float fogHiddenFactor(Vec3 target) {
        return fogHiddenFactor(target, fogVisionSources());
    }

    private static boolean isClientVisible(Vec3 target) {
        return fogHiddenFactor(target) <= 0.0F;
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    /**
     * Terrain raycasting deliberately uses the real player as the collision context.
     * Freecam's synthetic camera entity is only a viewpoint and should not decide which
     * block collision shapes the command cursor can hit.
     */
    private static GroundHit raycastGroundHit(double screenX, double screenY) {
        RayContext ray = rayContext(screenX, screenY);
        return ray == null ? null : raycastGroundHit(ray);
    }

    private static GroundHit raycastGroundHit(RayContext ray) {
        if (MC.level == null || MC.player == null) {
            return null;
        }

        // Normal solid terrain. This is the path we expect to use almost all the time.
        BlockHitResult collider = MC.level.clip(new ClipContext(
                ray.start(),
                ray.end(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                MC.player
        ));
        if (collider.getType() == HitResult.Type.BLOCK) {
            return new GroundHit(collider.getBlockPos(), collider.getLocation(), "COLLIDER");
        }

        // Some modded blocks expose an outline without a useful collider. It is still a
        // perfectly reasonable place for the player to point an RTS command cursor.
        BlockHitResult outline = MC.level.clip(new ClipContext(
                ray.start(),
                ray.end(),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                MC.player
        ));
        if (outline.getType() == HitResult.Type.BLOCK) {
            return new GroundHit(outline.getBlockPos(), outline.getLocation(), "OUTLINE");
        }

        // Last-resort DDA-style sampling. This intentionally ignores Freecam entirely and
        // checks real collision shapes along the already-proven screen ray. It protects the
        // command cursor from odd modded collision implementations returning MISS above.
        return manualCollisionFallback(ray);
    }

    private static GroundHit manualCollisionFallback(RayContext ray) {
        if (MC.level == null || MC.player == null) {
            return null;
        }
        Vec3 delta = ray.end().subtract(ray.start());
        double length = delta.length();
        if (length < 0.001D) {
            return null;
        }

        final double stepLength = 0.20D;
        Vec3 step = delta.scale(stepLength / length);
        Vec3 sample = ray.start();
        BlockPos lastChecked = null;
        int steps = Math.min(2048, (int) Math.ceil(length / stepLength));
        CollisionContext collisionContext = CollisionContext.of(MC.player);

        for (int i = 0; i <= steps; i++) {
            BlockPos pos = BlockPos.containing(sample);
            if (!pos.equals(lastChecked) && MC.level.hasChunkAt(pos)) {
                lastChecked = pos;
                BlockState state = MC.level.getBlockState(pos);
                if (!state.isAir()) {
                    VoxelShape shape = state.getCollisionShape(MC.level, pos, collisionContext);
                    if (!shape.isEmpty()) {
                        BlockHitResult clipped = shape.clip(ray.start(), ray.end(), pos);
                        if (clipped != null) {
                            return new GroundHit(clipped.getBlockPos(), clipped.getLocation(), "MANUAL");
                        }
                    }
                }
            }
            sample = sample.add(step);
        }
        return null;
    }

    private static double blockLimitDistance(RayContext ray) {
        GroundHit blockHit = raycastGroundHit(ray);
        return blockHit != null
                ? ray.start().distanceToSqr(blockHit.location())
                : MAX_RAY_DISTANCE * MAX_RAY_DISTANCE;
    }

    private static RayContext rayContext(double screenX, double screenY) {
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) {
            return null;
        }
        ScreenProjection.Ray ray = ScreenProjection.ray(camera, screenX, screenY);
        Vec3 start = new Vec3(ray.origin().x(), ray.origin().y(), ray.origin().z());
        Vec3 direction = new Vec3(ray.direction().x(), ray.direction().y(), ray.direction().z());
        return new RayContext(start, start.add(direction.scale(MAX_RAY_DISTANCE)));
    }

    private static ScreenProjection.CameraSpec cameraSpec() {
        if (MC.level == null || MC.getWindow().getWidth() <= 0 || MC.getWindow().getHeight() <= 0) {
            return null;
        }
        Camera camera = MC.gameRenderer.getMainCamera();
        Vec3 position = camera.getPosition();
        double fov = MC.options.fov().get();
        return new ScreenProjection.CameraSpec(
                position.x,
                position.y,
                position.z,
                camera.getYRot(),
                camera.getXRot(),
                fov,
                MC.getWindow().getWidth(),
                MC.getWindow().getHeight()
        );
    }

    private static List<AbstractRecruitEntity> loadedOwnedRecruits() {
        if (MC.level == null || MC.player == null) {
            return List.of();
        }
        Vec3 center = MC.gameRenderer.getMainCamera().getPosition();
        AABB area = new AABB(
                center.x - SELECTION_RANGE,
                center.y - SELECTION_RANGE,
                center.z - SELECTION_RANGE,
                center.x + SELECTION_RANGE,
                center.y + SELECTION_RANGE,
                center.z + SELECTION_RANGE
        );
        List<AbstractRecruitEntity> result = new ArrayList<>();
        for (AbstractRecruitEntity recruit : MC.level.getEntitiesOfClass(AbstractRecruitEntity.class, area)) {
            if (validOwnedGroupId(recruit) != null) {
                result.add(recruit);
            }
        }
        return result;
    }

    private static UUID validOwnedGroupId(AbstractRecruitEntity recruit) {
        if (recruit == null || MC.player == null || !recruit.isAlive()) {
            return null;
        }
        if (!MC.player.getUUID().equals(recruit.getOwnerUUID())) {
            return null;
        }
        UUID groupId = recruit.getGroup();
        return groupId != null && findOwnedClientGroup(groupId) != null ? groupId : null;
    }

    private static RecruitsGroup findOwnedClientGroup(UUID groupId) {
        if (ClientManager.groups == null || MC.player == null) {
            return null;
        }
        for (RecruitsGroup group : ClientManager.groups) {
            if (group == null || group.removed || !groupId.equals(group.getUUID())) {
                continue;
            }
            if (MC.player.getUUID().equals(group.getPlayerUUID())) {
                return group;
            }
        }
        return null;
    }


    /**
     * ClientManager groups are lightweight client snapshots. RecruitsGroup#copy() preserves
     * the persistent size, UUID/name/owner metadata, but it does NOT copy the member UUID
     * list. Therefore group.members must never be used as a client-side existence test.
     */
    private static boolean isCommandableClientGroup(RecruitsGroup group) {
        return group != null && !group.removed && group.getUUID() != null;
    }

    private static void syncSelectionFromRecruits() {
        SELECTED_GROUPS.clear();
        if (ClientManager.groups == null || MC.player == null) {
            return;
        }
        for (RecruitsGroup group : ClientManager.groups) {
            if (!isCommandableClientGroup(group)) {
                continue;
            }
            if (MC.player.getUUID().equals(group.getPlayerUUID()) && !group.isDisabled()) {
                SELECTED_GROUPS.add(group.getUUID());
            }
        }
    }

    private static void applySelectionToRecruits() {
        if (ClientManager.groups == null || MC.player == null) {
            return;
        }
        Set<UUID> existing = new LinkedHashSet<>();
        for (RecruitsGroup group : ClientManager.groups) {
            if (!isCommandableClientGroup(group)) {
                continue;
            }
            if (!MC.player.getUUID().equals(group.getPlayerUUID())) {
                continue;
            }
            existing.add(group.getUUID());
            group.setDisabled(!SELECTED_GROUPS.contains(group.getUUID()));
        }
        SELECTED_GROUPS.retainAll(existing);
    }

    private static void updateGlow() {
        if (MC.level == null || !RTSClientConfig.usesEntityGlow()) {
            clearGlow();
            return;
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId != null && SELECTED_GROUPS.contains(groupId)) {
                seen.add(recruit.getUUID());
                MODIFIED_GLOW.putIfAbsent(
                        recruit.getUUID(),
                        new GlowState(recruit, recruit.hasGlowingTag())
                );
                recruit.setGlowingTag(true);
            } else {
                restoreGlow(recruit);
            }
        }
        for (Map.Entry<UUID, GlowState> entry : List.copyOf(MODIFIED_GLOW.entrySet())) {
            if (!seen.contains(entry.getKey())) {
                entry.getValue().entity().setGlowingTag(entry.getValue().original());
                MODIFIED_GLOW.remove(entry.getKey());
            }
        }
        RTSSelectionOutlineState.replace(seen);
    }

    private static void restoreGlow(AbstractRecruitEntity recruit) {
        GlowState state = MODIFIED_GLOW.remove(recruit.getUUID());
        if (state != null) {
            recruit.setGlowingTag(state.original());
        }
    }

    private static void clearGlow() {
        for (GlowState state : List.copyOf(MODIFIED_GLOW.values())) {
            state.entity().setGlowingTag(state.original());
        }
        MODIFIED_GLOW.clear();
        RTSSelectionOutlineState.clear();
    }

    /**
     * Use the ranged target cursor when the loaded selected force is entirely a ranged or
     * siege role. Mixed melee+ranged selections deliberately fall back to the sword cursor
     * so the player is not misled about the whole force behaving as a missile unit.
     */
    private static boolean selectedAttackPrefersRangedTargeting() {
        if (MC.level == null || SELECTED_GROUPS.isEmpty()) {
            return false;
        }
        int loaded = 0;
        int ranged = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId == null || !SELECTED_GROUPS.contains(groupId)) {
                continue;
            }
            loaded++;
            if (recruit.getShouldRanged() || recruit instanceof SiegeEngineerEntity) {
                ranged++;
            }
        }
        return loaded > 0 && ranged == loaded;
    }

    private static boolean isAttackableTarget(LivingEntity target) {
        if (target == null || MC.player == null || !target.isAlive() || target == MC.player) {
            return false;
        }
        if (target instanceof AbstractRecruitEntity recruit
                && MC.player.getUUID().equals(recruit.getOwnerUUID())) {
            return false;
        }
        return !MC.player.isAlliedTo(target);
    }

    private static boolean isRecruitsScreenOpen() {
        return MC.screen != null
                && MC.screen.getClass().getName().startsWith("com.talhanation.recruits.client.gui");
    }

    private static void updateCursorPosition() {
        cursorX = MC.mouseHandler.xpos();
        cursorY = MC.mouseHandler.ypos();
    }

    public static void renderHud(GuiGraphics graphics) {
        if (!active || MC.font == null || MC.screen != null) {
            return;
        }

        if (draggingCustom) {
            sampleCustomStrokePoint(false);
        }

        Map<UUID, GroupSummary> summaries = selectedGroupSummaries();
        int selectedTroops = summaries.values().stream().mapToInt(GroupSummary::loadedCount).sum();
        int selectedArtillery = selectedArtilleryGuns();
        int selectedShips = selectedShips();

        // Total War mode always owns the normal RTS presentation. Debug mode adds the
        // verbose diagnostics panel without replacing the command interface.
        CommanderOverlay.render(graphics);
        if (RTSClientConfig.DEBUG_COMMAND_MENU.get()) {
            renderDebugCommandPanel(graphics, summaries, selectedTroops, selectedArtillery, selectedShips);
        }

        if (!commandCursor) {
            return;
        }

        // The RTS mouse position remains the source for raycasts, hover and orders, but
        // deliberately has no moving crosshair/attack artwork on the HUD.

        if (draggingSelection) {
            int minX = toGuiX(Math.min(selectionStartX, selectionCurrentX));
            int maxX = toGuiX(Math.max(selectionStartX, selectionCurrentX));
            int minY = toGuiY(Math.min(selectionStartY, selectionCurrentY));
            int maxY = toGuiY(Math.max(selectionStartY, selectionCurrentY));
            graphics.fill(minX, minY, maxX, maxY, 0x3028D46C);
            drawBoxOutline(graphics, minX, minY, maxX, maxY, 0xFF58E08A);
            int previewCount = groupsInsideCurrentBox().size();
            graphics.drawString(MC.font,
                    previewCount + (previewCount == 1 ? " group" : " groups"),
                    minX + 4,
                    Math.max(4, minY - 11),
                    0xFF8CFFAE,
                    true);
        }

        if (draggingFrontage) {
            if (isFrontageGesture()) {
                int x1 = toGuiX(frontageStartX);
                int y1 = toGuiY(frontageStartY);
                int x2 = toGuiX(frontageCurrentX);
                int y2 = toGuiY(frontageCurrentY);
                drawScreenLine(graphics, x1, y1, x2, y2, 0xFFFFD36A);
                graphics.fill(x1 - 3, y1 - 3, x1 + 4, y1 + 4, 0xFFFFE7A3);
                graphics.fill(x2 - 3, y2 - 3, x2 + 4, y2 + 4, 0xFFFFE7A3);
                renderFrontageFormationPreview(graphics);
            } else {
                renderMovePreview(graphics);
            }
        }

        if (draggingCustom && CUSTOM_STROKE.size() >= 2) {
            for (int i = 1; i < CUSTOM_STROKE.size(); i++) {
                CustomStrokePoint a = CUSTOM_STROKE.get(i - 1);
                CustomStrokePoint b = CUSTOM_STROKE.get(i);
                drawScreenLine(
                        graphics,
                        toGuiX(a.screenX()), toGuiY(a.screenY()),
                        toGuiX(b.screenX()), toGuiY(b.screenY()),
                        0xFFFFB84D
                );
            }
            CustomStrokePoint first = CUSTOM_STROKE.get(0);
            CustomStrokePoint last = CUSTOM_STROKE.get(CUSTOM_STROKE.size() - 1);
            graphics.fill(toGuiX(first.screenX()) - 3, toGuiY(first.screenY()) - 3,
                    toGuiX(first.screenX()) + 4, toGuiY(first.screenY()) + 4, 0xFFFFE0A3);
            graphics.fill(toGuiX(last.screenX()) - 3, toGuiY(last.screenY()) - 3,
                    toGuiX(last.screenX()) + 4, toGuiY(last.screenY()) + 4, 0xFFFFE0A3);
        }

        if (draggingTranslation) {
            renderTranslationPreview(graphics);
        }

        if (!routeMode && !draggingTranslation && !draggingFrontage && !draggingCustom && !rotatingFormation) {
            renderCurrentOrderPreviews(graphics);
        }

        if (routeMode) {
            renderRoutePreview(graphics);
        }

        if (rotatingFormation) {
            renderRotationPreview(graphics);
        }
    }

    private static void renderReleaseCommandPanel(
            GuiGraphics graphics,
            int selectedTroops,
            int selectedArtillery,
            int selectedShips
    ) {
        int guiWidth = MC.getWindow().getGuiScaledWidth();
        int panelX = 8;
        int panelY = 8;
        int panelWidth = Math.min(330, Math.max(180, guiWidth - 16));
        int lineHeight = 13;
        boolean showStatus = statusTicks > 0 && !status.isBlank();
        int lineCount = 3 + (selectedArtillery > 0 ? 1 : 0) + (selectedShips > 0 ? 1 : 0) + (showStatus ? 1 : 0);
        int panelHeight = 12 + lineCount * lineHeight;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0101010);
        graphics.fill(panelX, panelY, panelX + 3, panelY + panelHeight, 0xFFE0B85A);

        int textX = panelX + 9;
        int textWidth = panelWidth - 16;
        int y = panelY + 7;
        drawHudLine(graphics,
                (commandCursor ? "COMMAND" : "CAMERA LOOK") + " · " + SELECTED_GROUPS.size()
                        + " groups · " + selectedTroops + " troops",
                textX, y, textWidth, 0xFFFFFFFF);
        y += lineHeight;
        drawHudLine(graphics, "Formation: " + selectedFormationText(), textX, y, textWidth, 0xFFFFD98A);
        y += lineHeight;
        drawHudLine(graphics, currentContextLine(false), textX, y, textWidth,
                (routeMode || rotatingFormation || draggingFrontage || draggingCustom) ? 0xFFFFD36A : hoverColor());

        if (selectedArtillery > 0) {
            y += lineHeight;
            drawHudLine(graphics, artilleryHudText(selectedArtillery), textX, y, textWidth, 0xFFFFD98A);
        }
        if (selectedShips > 0) {
            y += lineHeight;
            drawHudLine(graphics, "Fleet: " + selectedShips + " ship(s)", textX, y, textWidth, 0xFFAEDBFF);
        }
        if (showStatus) {
            y += lineHeight;
            drawHudLine(graphics, status, textX, y, textWidth, 0xFFE7C67A);
        }
    }

    private static void renderDebugCommandPanel(
            GuiGraphics graphics,
            Map<UUID, GroupSummary> summaries,
            int selectedTroops,
            int selectedArtillery,
            int selectedShips
    ) {
        int guiWidth = MC.getWindow().getGuiScaledWidth();
        int panelX = 8;
        int panelY = 8;
        int panelWidth = Math.min(390, Math.max(200, guiWidth - 16));
        int lineHeight = 13;
        boolean showStatus = statusTicks > 0 && !status.isBlank();
        int lineCount = 14 + (selectedArtillery > 0 ? 1 : 0) + (selectedShips > 0 ? 1 : 0) + (showStatus ? 1 : 0);
        int panelHeight = 12 + lineCount * lineHeight;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD0101010);
        graphics.fill(panelX, panelY, panelX + 3, panelY + panelHeight, 0xFFE0B85A);

        int textX = panelX + 9;
        int textWidth = panelWidth - 16;
        int y = panelY + 7;
        drawHudLine(graphics, "RECRUITS: RTS CAMERA · 0.15.9-server.1 · DEBUG COMMAND MENU", textX, y, textWidth, 0xFFFFD98A);
        y += lineHeight;
        drawHudLine(graphics,
                (commandCursor ? "COMMAND" : "CAMERA LOOK") + " · " + SELECTED_GROUPS.size()
                        + " groups · " + selectedTroops + " troops",
                textX, y, textWidth, 0xFFFFFFFF);
        y += lineHeight;
        drawHudLine(graphics, "Formation: " + selectedFormationText(), textX, y, textWidth, 0xFFFFD98A);
        y += lineHeight;
        drawHudLine(graphics, currentContextLine(true), textX, y, textWidth,
                (routeMode || rotatingFormation || draggingFrontage || draggingCustom) ? 0xFFFFD36A : hoverColor());
        y += lineHeight;
        drawHudLine(graphics, "Mouse: LMB select · Ctrl+LMB toggle · Shift+card range · RMB move/attack",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Mouse: wheel down rises / up lowers (custom draw: ranks) · hold MMB look · 2×RMB run",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Keys: " + keyName(RTSCameraKeyMappings.TOGGLE) + " camera · "
                        + keyName(RTSCameraKeyMappings.CLEAR) + " clear · "
                        + keyName(RTSCameraKeyMappings.MOVEMENT_PACE) + " walk/run",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Camera: WASD pan · " + keyName(RTSCameraKeyMappings.CAMERA_UP) + " up · "
                        + keyName(RTSCameraKeyMappings.CAMERA_DOWN) + " down · wheel down rises/up lowers · hold "
                        + keyName(RTSCameraKeyMappings.SHOW_PLANNED_ORDERS) + " all orders",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Groups: " + keyName(RTSCameraKeyMappings.SELECTION_GROUP) + " create bundle · "
                        + keyName(RTSCameraKeyMappings.ROTATE) + " rotate · "
                        + keyName(RTSCameraKeyMappings.ARMY_PRESET) + " army " + armyPresetTool.displayName(),
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Route: " + keyName(RTSCameraKeyMappings.ROUTE_MODE) + " mode · "
                        + keyName(RTSCameraKeyMappings.COMMIT_ROUTE) + " commit · "
                        + keyName(RTSCameraKeyMappings.RESET_ROUTE) + " cancel planning · "
                        + keyName(RTSCameraKeyMappings.HALT) + " HALT",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "Fire: " + keyName(RTSCameraKeyMappings.STRATEGIC_FIRE) + " strategic · "
                        + keyName(RTSCameraKeyMappings.HOLD_FIRE) + " hold · "
                        + keyName(RTSCameraKeyMappings.FIRE_AT_WILL) + " fire at will",
                textX, y, textWidth, 0xFFB8B8B8);
        y += lineHeight;
        drawHudLine(graphics, "State: route=" + routeMode + " rotate=" + rotatingFormation
                        + " frontage=" + draggingFrontage + " custom=" + draggingCustom
                        + " target=" + (hoveredTarget == null ? "none" : hoveredTarget.getType().toString()),
                textX, y, textWidth, 0xFF9FC7E8);
        y += lineHeight;
        drawHudLine(graphics, "FOW: server=" + ClientPermissionState.fogEnabled()
                        + " render=" + RTSClientConfig.FOG_RENDER_ENABLED.get()
                        + " · inputGate=" + InputGate.isBlocking(),
                textX, y, textWidth, 0xFF9FC7E8);

        if (selectedArtillery > 0) {
            y += lineHeight;
            drawHudLine(graphics, artilleryHudText(selectedArtillery), textX, y, textWidth, 0xFFFFD98A);
        }
        if (selectedShips > 0) {
            y += lineHeight;
            drawHudLine(graphics, "Fleet: " + selectedShips + " ship(s) · Recruits Captain native sailing",
                    textX, y, textWidth, 0xFFAEDBFF);
        }
        y += lineHeight;
        drawHudLine(graphics, selectedSummaryText(summaries), textX, y, textWidth, 0xFFD8D8D8);
        if (showStatus) {
            y += lineHeight;
            drawHudLine(graphics, "Last action: " + status, textX, y, textWidth, 0xFFE7C67A);
        }
    }

    private static String keyName(net.minecraft.client.KeyMapping mapping) {
        return mapping == null ? "?" : mapping.getTranslatedKeyMessage().getString();
    }

    private static String currentContextLine(boolean debug) {
        if (routeMode) {
            return debug
                    ? "ROUTE MODE · " + ROUTE_POINTS.size() + " waypoint(s) · RMB add · Enter commit · Backspace cancel"
                    : "ROUTE MODE · " + ROUTE_POINTS.size() + " waypoint(s)";
        }
        if (rotatingFormation) {
            return "Rotate: " + Math.round(rotationDegrees) + "° · facing "
                    + Math.round(FormationRotationMath.normalizeYaw(rotationBaseFacingYaw + (float) rotationDegrees)) + "°";
        }
        if (draggingCustom) {
            return customPreviewText();
        }
        if (draggingFrontage) {
            return isFrontageGesture() ? frontagePreviewText() : movePreviewText();
        }
        return hoverText();
    }

    private static void drawHudLine(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        graphics.drawString(MC.font, fitText(text, maxWidth), x, y, color, false);
    }

    private static String fitText(String text, int maxWidth) {
        if (text == null || MC.font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String suffix = "...";
        int end = text.length();
        while (end > 1 && MC.font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(1, end)) + suffix;
    }

    /**
     * RMB begins as a move-preview gesture. It becomes a frontage reform only after
     * the cursor has crossed the same drag threshold used when the button is released.
     */
    private static boolean isFrontageGesture() {
        return draggingFrontage && Math.hypot(
                frontageCurrentX - frontageStartX,
                frontageCurrentY - frontageStartY
        ) >= FRONTAGE_DRAG_THRESHOLD;
    }

    /** Full frontage ghost: mirrors FrontageMath rank/spacing selection before release. */
    private static void renderFrontageFormationPreview(GuiGraphics graphics) {
        if (!isFrontageGesture() || frontageStartGround == null || SELECTED_GROUPS.isEmpty()) {
            return;
        }
        GroundHit endHit = raycastGroundHit(frontageCurrentX, frontageCurrentY);
        if (endHit == null) {
            return;
        }
        Vec3 start = frontageStartGround.location();
        Vec3 end = endHit.location();
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double rawWidth = Math.hypot(dx, dz);
        if (rawWidth < FrontageMath.MIN_FRONTAGE) {
            return;
        }
        double width = Math.min(FrontageMath.MAX_FRONTAGE, rawWidth);
        double lineX = dx / rawWidth;
        double lineZ = dz / rawWidth;
        float facingYaw = FrontageMath.facingYaw(dx, dz);
        Map<UUID, List<AbstractRecruitEntity>> groups = selectedLoadedGroups();
        if (groups.isEmpty()) {
            return;
        }

        List<UUID> ordered = new ArrayList<>(groups.keySet());
        ordered.sort(java.util.Comparator.comparingDouble(
                id -> clientGroupProjection(groups.get(id), lineX, lineZ)
        ));
        List<Integer> counts = new ArrayList<>(ordered.size());
        for (UUID id : ordered) {
            counts.add(groups.get(id).size());
        }
        List<FrontageMath.GroupSegment> segments = FrontageMath.split(counts, width);
        if (segments.size() != ordered.size()) {
            return;
        }

        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) {
            return;
        }
        double midX = (start.x + end.x) * 0.5D;
        double midY = (start.y + end.y) * 0.5D;
        double midZ = (start.z + end.z) * 0.5D;
        double yaw = Math.toRadians(facingYaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        int drawn = 0;

        for (int gi = 0; gi < ordered.size() && drawn < 384; gi++) {
            List<AbstractRecruitEntity> recruits = groups.get(ordered.get(gi));
            FrontageMath.GroupSegment segment = segments.get(gi);
            double centerX = midX + lineX * segment.centerOffset();
            double centerZ = midZ + lineZ * segment.centerOffset();

            int artillery = 0;
            int ships = 0;
            int infantry = 0;
            for (AbstractRecruitEntity recruit : recruits) {
                if (ClientPermissionState.siegeWeaponsAvailable()
                        && recruit instanceof SiegeEngineerEntity engineer
                        && SiegeWeaponsCompat.isAttachedToSiegeWeapon(engineer)) {
                    artillery++;
                } else if (ClientPermissionState.smallShipsAvailable()
                        && recruit instanceof CaptainEntity captain
                        && SmallShipsCompat.isCaptainAboard(captain)) {
                    ships++;
                } else {
                    infantry++;
                }
            }

            if (infantry > 0) {
                int ranks = Math.max(1, Math.min(segment.ranks(), infantry));
                int columns = (int) Math.ceil(infantry / (double) ranks);
                double rightStep = Math.max(1.0D, 1.02D * segment.spacing());
                double backStep = Math.max(1.1D, 1.12D * segment.spacing());
                int placed = 0;
                for (int row = 0; row < ranks && placed < infantry && drawn < 384; row++) {
                    for (int col = 0; col < columns && placed < infantry && drawn < 384; col++) {
                        double right = (col - (columns - 1) * 0.5D) * rightStep;
                        double back = (row - (ranks - 1) * 0.5D) * backStep;
                        double wx = centerX + rightX * right - forwardX * back;
                        double wz = centerZ + rightZ * right - forwardZ * back;
                        drawYellowWorldPoint(graphics, camera, wx, midY + 0.15D, wz, false);
                        placed++;
                        drawn++;
                    }
                }
            }

            if (artillery > 0 && drawn < 384) {
                double spacing = artillery <= 1 ? 0.0D
                        : Math.max(ArtilleryFormationMath.MIN_GUN_SPACING,
                        Math.min(ArtilleryFormationMath.MAX_GUN_SPACING, segment.allocatedWidth() / (artillery - 1.0D)));
                for (int i = 0; i < artillery && drawn < 384; i++) {
                    double off = (i - (artillery - 1) * 0.5D) * spacing;
                    drawYellowWorldPoint(graphics, camera,
                            centerX + rightX * off, midY + 0.2D, centerZ + rightZ * off, true);
                    drawn++;
                }
            }
            if (ships > 0 && drawn < 384) {
                double spacing = ships <= 1 ? 0.0D
                        : Math.max(SmallShipsCompat.MIN_SPACING,
                        Math.min(SmallShipsCompat.MAX_SPACING, segment.allocatedWidth() / (ships - 1.0D)));
                for (int i = 0; i < ships && drawn < 384; i++) {
                    double off = (i - (ships - 1) * 0.5D) * spacing;
                    drawYellowWorldPoint(graphics, camera,
                            centerX + rightX * off, midY + 0.3D, centerZ + rightZ * off, true);
                    drawn++;
                }
            }
        }
    }

    private static void rememberMoveOrderPreview(Vec3 armyTarget) {
        Map<UUID, List<AbstractRecruitEntity>> groups = selectedLoadedGroups();
        if (armyTarget == null || groups.isEmpty()) {
            return;
        }
        Map<UUID, Vec3> centers = clientGroupCenters(groups);
        Vec3 armyCenter = averageCenters(centers.values());
        if (armyCenter == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Vec3> entry : centers.entrySet()) {
            Vec3 center = entry.getValue();
            Vec3 target = armyTarget.add(center.x - armyCenter.x, center.y - armyCenter.y, center.z - armyCenter.z);
            CURRENT_ORDER_PREVIEWS.put(entry.getKey(), new CurrentOrderPreview(
                    List.of(target), target, null, null, now
            ));
        }
    }

    private static void rememberRouteOrderPreview(List<BlockPos> route) {
        Map<UUID, List<AbstractRecruitEntity>> groups = selectedLoadedGroups();
        if (route == null || route.isEmpty() || groups.isEmpty()) {
            return;
        }
        Map<UUID, Vec3> centers = clientGroupCenters(groups);
        Vec3 armyCenter = averageCenters(centers.values());
        if (armyCenter == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Vec3> entry : centers.entrySet()) {
            Vec3 offset = entry.getValue().subtract(armyCenter);
            List<Vec3> points = new ArrayList<>(route.size());
            for (BlockPos pos : route) {
                points.add(new Vec3(pos.getX() + 0.5D + offset.x, pos.getY() + offset.y, pos.getZ() + 0.5D + offset.z));
            }
            Vec3 destination = points.get(points.size() - 1);
            CURRENT_ORDER_PREVIEWS.put(entry.getKey(), new CurrentOrderPreview(
                    List.copyOf(points), destination, null, null, now
            ));
        }
    }

    private static void rememberTranslationOrderPreview(Vec3 delta, double degrees) {
        if (delta == null) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = selectedLoadedGroups();
        Map<UUID, Vec3> centers = clientGroupCenters(groups);
        Vec3 armyCenter = averageCenters(centers.values());
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Vec3> entry : centers.entrySet()) {
            Vec3 destination = rotateAround(entry.getValue(), armyCenter, degrees).add(delta);
            CURRENT_ORDER_PREVIEWS.put(entry.getKey(), new CurrentOrderPreview(
                    List.of(destination), destination, null, null, now));
        }
    }

    private static Vec3 rotateAround(Vec3 point, Vec3 pivot, double degrees) {
        if (point == null || pivot == null || Math.abs(degrees) < 1.0E-6D) return point;
        FormationRotationMath.Point rotated = FormationRotationMath.rotate(
                new FormationRotationMath.Point(point.x, point.z),
                new FormationRotationMath.Point(pivot.x, pivot.z), degrees);
        return new Vec3(rotated.x(), point.y, rotated.z());
    }

    public static void applyFormationRouteSync(UUID groupId, Vec3 serverCenter, Vec3 destination,
                                               List<Vec3> waypoints, boolean moving) {
        if (groupId == null) return;
        if (!moving) {
            LOCAL_RUNNING_GROUPS.remove(groupId);
            CURRENT_ORDER_PREVIEWS.remove(groupId);
            return;
        }
        if (destination == null || waypoints == null) return;
        CurrentOrderPreview previous = CURRENT_ORDER_PREVIEWS.get(groupId);
        Vec3 frontageStart = previous == null ? null : previous.frontageStart();
        Vec3 frontageEnd = previous == null ? null : previous.frontageEnd();
        List<Vec3> remaining = new ArrayList<>();
        Vec3 liveStart = serverCenter == null ? Vec3.ZERO : serverCenter;
        for (Vec3 point : waypoints) {
            if (point != null && point.distanceToSqr(liveStart) > 0.20D * 0.20D) remaining.add(point);
        }
        if (remaining.isEmpty()) remaining.add(destination);
        CURRENT_ORDER_PREVIEWS.put(groupId, new CurrentOrderPreview(
                List.copyOf(remaining), destination, frontageStart, frontageEnd, System.currentTimeMillis()));
    }

    private static void rememberFrontageOrderPreview(Vec3 start, Vec3 end) {
        if (start == null || end == null) return;
        Vec3 midpoint = start.add(end).scale(0.5D);
        rememberMoveOrderPreview(midpoint);
        long now = System.currentTimeMillis();
        for (UUID groupId : SELECTED_GROUPS) {
            CurrentOrderPreview old = CURRENT_ORDER_PREVIEWS.get(groupId);
            Vec3 destination = old == null ? midpoint : old.destination();
            List<Vec3> path = old == null ? List.of(destination) : old.waypoints();
            CURRENT_ORDER_PREVIEWS.put(groupId, new CurrentOrderPreview(path, destination, start, end, now));
        }
    }

    private static Map<UUID, Vec3> clientGroupCenters(Map<UUID, List<AbstractRecruitEntity>> groups) {
        Map<UUID, Vec3> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<AbstractRecruitEntity>> entry : groups.entrySet()) {
            double x = 0.0D, y = 0.0D, z = 0.0D;
            int count = 0;
            for (AbstractRecruitEntity recruit : entry.getValue()) {
                Vec3 pos = visualUnitPosition(recruit);
                x += pos.x; y += pos.y; z += pos.z; count++;
            }
            if (count > 0) result.put(entry.getKey(), new Vec3(x / count, y / count, z / count));
        }
        return result;
    }

    private static Vec3 averageCenters(java.util.Collection<Vec3> centers) {
        if (centers == null || centers.isEmpty()) return null;
        double x = 0.0D, y = 0.0D, z = 0.0D;
        int count = 0;
        for (Vec3 center : centers) {
            if (center == null) continue;
            x += center.x; y += center.y; z += center.z; count++;
        }
        return count == 0 ? null : new Vec3(x / count, y / count, z / count);
    }

    private static void clearCurrentOrderPreviews(java.util.Collection<UUID> groups) {
        if (groups == null) return;
        for (UUID groupId : groups) CURRENT_ORDER_PREVIEWS.remove(groupId);
    }

    private static void renderCurrentOrderPreviews(GuiGraphics graphics) {
        if (CURRENT_ORDER_PREVIEWS.isEmpty()) return;
        Set<UUID> visible = new LinkedHashSet<>(SELECTED_GROUPS);
        if (hoveredFriendlyGroupId != null) visible.add(hoveredFriendlyGroupId);
        if (capturedShowOrdersDown || RTSCameraKeyMappings.SHOW_PLANNED_ORDERS.isDown()) {
            visible.addAll(CURRENT_ORDER_PREVIEWS.keySet());
        }
        if (visible.isEmpty()) return;
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) return;
        Map<UUID, List<AbstractRecruitEntity>> loaded = loadedGroups(visible);
        long now = System.currentTimeMillis();

        for (UUID groupId : List.copyOf(visible)) {
            CurrentOrderPreview preview = CURRENT_ORDER_PREVIEWS.get(groupId);
            List<AbstractRecruitEntity> recruits = loaded.get(groupId);
            if (preview == null || recruits == null || recruits.isEmpty()) continue;

            Vec3 center = clientGroupCenters(Map.of(groupId, recruits)).get(groupId);
            if (center == null) continue;
            if (horizontalDistanceClient(center, preview.destination()) < 1.2D && now - preview.issuedMillis() > 750L) {
                CURRENT_ORDER_PREVIEWS.remove(groupId, preview);
                continue;
            }

            Vec3 previous = center;
            for (Vec3 point : preview.waypoints()) {
                drawWorldPathSegment(graphics, camera, previous, point);
                previous = point;
            }
            if (preview.frontageStart() != null && preview.frontageEnd() != null) {
                drawWorldPathSegment(graphics, camera, preview.frontageStart(), preview.frontageEnd());
            }

            double dx = preview.destination().x - center.x;
            double dy = preview.destination().y - center.y;
            double dz = preview.destination().z - center.z;
            int drawn = 0;
            for (AbstractRecruitEntity recruit : recruits) {
                if (drawn++ >= 192) break;
                Vec3 source = visualUnitPosition(recruit);
                drawYellowWorldPoint(graphics, camera, source.x + dx, source.y + dy + 0.15D, source.z + dz, false);
            }
        }
    }

    private static void renderTranslationPreview(GuiGraphics graphics) {
        if (translationStartGround == null || translationCurrentGround == null) return;
        Vec3 delta = translationCurrentGround.subtract(translationStartGround);
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) return;
        Map<UUID, List<AbstractRecruitEntity>> groups = selectedLoadedGroups();
        Map<UUID, Vec3> centers = clientGroupCenters(groups);
        Vec3 armyCenter = averageCenters(centers.values());
        if (armyCenter != null) drawWorldPathSegment(graphics, camera, armyCenter, armyCenter.add(delta));
        int drawn = 0;
        for (List<AbstractRecruitEntity> recruits : groups.values()) {
            for (AbstractRecruitEntity recruit : recruits) {
                if (drawn++ >= 384) return;
                Vec3 source = visualUnitPosition(recruit);
                Vec3 transformed = rotateAround(source, armyCenter, translationRotationDegrees).add(delta);
                drawYellowWorldPoint(graphics, camera,
                        transformed.x, transformed.y + 0.15D, transformed.z, false);
            }
        }
        if (armyCenter != null && (translationControlWasDown || Math.abs(translationRotationDegrees) >= 0.25D)) {
            drawFacingArrow(graphics, camera, armyCenter.add(delta),
                    FormationRotationMath.normalizeYaw(
                            selectedArmyFacing() + (float) translationRotationDegrees));
        }
    }

    private static void drawWorldPathSegment(GuiGraphics graphics, ScreenProjection.CameraSpec camera, Vec3 from, Vec3 to) {
        if (from == null || to == null) return;
        ScreenProjection.ProjectedPoint a = ScreenProjection.project(camera, from.x, from.y + 0.25D, from.z);
        ScreenProjection.ProjectedPoint b = ScreenProjection.project(camera, to.x, to.y + 0.25D, to.z);
        if (a != null && b != null) {
            drawScreenLine(graphics, toGuiX(a.x()), toGuiY(a.y()), toGuiX(b.x()), toGuiY(b.y()), 0xD8FFD36A);
        }
    }

    private static double horizontalDistanceClient(Vec3 a, Vec3 b) {
        return a == null || b == null ? Double.POSITIVE_INFINITY : Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static void renderRoutePreview(GuiGraphics graphics) {
        if (!routeMode || SELECTED_GROUPS.isEmpty()) {
            return;
        }
        ScreenProjection.CameraSpec camera = cameraSpec();
        Vec3 army = selectedArmyCenter();
        if (camera == null || army == null) {
            return;
        }
        Vec3 previous = army;
        for (int i = 0; i < ROUTE_POINTS.size(); i++) {
            BlockPos pos = ROUTE_POINTS.get(i);
            Vec3 world = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.35D, pos.getZ() + 0.5D);
            ScreenProjection.ProjectedPoint a = ScreenProjection.project(camera, previous.x, previous.y + 0.25D, previous.z);
            ScreenProjection.ProjectedPoint b = ScreenProjection.project(camera, world.x, world.y, world.z);
            if (a != null && b != null) {
                drawScreenLine(graphics, toGuiX(a.x()), toGuiY(a.y()), toGuiX(b.x()), toGuiY(b.y()), 0xFFFFD36A);
                int bx = toGuiX(b.x());
                int by = toGuiY(b.y());
                graphics.fill(bx - 4, by - 4, bx + 5, by + 5, 0xFFFFE7A3);
                graphics.drawString(MC.font, Integer.toString(i + 1), bx + 6, by - 5, 0xFFFFE7A3, true);
            }
            previous = world;
        }

        // Show the end arrangement at the final committed waypoint; while the route is
        // empty, show the prospective current-cursor endpoint instead.
        Vec3 previewTarget = null;
        if (!ROUTE_POINTS.isEmpty()) {
            BlockPos last = ROUTE_POINTS.get(ROUTE_POINTS.size() - 1);
            previewTarget = new Vec3(last.getX() + 0.5D, last.getY(), last.getZ() + 0.5D);
        } else {
            GroundHit hit = raycastGroundHit(cursorX, cursorY);
            if (hit != null) {
                previewTarget = new Vec3(hit.pos().getX() + 0.5D, hit.location().y, hit.pos().getZ() + 0.5D);
            }
        }
        if (previewTarget != null) {
            renderTranslatedStencilAt(graphics, previewTarget, 192);
        }
    }

    private static void renderTranslatedStencilAt(GuiGraphics graphics, Vec3 newArmyCenter, int maxPoints) {
        Vec3 oldArmyCenter = selectedArmyCenter();
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (oldArmyCenter == null || camera == null) {
            return;
        }
        double dx = newArmyCenter.x - oldArmyCenter.x;
        double dy = newArmyCenter.y - oldArmyCenter.y;
        double dz = newArmyCenter.z - oldArmyCenter.z;
        int drawn = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId == null || !SELECTED_GROUPS.contains(groupId) || drawn++ >= maxPoints) {
                continue;
            }
            Vec3 source = visualUnitPosition(recruit);
            drawYellowWorldPoint(graphics, camera, source.x + dx, source.y + dy + 0.15D, source.z + dz, false);
        }
    }

    private static Vec3 visualUnitPosition(AbstractRecruitEntity recruit) {
        if (ClientPermissionState.smallShipsAvailable()
                && recruit instanceof CaptainEntity captain
                && SmallShipsCompat.isCaptainAboard(captain)) {
            return SmallShipsCompat.shipPosition(captain);
        }
        return recruit.position();
    }

    private static void drawYellowWorldPoint(
            GuiGraphics graphics, ScreenProjection.CameraSpec camera,
            double x, double y, double z, boolean heavy
    ) {
        ScreenProjection.ProjectedPoint projected = ScreenProjection.project(camera, x, y, z);
        if (projected == null) {
            return;
        }
        int sx = toGuiX(projected.x());
        int sy = toGuiY(projected.y());
        int radius = heavy ? 3 : 2;
        graphics.fill(sx - radius, sy - radius, sx + radius + 1, sy + radius + 1, 0xC8FFD36A);
    }

    private static Map<UUID, List<AbstractRecruitEntity>> selectedLoadedGroups() {
        return loadedGroups(SELECTED_GROUPS);
    }

    private static Map<UUID, List<AbstractRecruitEntity>> loadedGroups(Collection<UUID> groupIds) {
        Map<UUID, List<AbstractRecruitEntity>> groups = new LinkedHashMap<>();
        for (UUID id : groupIds) {
            groups.put(id, new ArrayList<>());
        }
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            List<AbstractRecruitEntity> list = groups.get(recruit.getGroup());
            if (list != null) {
                list.add(recruit);
            }
        }
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return groups;
    }

    private static double clientGroupProjection(List<AbstractRecruitEntity> recruits, double lineX, double lineZ) {
        if (recruits == null || recruits.isEmpty()) {
            return 0.0D;
        }
        double x = 0.0D;
        double z = 0.0D;
        for (AbstractRecruitEntity recruit : recruits) {
            Vec3 pos = visualUnitPosition(recruit);
            x += pos.x;
            z += pos.z;
        }
        double inv = 1.0D / recruits.size();
        return x * inv * lineX + z * inv * lineZ;
    }

    /**
     * Draw the selected army's current stencil translated to the pending RMB target.
     * This previews the same formation-preserving semantics as a walk/run terrain order:
     * existing group spacing and internal arrangement are translated, not reformed.
     */
    private static void renderMovePreview(GuiGraphics graphics) {
        if (!draggingFrontage || isFrontageGesture() || SELECTED_GROUPS.isEmpty()) {
            return;
        }

        // An RMB over a hostile is an attack order, not a move. Do not lie with a
        // destination stencil in that case.
        LivingEntity attackTarget = raycastAttackTarget(cursorX, cursorY);
        if (isAttackableTarget(attackTarget)) {
            return;
        }

        GroundHit ground = raycastGroundHit(cursorX, cursorY);
        Vec3 oldArmyCenter = selectedArmyCenter();
        if (ground == null || oldArmyCenter == null) {
            return;
        }

        Vec3 newArmyCenter = new Vec3(
                ground.pos().getX() + 0.5D,
                ground.location().y,
                ground.pos().getZ() + 0.5D
        );
        if (newArmyCenter.distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            return;
        }

        double dx = newArmyCenter.x - oldArmyCenter.x;
        double dy = newArmyCenter.y - oldArmyCenter.y;
        double dz = newArmyCenter.z - oldArmyCenter.z;
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) {
            return;
        }

        int drawn = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId == null || !SELECTED_GROUPS.contains(groupId)) {
                continue;
            }
            if (drawn++ >= 256) {
                break;
            }
            Vec3 source = visualUnitPosition(recruit);
            ScreenProjection.ProjectedPoint projected = ScreenProjection.project(
                    camera,
                    source.x + dx,
                    source.y + dy + 0.15D,
                    source.z + dz
            );
            if (projected == null) {
                continue;
            }
            int x = toGuiX(projected.x());
            int y = toGuiY(projected.y());
            graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xB8FFD36A);
        }
    }

    private static String movePreviewText() {
        if (!draggingFrontage) {
            return hoverText();
        }
        if (SELECTED_GROUPS.isEmpty()) {
            return "Move preview: select at least one group";
        }
        LivingEntity attackTarget = raycastAttackTarget(cursorX, cursorY);
        if (isAttackableTarget(attackTarget)) {
            return "Attack target · release RMB to engage";
        }
        GroundHit ground = raycastGroundHit(cursorX, cursorY);
        if (ground == null) {
            return "Move preview: no valid terrain under cursor";
        }
        Vec3 destination = new Vec3(
                ground.pos().getX() + 0.5D,
                ground.location().y,
                ground.pos().getZ() + 0.5D
        );
        if (destination.distanceToSqr(anchor) > ORDER_RADIUS * ORDER_RADIUS) {
            return "Move preview: destination outside tactical radius";
        }
        boolean hastyPreview = lastTerrainOrderTarget != null
                && isHastyDoubleClick(ground.pos(), System.currentTimeMillis());
        return (hastyPreview ? "Hasty move preview" : "Move preview")
                + ": " + SELECTED_GROUPS.size() + " group(s) · release RMB to commit";
    }

    private static void renderRotationPreview(GuiGraphics graphics) {
        ScreenProjection.CameraSpec camera = cameraSpec();
        if (camera == null) {
            return;
        }
        ScreenProjection.ProjectedPoint pivotPoint = ScreenProjection.project(
                camera, rotationPivot.x, rotationPivot.y + 0.25D, rotationPivot.z
        );
        if (pivotPoint != null) {
            int px = toGuiX(pivotPoint.x());
            int py = toGuiY(pivotPoint.y());
            // Rotation owns the preview exclusively: mark the pivot, but do not draw
            // a pivot-to-cursor stroke that looks like an RMB frontage/custom line.
            graphics.fill(px - 3, py - 3, px + 4, py + 4, 0xFFFFE7A3);
        }

        FormationRotationMath.Point pivot = new FormationRotationMath.Point(rotationPivot.x, rotationPivot.z);
        int drawn = 0;
        for (RotationPreviewPoint preview : ROTATION_PREVIEW) {
            if (drawn++ >= 256) {
                break;
            }
            Vec3 world = preview.world();
            FormationRotationMath.Point rotated = FormationRotationMath.rotate(
                    new FormationRotationMath.Point(world.x, world.z),
                    pivot,
                    rotationDegrees
            );
            ScreenProjection.ProjectedPoint projected = ScreenProjection.project(
                    camera, rotated.x(), world.y + 0.15D, rotated.z()
            );
            if (projected == null) {
                continue;
            }
            int x = toGuiX(projected.x());
            int yy = toGuiY(projected.y());
            graphics.fill(x - 2, yy - 2, x + 3, yy + 3, 0xB8FFD36A);
        }

        drawFacingArrow(graphics, camera, rotationPivot, FormationRotationMath.normalizeYaw(
                rotationBaseFacingYaw + (float) rotationDegrees));
    }

    /** Shared FACING arrow for Z rotation and Alt+Ctrl transform previews. */
    private static void drawFacingArrow(
            GuiGraphics graphics, ScreenProjection.CameraSpec camera, Vec3 pivot, float previewYaw
    ) {
        ScreenProjection.ProjectedPoint pivotPoint = ScreenProjection.project(
                camera, pivot.x, pivot.y + 0.25D, pivot.z);
        double radians = Math.toRadians(previewYaw);
        double facingX = -Math.sin(radians);
        double facingZ = Math.cos(radians);
        ScreenProjection.ProjectedPoint facingTip = ScreenProjection.project(
                camera,
                pivot.x + facingX * 8.0D,
                pivot.y + 0.35D,
                pivot.z + facingZ * 8.0D
        );
        if (pivotPoint != null && facingTip != null) {
            int fromX = toGuiX(pivotPoint.x());
            int fromY = toGuiY(pivotPoint.y());
            int tipX = toGuiX(facingTip.x());
            int tipY = toGuiY(facingTip.y());
            drawScreenArrow(graphics, fromX, fromY, tipX, tipY, 0xFFFFE07A);
            graphics.drawString(MC.font, "FACING", tipX + 5, tipY - 5, 0xFFFFE7A3, true);
        }
    }

    /** Compact Total War-style missile target reticle. */
    private static void drawRangedTargetCursor(GuiGraphics graphics, int centerX, int centerY) {
        int color = 0xFFFFE07A;
        int shadow = 0xCC211F18;
        int r = 7;

        // Dark backing keeps the reticle readable over grass, snow and unit models.
        graphics.fill(centerX - r - 1, centerY - 1, centerX - 2, centerY + 2, shadow);
        graphics.fill(centerX + 3, centerY - 1, centerX + r + 2, centerY + 2, shadow);
        graphics.fill(centerX - 1, centerY - r - 1, centerX + 2, centerY - 2, shadow);
        graphics.fill(centerX - 1, centerY + 3, centerX + 2, centerY + r + 2, shadow);

        graphics.fill(centerX - r, centerY, centerX - 2, centerY + 1, color);
        graphics.fill(centerX + 3, centerY, centerX + r + 1, centerY + 1, color);
        graphics.fill(centerX, centerY - r, centerX + 1, centerY - 2, color);
        graphics.fill(centerX, centerY + 3, centerX + 1, centerY + r + 1, color);
        graphics.fill(centerX, centerY, centerX + 1, centerY + 1, 0xFFFFFFFF);

        // Four corner ticks imply a circular sight without requiring another texture asset.
        graphics.fill(centerX - 6, centerY - 6, centerX - 3, centerY - 5, color);
        graphics.fill(centerX + 4, centerY - 6, centerX + 7, centerY - 5, color);
        graphics.fill(centerX - 6, centerY + 6, centerX - 3, centerY + 7, color);
        graphics.fill(centerX + 4, centerY + 6, centerX + 7, centerY + 7, color);
    }

    private static final ItemStack IRON_SWORD_CURSOR = new ItemStack(Items.IRON_SWORD);

    /** Vanilla Minecraft iron sword, enlarged and offset so the hardware cursor cannot hide it. */
    private static void drawSwordCursor(GuiGraphics graphics, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 250.0F);
        graphics.pose().scale(1.55F, 1.55F, 1.0F);
        graphics.renderItem(IRON_SWORD_CURSOR, 0, 0);
        graphics.pose().popPose();
    }

    private static void drawScreenArrow(
            GuiGraphics graphics, int x1, int y1, int x2, int y2, int color
    ) {
        drawScreenLine(graphics, x1, y1, x2, y2, color);
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 2.0D) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        double px = -uy;
        double py = ux;
        double head = 8.0D;
        double wing = 4.5D;
        int leftX = (int) Math.round(x2 - ux * head + px * wing);
        int leftY = (int) Math.round(y2 - uy * head + py * wing);
        int rightX = (int) Math.round(x2 - ux * head - px * wing);
        int rightY = (int) Math.round(y2 - uy * head - py * wing);
        drawScreenLine(graphics, x2, y2, leftX, leftY, color);
        drawScreenLine(graphics, x2, y2, rightX, rightY, color);
    }

    private static void drawScreenLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            graphics.fill(x1 - 1, y1 - 1, x1 + 2, y1 + 2, color);
            return;
        }
        int capped = Math.min(1024, steps);
        for (int i = 0; i <= capped; i++) {
            double t = i / (double) capped;
            int x = (int) Math.round(x1 + dx * t);
            int y = (int) Math.round(y1 + dy * t);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
    }

    private static String frontagePreviewText() {
        if (!draggingFrontage || frontageStartGround == null) {
            return "Frontage: start on valid terrain";
        }
        GroundHit end = raycastGroundHit(frontageCurrentX, frontageCurrentY);
        if (end == null) {
            return "Frontage: end cursor is not on terrain";
        }
        Vec3 a = frontageStartGround.location();
        Vec3 b = end.location();
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double width = Math.min(FrontageMath.MAX_FRONTAGE, Math.sqrt(dx * dx + dz * dz));
        float yaw = FrontageMath.facingYaw(dx, dz);
        return "Frontage: " + String.format(java.util.Locale.ROOT, "%.1f", width)
                + " blocks · facing " + cardinal(yaw) + " · release to deploy";
    }

    private static String customPreviewText() {
        if (!draggingCustom || CUSTOM_STROKE.size() < 2) {
            return "Custom: Alt+RMB draw on terrain · wheel changes ranks (" + customRanks + ")";
        }
        List<CustomFormationMath.Point> path = new ArrayList<>(CUSTOM_STROKE.size());
        for (CustomStrokePoint point : CUSTOM_STROKE) {
            path.add(new CustomFormationMath.Point(point.world().x, point.world().z));
        }
        double length = CustomFormationMath.length(CustomFormationMath.sanitize(path));
        return "Custom: " + CUSTOM_STROKE.size() + " pts · "
                + String.format(java.util.Locale.ROOT, "%.1f", length) + " blocks · "
                + customRanks + (customRanks == 1 ? " rank" : " ranks") + " · release to deploy";
    }

    private static String cardinal(float yaw) {
        float normalized = (yaw % 360.0F + 360.0F) % 360.0F;
        if (normalized < 22.5F || normalized >= 337.5F) return "S";
        if (normalized < 67.5F) return "SW";
        if (normalized < 112.5F) return "W";
        if (normalized < 157.5F) return "NW";
        if (normalized < 202.5F) return "N";
        if (normalized < 247.5F) return "NE";
        if (normalized < 292.5F) return "E";
        return "SE";
    }

    private static void drawBoxOutline(GuiGraphics graphics, int minX, int minY, int maxX, int maxY, int color) {
        graphics.fill(minX, minY, maxX + 1, minY + 1, color);
        graphics.fill(minX, maxY, maxX + 1, maxY + 1, color);
        graphics.fill(minX, minY, minX + 1, maxY + 1, color);
        graphics.fill(maxX, minY, maxX + 1, maxY + 1, color);
    }

    private static String hoverText() {
        if (!commandCursor) {
            return "Press X to return to point-and-click commands.";
        }
        if (hoveredRecruit != null) {
            UUID groupId = validOwnedGroupId(hoveredRecruit);
            RecruitsGroup group = groupId == null ? null : findOwnedClientGroup(groupId);
            return group == null ? "Owned recruit" : "Select group: " + group.getName();
        }
        if (isAttackableTarget(hoveredTarget)) {
            return (selectedAttackPrefersRangedTargeting() ? "Ranged target: " : "Attack: ")
                    + hoveredTarget.getDisplayName().getString();
        }
        if (hoveredGround != null) {
            return "Move / deploy";
        }
        return "No valid command target";
    }

    private static int hoverColor() {
        if (!commandCursor) {
            return 0xFFB8B8B8;
        }
        if (hoveredRecruit != null) {
            return 0xFF65F28B;
        }
        if (isAttackableTarget(hoveredTarget)) {
            return 0xFFF2F2E8;
        }
        if (hoveredGround != null) {
            return 0xFFFFD36A;
        }
        return 0xFFAAAAAA;
    }

    /** Apply the authoritative formation memory sent by the server after login/dimension change. */
    public static void applyPersistentFormationSync(
            Map<UUID, FormationKind> formations,
            Set<UUID> customGroups,
            Set<UUID> looseSpacingGroups
    ) {
        LOCAL_FORMATIONS.clear();
        LOCAL_CUSTOM_GROUPS.clear();
        LOCAL_LOOSE_SPACING_GROUPS.clear();
        if (formations != null) {
            LOCAL_FORMATIONS.putAll(formations);
        }
        if (customGroups != null) {
            LOCAL_CUSTOM_GROUPS.addAll(customGroups);
            for (UUID id : customGroups) {
                LOCAL_FORMATIONS.remove(id);
            }
        }
        if (looseSpacingGroups != null) {
            LOCAL_LOOSE_SPACING_GROUPS.addAll(looseSpacingGroups);
        }
    }

    private static String artilleryHudText(int attachedGuns) {
        StringBuilder text = new StringBuilder("Artillery: ").append(attachedGuns)
                .append(" attached gun").append(attachedGuns == 1 ? "" : "s");
        if (hoveredGround == null) {
            return text.append(" · F designate fire").toString();
        }
        Vec3 target = Vec3.atCenterOf(hoveredGround);
        int inRange = 0;
        String state = null;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId == null || !SELECTED_GROUPS.contains(groupId)
                    || !(recruit instanceof SiegeEngineerEntity engineer)
                    || !SiegeWeaponsCompat.isAttachedToSiegeWeapon(engineer)) {
                continue;
            }
            if (SiegeWeaponsCompat.targetInRange(engineer, target)) {
                inRange++;
            }
            if (state == null) {
                state = SiegeWeaponsCompat.firingState(engineer);
            }
        }
        text.append(" · ").append(inRange).append('/').append(attachedGuns).append(" in range");
        if (state != null && attachedGuns == 1) {
            text.append(" · ").append(state);
        }
        return text.toString();
    }

    private static int selectedShips() {
        if (!ClientPermissionState.smallShipsAvailable() || SELECTED_GROUPS.isEmpty()) {
            return 0;
        }
        int ships = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId != null && SELECTED_GROUPS.contains(groupId)
                    && recruit instanceof CaptainEntity captain
                    && SmallShipsCompat.isCaptainAboard(captain)) {
                ships++;
            }
        }
        return ships;
    }

    private static int selectedArtilleryGuns() {
        if (!ClientPermissionState.siegeWeaponsAvailable() || SELECTED_GROUPS.isEmpty()) {
            return 0;
        }
        int guns = 0;
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            if (groupId != null && SELECTED_GROUPS.contains(groupId)
                    && recruit instanceof SiegeEngineerEntity engineer
                    && SiegeWeaponsCompat.isAttachedToSiegeWeapon(engineer)) {
                guns++;
            }
        }
        return guns;
    }

    private static String selectedFormationText() {
        if (SELECTED_GROUPS.isEmpty()) {
            return formationTool.displayName() + " (tool)";
        }

        int customCount = 0;
        for (UUID groupId : SELECTED_GROUPS) {
            if (LOCAL_CUSTOM_GROUPS.contains(groupId)) {
                customCount++;
            }
        }
        if (customCount == SELECTED_GROUPS.size()) {
            return "Custom";
        }
        if (customCount > 0) {
            return "Mixed";
        }

        FormationKind common = null;
        boolean unknown = false;
        for (UUID groupId : SELECTED_GROUPS) {
            FormationKind known = LOCAL_FORMATIONS.get(groupId);
            if (known == null) {
                unknown = true;
                continue;
            }
            if (common == null) {
                common = known;
            } else if (common != known) {
                return "Mixed";
            }
        }
        if (common == null) {
            return "Auto / existing";
        }
        if (unknown) {
            return "Mixed / auto";
        }
        return common.displayName();
    }

    private static Map<UUID, GroupSummary> selectedGroupSummaries() {
        Map<UUID, GroupSummary> result = new LinkedHashMap<>();
        for (UUID groupId : SELECTED_GROUPS) {
            RecruitsGroup group = findOwnedClientGroup(groupId);
            if (group != null) {
                result.put(groupId, new GroupSummary(group.getName(), 0));
            }
        }
        for (AbstractRecruitEntity recruit : loadedOwnedRecruits()) {
            UUID groupId = recruit.getGroup();
            GroupSummary summary = result.get(groupId);
            if (summary != null) {
                result.put(groupId, new GroupSummary(summary.name(), summary.loadedCount() + 1));
            }
        }
        return result;
    }

    private static String selectedSummaryText(Map<UUID, GroupSummary> summaries) {
        if (summaries.isEmpty()) {
            return "Selected: none";
        }
        StringBuilder text = new StringBuilder("Selected: ");
        int shown = 0;
        for (GroupSummary summary : summaries.values()) {
            if (shown >= 3) {
                break;
            }
            if (shown > 0) {
                text.append(" · ");
            }
            text.append(summary.name()).append(" (").append(summary.loadedCount()).append(')');
            shown++;
        }
        int remaining = summaries.size() - shown;
        if (remaining > 0) {
            text.append(" · +").append(remaining).append(" more");
        }
        return text.toString();
    }

    private static double toPhysicalX(double guiX) {
        return guiX * MC.getWindow().getWidth() / Math.max(1.0D, MC.getWindow().getGuiScaledWidth());
    }

    private static double toPhysicalY(double guiY) {
        return guiY * MC.getWindow().getHeight() / Math.max(1.0D, MC.getWindow().getGuiScaledHeight());
    }

    private static int toGuiX(double physicalX) {
        return (int) Math.round(physicalX * MC.getWindow().getGuiScaledWidth() / MC.getWindow().getWidth());
    }

    private static int toGuiY(double physicalY) {
        return (int) Math.round(physicalY * MC.getWindow().getGuiScaledHeight() / MC.getWindow().getHeight());
    }

    private static void setStatus(String text) {
        status = text;
        statusTicks = 100;
        message(text);
    }

    private static void message(String text) {
        if (MC.player != null) {
            MC.player.displayClientMessage(Component.literal(text), true);
        }
    }

    private enum CapturedCommand {
        TOGGLE,
        CLEAR,
        MOVEMENT_PACE,
        SELECTION_GROUP,
        ROTATE,
        ARMY_PRESET,
        STRATEGIC_FIRE,
        HOLD_FIRE,
        FIRE_AT_WILL,
        ROUTE_MODE,
        COMMIT_ROUTE,
        RESET_ROUTE,
        HALT
    }

    private record GlowState(AbstractRecruitEntity entity, boolean original) {
    }

    private record RayContext(Vec3 start, Vec3 end) {
    }

    private record GroundHit(BlockPos pos, Vec3 location, String source) {
    }

    private record CustomStrokePoint(double screenX, double screenY, Vec3 world) {
    }

    private record GroupSummary(String name, int loadedCount) {
    }

    private record RotationPreviewPoint(Vec3 world) {
    }

    private record CurrentOrderPreview(
            List<Vec3> waypoints, Vec3 destination, Vec3 frontageStart, Vec3 frontageEnd, long issuedMillis
    ) {
    }

    public record FogVisionSource(Vec3 position, double radius) {
    }
}
