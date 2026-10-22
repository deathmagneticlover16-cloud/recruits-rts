#!/usr/bin/env python3
"""Targeted source contracts for Commander 0.15.8 behavior."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(text: str, token: str, description: str) -> None:
    if token not in text:
        raise AssertionError(description)


def forbid(text: str, token: str, description: str) -> None:
    if token in text:
        raise AssertionError(description)


formation = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/NativeFormationController.java")
pathfinder = source("src/main/java/ee/lastpagans/recruitsrtscamera/navigation/TacticalPathfinder.java")
steering = source("src/main/java/ee/lastpagans/recruitsrtscamera/navigation/LocalFormationSteering.java")
keys = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/RTSCameraKeyMappings.java")
controller = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/RTSCameraController.java")
overlay = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CommanderOverlay.java")
facade = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/RTSCommandFacade.java")
gate = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/InputGate.java")
network = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/RTSCameraNetwork.java")
rotate_packet = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/RotateFormationPacket.java")
translate_packet = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/TranslateGroupsPacket.java")
ui_ids = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CommanderUiElementIds.java")
ui_registry = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CommanderUiRegistry.java")
cavalry = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/CavalryTacticalController.java")
selection_groups = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/SelectionGroupManager.java")
face_mixin = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/NativeFaceCommandMixin.java")
movement = source("src/main/java/ee/lastpagans/recruitsrtscamera/movement/RTSMovementBoostManager.java")
attack = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/RTSGroupAttackManager.java")
tactical = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/TacticalCombatManager.java")
cavalry_combat = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/CavalryCombatManager.java")
mode_packet = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/RTSModeStatePacket.java")
attack_packet = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/AttackGroupsPacket.java")
animation_hints = source("src/main/java/ee/lastpagans/recruitsrtscamera/animation/CommanderAnimationHints.java")
slots = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/FormationSlotMath.java")
formation_service = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/FormationService.java")
rumble = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/CavalryRumbleManager.java")
rumble_client = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CavalryRumbleClient.java")
scheduler = source("src/main/java/ee/lastpagans/recruitsrtscamera/runtime/RecruitAIScheduler.java")
stats = source("src/main/java/ee/lastpagans/recruitsrtscamera/troops/TroopStatistic.java")
stat_registry = source("src/main/java/ee/lastpagans/recruitsrtscamera/troops/TroopStatRegistry.java")

require(formation, "applyFormationFacing(recruit, mover,", "formation movement needs one facing owner")
require(formation, "? memberHeading : headingTo(mover.position(), desired, memberHeading)",
        "stationary members must face their slot while rotating, then restore final facing")
require(formation, "state.travelHeading", "travelling members must retain the shared route tangent")
require(formation, "addGoal(-10, lease.goal)", "mounted formations need exclusive horse goal ownership")
require(formation, "Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET",
        "horse puppet goal must suppress every competing control channel")
require(formation, "signedUnit(seed) * SLOT_JITTER_RIGHT",
        "formation slots need stable bounded human variation")
require(cavalry, "Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET",
        "mounted tactical combat must keep the horse under rider control")
forbid(cavalry, "s.mount.getNavigation().stop();\n                if (s.maneuver.blocked",
       "combat stall recovery must not inject an animation-breaking stop")
require(cavalry, "lastNavigationGoal.distanceToSqr(navigationGoal) > 1.5D * 1.5D",
        "combat navigation must avoid redundant path resets")
forbid(cavalry, "if (++s.repath < 10) return;",
       "combat maneuvers must not wait after reaching an intermediate waypoint")
forbid(formation[formation.index("private static void claim("):formation.index("private static void releaseMembers(")],
       "ownerRot", "claim must not compete for rotation")
require(formation, "new Vec3(goal.x, resolved.y, goal.z)",
        "completed routes must preserve the exact horizontal destination")
require(formation, "float slotHeading = state.slotHeading;",
        "formation geometry must face the route tangent and restore ordered facing at arrival")
require(formation, "worldSlotPoints(start, heading, offsets)",
        "new orders must assign slots against live geometry instead of the distant destination")
require(formation, "stabilizeMountedSlots(state, members, target)",
        "mounted direction reversals must choose the nearest physical slots once")
require(formation, "approachHeading(state.slotHeading, target, 9.0F)",
        "formation geometry must ease through route bends without slot snapping")
require(formation, "mover.setDeltaMovement(momentum)",
        "manual-control handoffs must preserve momentum for continuous animation")
require(formation, "state.appliedHeadings.getOrDefault(recruitId, mount.getYRot())",
        "mounted smoothing must use the last authoritative yaw instead of MoveControl feedback")
require(formation, "QUICK_PATH_BUDGET_NANOS = 20_000_000L",
        "difficult route planning must use the 20 ms staged-planning budget")
require(formation, "state.routeGoal = requestedDestination",
        "partial safe routes must retain their continuation goal")

require(pathfinder, "mover instanceof AbstractHorse", "cavalry rises need a dedicated route cost")
require(steering, "recruit.getVehicle() == mover && mover.onGround()",
        "mounted local steering must actively jump a safe rise")
require(steering, "recruit.getVehicle() == null ? separation(recruit) : Vec3.ZERO",
        "mounted steering must not alternate lateral neighbor impulses")
require(steering, "forward.scale(0.45D / forwardLength)",
        "mounted MoveControl needs forward lookahead to avoid stop-start animation")
require(steering, "lateralLimit = slotDistance > 1.50D ? 0.12D : 0.045D",
        "mounted slot correction must bound side-to-side steering")
require(formation, "recruit.getVehicle() == mover && mover.onGround()",
        "mounted tactical waypoints must actively jump a safe rise")
require(pathfinder, "if (!dryAt(level, mover, feet)) return null;", "fluid rejection must remain")
require(pathfinder, "if (shape.isEmpty()) return null;", "unsupported terrain rejection must remain")
require(pathfinder, "segmentSafe(level, mover", "safe open routes must be straightened")
require(pathfinder, "requiredAnchors.contains", "route smoothing must preserve constrained terrain anchors")
require(pathfinder, "SEARCH_TIME_BUDGET_NANOS = 20_000_000L",
        "each tactical search must yield a safe partial route before a multi-second tick spike")

require(keys, "GLFW.GLFW_KEY_E", "E must move the camera up")
require(keys, "GLFW.GLFW_KEY_Q", "Q must move the camera down")
require(keys, "GLFW.GLFW_KEY_SPACE", "Space must be the planned-order hold action")
forbid(gate, '"key.jump"', "Space must not pass through as Freecam vertical input")
forbid(gate, '"key.sneak"', "Ctrl/sneak must not pass through as Freecam vertical input")
require(controller, "visible.addAll(CURRENT_ORDER_PREVIEWS.keySet())",
        "Space preview must include unselected planned orders")
require(controller, "deliberately has no moving crosshair/attack artwork",
        "the visible moving cursor must stay removed")
require(controller, "translationRotationDegrees", "Alt translation needs retained Ctrl rotation")
require(controller, "GLFW.GLFW_KEY_LEFT_CONTROL", "Ctrl must rotate during Alt translation")
require(controller, "freeCamera.setPos(anchor.x, anchor.y + 14.0D, anchor.z)",
        "RTS camera must begin fourteen blocks above the player")
require(controller, "-scrollDelta * step", "the wheel's vertical direction must be inverted")
require(controller, "drawFacingArrow(graphics, camera, armyCenter.add(delta)",
        "Alt+Ctrl must render the shared FACING arrow")
require(controller, "InputConstants.isKeyDown", "vertical movement must use physical key state")
require(controller, "RTSCameraKeyMappings.CAMERA_UP.setDown(false)",
        "released vertical keys must clear stale mapping state")
require(translate_packet, "RotateFormationPacket.markTranslated", "combined transforms must compose in packet order")
require(rotate_packet, "FormationRuntime.formationDestination", "combined rotation must use translated destinations")

require(overlay, "GLFW.GLFW_MOD_SHIFT", "unit cards need Shift range selection")
require(overlay, "cardSelectionAnchor", "unit cards need a stable plain-click anchor")
require(facade, "Select the inclusive interval in the full ordered card list, across pages.",
        "range selection must use the full ordered card list")
require(ui_ids, 'MAIN_COMMAND_BAR = "main.command_bar"', "command bar needs a stable semantic ID")
require(ui_ids, 'STAT_FATIGUE = "stat.fatigue"', "fatigue bar needs a stable semantic ID")
require(ui_registry, "public static List<Element> snapshot()", "UI editor needs a public element snapshot")
require(selection_groups, "key == GLFW.GLFW_KEY_BACKSPACE", "Backspace must unbind a direct group hotkey")
require(selection_groups, "BUNDLES.containsKey(bundleSlot)",
        "number hotkeys must only be blocked by an occupied bundle slot")
require(selection_groups, "!DIRECT_HOTKEYS.containsKey(keyForBundleSlot(candidate))",
        "bundle creation must skip numbers occupied by direct hotkeys")
require(face_mixin, "FormationService.rememberFacing", "native Face must persist its facing lock")
require(formation, "CENTER_SPEED = 0.21D", "foot formation travel must be 1.5x faster")
require(formation, "FOOT_NAV_SPEED = 1.17D", "foot slot movement and rotation must be 1.5x faster")
require(movement, "REGULAR_INFANTRY_NAV_SPEED = 1.17D", "regular foot navigation must be 1.5x faster")
require(movement, "HASTY_INFANTRY_SPEED = 1.62D", "running foot navigation must be 1.5x faster")
require(movement, "SLOW_MARCH_CAVALRY_NAV_SPEED = 0.68D", "cavalry slow pace must remain unchanged")
require(movement, "REGULAR_CAVALRY_MOUNT_MULTIPLIER = 1.10D",
        "ordinary formation cavalry must receive the requested 2.10x mount speed")
require(movement, "TacticalCombatManager.chargeAcceleration(recruit)",
        "mounted charges must progressively accelerate")
require(tactical, "return 1.10D + 0.30D * progress;",
        "melee charges must snowball to a bounded speed bonus")
require(tactical, "rearImpact(attacker, victim) ? 2.0D : 1.0D",
        "rear charge impacts must apply double morale shock")
require(cavalry_combat, "TacticalCombatManager.rearImpact(rider, victim) ? 2.0D : 1.0D",
        "mounted rear impacts must apply double morale shock")
require(attack, "FormationRuntime.retargetFormation", "attack approaches must retarget without resetting movement")
require(attack, "state.charge && state.approachKind == ApproachKind.INFANTRY",
        "melee charges must retain formation through first contact")
require(mode_packet, "FormationStateSyncPacket.forPlayer(sender)",
        "entering RTS mode must restore saved loose-spacing state")
require(attack_packet, "import ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager;",
        "charge-order timing must import its declaring combat manager")
require(animation_hints, 'MOVEMENT_KEY = "RecruitsRTSMovementState"',
        "animation integrations need a stable semantic movement-state key")
require(animation_hints, "public enum MovementState { IDLE, WALKING, RUNNING }",
        "animation integrations need explicit walking and running states")
require(animation_hints, "IDLE_HYSTERESIS_TICKS = 6",
        "one-tick movement handoffs must not reset locomotion animations")
require(animation_hints, "synchronizeSprintState(recruit, next)",
        "RTS run intent must reach sprint-based SPA animation controllers")
require(slots, "case CAVALRY_WEDGE -> addCavalryWedge", "wedge needs dedicated filled geometry")
require(slots, "1.7D * (spacing <= 1.10D ? 1.30D : 1.0D)",
        "normal mounted geometry must be 1.3x wider without widening loose spacing twice")
require(formation_service, "previous.spacing() > 1.10D", "frontage dragging must preserve loose spacing")
require(formation, "state.rotateForTravel = horizontalDistanceSq(state.center, destination) > 4.0D * 4.0D",
        "short orders must not rotate the whole formation out and back")
require(formation, "mover != recruit && (travelling || !arrived)",
        "mounted bodies must retain the route tangent through slow arrival cohesion")
require(formation, "? slotError > 4.0D * 4.0D || heightError > 1.35D ? 1.08D",
        "mounted laggards need bounded catch-up instead of teleport-like surges")
require(cavalry_combat, "MOUNT_HEALTH_BONUS = 0.75D", "managed recruited mounts need extra health")
require(rumble, "riders * speed * emphasis / 15.0D", "rumble gain must use the reduced riders x speed scale")
require(rumble, "(wedge ? 1.15D : 1.0D) * (charging ? 1.15D : 1.0D)",
        "wedge and charge sound emphasis must stack")
require(rumble, "SAMPLE_TICKS = 5", "rumble sampling must avoid an every-tick server scan")
require(rumble, "new CavalryRumblePacket(groupId, false", "stopped groups need an explicit client stop")
require(rumble_client, "FADE_IN_STEP = 1.0F / 120.0F", "rumble needs a very slow fade in")
require(rumble_client, "FADE_OUT_STEP = 1.0F / 120.0F", "rumble needs a very slow fade out")
require(rumble_client, "looping = true", "rumble must be a controllable loop, not fire-and-forget audio")
require(rumble_client, "public boolean canStartSilent()", "a zero-gain fade-in must not be culled at startup")
require(rumble_client, "CAVALRY_RUMBLE_DISTANT", "distance filtering needs a separately filtered audio layer")
require(rumble_client, "smoothStep(FILTER_START, FILTER_END, distance)",
        "the low-pass layer must crossfade gradually with listener distance")
require(scheduler, "SERVER_TICK_WORK_BUDGET_NANOS = 15_000_000L",
        "recovery paths need an aggregate tick-time guard during initial cohesion")
require(overlay, "COMMAND_CAVALRY_WEDGE", "cavalry wedge needs a stable command-bar element")
require(overlay, "bodyguardFollow, cavalryWedge", "cavalry wedge must be the right-most command")
for key in ("DAMAGE", "MELEE_SKILL", "MELEE_DEFENCE", "CHARGE_BONUS", "SPEED", "MORALE",
            "FATIGUE", "ACCURACY", "AMMUNITION", "RATE_OF_FIRE"):
    require(stats, key + "(\"stat.", "all ten troop-wide statistics must have stable IDs")
require(stat_registry, "Map<UUID, TroopStatProfile>", "statistics must be stored per troop group")
require(controller, "ORDER_RADIUS = 288.0D", "temporary client order reach must be tripled")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/network/MoveGroupsPacket.java"),
        "MAX_TARGET_DISTANCE = 336.0D", "server move-order reach must be tripled")

require(network, 'PROTOCOL = "rts-camera-157"', "rumble state packet requires protocol 157")
require(source("gradle.properties"), "mod_version=0.15.8-server.1", "semantic version must be updated")

print("0.15.8 source contracts passed")
