#!/usr/bin/env python3
"""Targeted source contracts for Commander 0.15.4 behavior."""

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

require(formation, "applyFormationFacing(recruit, mover,", "formation movement needs one facing owner")
require(formation, "arrived ? memberHeading : headingTo(mover.position(), desired, memberHeading)",
        "stationary members must face their slot while rotating, then restore final facing")
require(formation, "state.travelHeading", "travelling members must retain the shared route tangent")
require(formation, "addGoal(-10, lease.goal)", "mounted formations need exclusive horse goal ownership")
require(formation, "Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET",
        "horse puppet goal must suppress every competing control channel")
require(formation, "signedUnit(seed) * SLOT_JITTER_RIGHT",
        "formation slots need stable bounded human variation")
require(cavalry, "Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET",
        "mounted tactical combat must keep the horse under rider control")
forbid(formation[formation.index("private static void claim("):formation.index("private static void releaseMembers(")],
       "ownerRot", "claim must not compete for rotation")
require(formation, "new Vec3(goal.x, resolved.y, goal.z)",
        "completed routes must preserve the exact horizontal destination")
require(formation, "float slotHeading = travelling ? state.travelHeading : state.heading;",
        "formation geometry must face the route tangent and restore ordered facing at arrival")
require(formation, "QUICK_PATH_BUDGET_NANOS = 20_000_000L",
        "difficult route planning must use the 20 ms staged-planning budget")
require(formation, "state.routeGoal = requestedDestination",
        "partial safe routes must retain their continuation goal")

require(pathfinder, "mover instanceof AbstractHorse", "cavalry rises need a dedicated route cost")
require(steering, "recruit.getVehicle() == mover && mover.onGround()",
        "mounted local steering must actively jump a safe rise")
require(formation, "recruit.getVehicle() == mover && mover.onGround()",
        "mounted tactical waypoints must actively jump a safe rise")
require(pathfinder, "if (!dryAt(level, mover, feet)) return null;", "fluid rejection must remain")
require(pathfinder, "if (shape.isEmpty()) return null;", "unsupported terrain rejection must remain")

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

require(network, 'PROTOCOL = "rts-camera-150"', "wire-compatible release must retain protocol 150")
require(source("gradle.properties"), "mod_version=0.15.4-server.1", "semantic version must be updated")

print("0.15.4 source contracts passed")
