#!/usr/bin/env python3
"""Targeted source contracts for Commander 0.15.14 behavior."""

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
mounted_motion = source("src/main/java/ee/lastpagans/recruitsrtscamera/navigation/MountedFormationMotion.java")
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
formation_packet = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/SetFormationPacket.java")
animation_hints = source("src/main/java/ee/lastpagans/recruitsrtscamera/animation/CommanderAnimationHints.java")
slots = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/FormationSlotMath.java")
formation_service = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/FormationService.java")
rumble = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/CavalryRumbleManager.java")
triumph = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/MountedTriumphManager.java")
spa = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/SeriousAnimationsCompat.java")
rumble_client = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CavalryRumbleClient.java")
icy_horse_sounds = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/IcyHorseSoundsCompat.java")
mount_push = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/RTSMountEntityPushMixin.java")
mount_control = source("src/main/java/ee/lastpagans/recruitsrtscamera/movement/RTSMountControlRegistry.java")
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
forbid(formation, "SLOT_JITTER", "authored formation slots must not receive random movement offsets")
forbid(formation, "signedUnit(", "slot destinations must remain exact and deterministic")
require(cavalry, "Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET",
        "mounted tactical combat must keep the horse under rider control")
forbid(cavalry, "s.mount.getNavigation().stop();\n                if (s.maneuver.blocked",
       "combat stall recovery must not inject an animation-breaking stop")
require(cavalry, "lastNavigationGoal.distanceToSqr(navigationGoal) > 2.5D * 2.5D",
        "combat navigation must avoid redundant path resets")
require(cavalry, "approachHeading(state.appliedHeading, desiredYaw, 4.5F)",
        "combat cavalry must ease rider and mount yaw through the broad turn")
require(cavalry, "CommanderAnimationHints.updateMovement(s.rider, true, true)",
        "the complete cavalry attack cycle must retain its running animation")
forbid(cavalry, "now - s.lastNavigationAt >= 30L",
       "accepted combat paths must not be periodically restarted")
require(cavalry, "!s.navigationAccepted && now - s.lastNavigationAt >= 5L",
        "rejected combat paths need a bounded retry interval")
require(cavalry, "s.lastNavigationAt = now;",
        "a rejected path must not falsify its timestamp and retry every tick")
require(cavalry, "TacticalPathfinder.canSteerDirect(",
        "cavalry must terrain-check a direct pass through an occupied frontage")
require(cavalry, "s.mount.getMoveControl().setWantedPosition(",
        "a safe committed pass must not stop when navigation reaches the enemy line")
require(cavalry, "s.mount.setDeltaMovement(momentum);",
        "navigation-to-pass control must preserve animation momentum")
maneuver = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/CavalryManeuver.java")
require(maneuver, "TURN_RADIUS = 12.0D", "cavalry needs a broad fly-by hairpin")
require(maneuver, "TURN_STEPS = 12", "cavalry hairpins need small stable steering changes")
require(maneuver, "PASS_DISTANCE = 22.0D", "cavalry must clear the victim before turning")
require(maneuver, "return turnWaypoint(turnStep);", "pass-to-turn handoff must not insert a stopped tick")
require(maneuver, "turnPoint.add(direction.scale(-6.0D))", "hairpin exit must carry motion into replanning")
forbid(cavalry, "if (++s.repath < 10) return;",
       "combat maneuvers must not wait after reaching an intermediate waypoint")
forbid(formation[formation.index("private static void claim("):formation.index("private static void releaseMembers(")],
       "ownerRot", "claim must not compete for rotation")
require(formation, "new Vec3(goal.x, resolved.y, goal.z)",
        "completed routes must preserve the exact horizontal destination")
require(formation, "state.slotHeading = state.heading;",
        "authored formation geometry must remain fixed while bodies face the route tangent")
require(formation, "worldSlotPoints(start, heading, offsets)",
        "new orders must assign slots against live geometry instead of the distant destination")
forbid(formation, "stabilizeMountedSlots", "movement must never reassign occupied formation slots")
require(formation, "cohesionTarget = Math.max(0.20D",
        "the virtual centre must slow while physical members assemble")
require(formation, "cohesionTarget - state.cohesionScale, -0.04D, 0.04D",
        "cohesion pace changes must be eased instead of pulsing")
require(formation, "mover.setDeltaMovement(momentum)",
        "manual-control handoffs must preserve momentum for continuous animation")
require(formation, "state.appliedHeadings.getOrDefault(recruitId, mount.getYRot())",
        "mounted smoothing must use the last authoritative yaw instead of MoveControl feedback")
require(formation, "QUICK_PATH_BUDGET_NANOS = 20_000_000L",
        "difficult route planning must use the 20 ms staged-planning budget")
require(formation, "state.routeGoal = requestedDestination",
        "partial safe routes must retain their continuation goal")

require(pathfinder, "mover instanceof AbstractHorse", "cavalry rises need a dedicated route cost")
require(steering, "if (mover.onGround())",
        "mounted local steering must actively jump a safe rise")
require(steering, "if (recruit.getVehicle() == mover)",
        "mounted steering must branch before foot separation is applied")
require(mounted_motion, "formationX / formationSpeed",
        "mounted velocity must follow the continuous shared formation vector")
require(mounted_motion, "lateralCurrentX * 0.45D",
        "mounted slot correction must damp lateral overshoot")
require(mounted_motion, "slotDistance > 1.5D ? 0.065D : 0.032D",
        "mounted lateral authority must remain bounded relative to walking speed")
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
require(movement, "GROUP_ACCELERATION_PER_TICK = 0.015D",
        "formation pace changes need a bounded shared acceleration")
require(movement, "replacement.currentStep = previous.currentStep",
        "Walk-to-Run must carry the current anchor speed across mode replacement")
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
require(overlay, "current == FormationKind.CAVALRY_WEDGE", "the wedge command must toggle its active state")
require(overlay, "? FormationKind.SQUARE", "a second wedge click must restore the default square")
require(formation_service, "if (active == FormationKind.CAVALRY_WEDGE)",
        "frontage dragging must recognize an active cavalry wedge")
require(formation_service, "destination, FormationKind.CAVALRY_WEDGE",
        "frontage dragging must position a wedge without replacing its geometry")
require(formation_service, "FormationSlotMath.bestRanksForWidth(",
        "server frontage must fit ranks using production mounted spacing")
require(controller, "FormationSlotMath.create(",
        "frontage preview must use the same production slot generator as the server")
require(controller, "LOCAL_FORMATIONS.get(groupId) == FormationKind.CAVALRY_WEDGE",
        "frontage preview must retain active wedge geometry")
require(controller, "FormationSlotMath.bestRanksForWidth(",
        "frontage preview must mirror mounted production rank fitting")
require(spa, 'Class.forName("com.lastpagans.recruitsspa.RecruitsAnimationsApi")',
        "SPA mounted triumph must resolve the published API package")
for method in ("playMountedTriumph", "canPlayMountedTriumph", "isMountedTriumphActive",
               "mountedTriumphDurationTicks"):
    require(spa, method, "SPA mounted triumph bridge is incomplete: " + method)
require(triumph, "averageMorale <= MIN_MORALE_EXCLUSIVE", "mounted triumph requires morale above 35")
require(triumph, "OPPORTUNITY_CHANCE = 0.30F", "mounted triumph needs the increased occasional rate")
require(triumph, "recruit.getMainHandItem().isEmpty()", "mounted triumph must preflight its hand item")
require(rumble, "sample.riders * 2 <= sample.totalMembers", "triumph must only sample cavalry formations")
require(rumble, "charging || running || wedge", "triumph eligibility must include charge, run and wedge")
require(steering, "private static boolean steerMounted(",
        "mounted open-ground movement needs one continuous velocity controller")
require(steering, "setWantedPosition(mover.getX(), mover.getY(), mover.getZ(), 0.0D)",
        "mounted steering must neutralize MoveControl's competing yaw target")
require(mounted_motion, "currentX * 0.30D + requestedX * 0.70D",
        "mounted velocity changes must be damped across ticks")
require(steering, "TacticalPathfinder.canSteerDirect(level, mover, probe)",
        "direct mounted velocity must retain terrain and hole safety checks")
require(formation_packet, "packet.kind == FormationKind.CAVALRY_WEDGE || packet.kind == FormationKind.SQUARE",
        "wedge and its square toggle must use one native formation controller")
require(formation, "mover != recruit && (travelling || !arrived)",
        "mounted bodies must retain the route tangent through slow arrival cohesion")
require(formation, "? slotError > 4.0D * 4.0D || heightError > 1.35D ? 1.08D",
        "mounted laggards need bounded catch-up instead of teleport-like surges")
require(formation, "cohesionDistance = mountedMember ? 0.85D : 1.55D",
        "mounted cohesion must tighten before the virtual centre resumes full pace")
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
require(rumble_client, "FILTER_START = 8.0D", "close rumble must retain its bright positional layer")
require(rumble_client, "FILTER_END = 34.0D", "thirty-to-forty block rumble must be almost entirely bass")
require(icy_horse_sounds, "NEIGH_CHANCE = 0.075F", "running horse neighs must remain rare")
require(icy_horse_sounds, "SNORT_CHANCE = 0.35F", "running horse snorts must be more frequent")
require(icy_horse_sounds, "info.getModId() + \" \" + info.getDisplayName()",
        "Icy integration must discover optional mod namespaces without a hard dependency")
require(icy_horse_sounds, "path.contains(\"neigh\") || path.contains(\"whinny\")",
        "Icy integration must discover both common neigh registry names")
require(icy_horse_sounds, "level.playSound(null, horse.getX(), horse.getY(), horse.getZ()",
        "horse vocalizations must be positional at an individual mount")
require(rumble, "IcyHorseSoundsCompat.maybePlay", "moving cavalry sampling must drive optional vocals")
require(mount_push, "RTSMountControlRegistry.controls(self) || RTSMountControlRegistry.controls(other)",
        "controlled mounts must not receive alternating entity shove impulses")
require(mount_push, "callback.cancel();", "RTS mount entity pushes must be cancelled at their source")
require(mount_control, "CLAIMS.merge(mount.getUUID(), 1, Integer::sum)",
        "formation-to-combat mount handoffs need reference-counted collision ownership")
require(formation, "RTSMountControlRegistry.claim(mount)", "formation movement must claim mount collision control")
require(cavalry, "RTSMountControlRegistry.claim(mount)", "combat passes must claim mount collision control")
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
require(source("gradle.properties"), "mod_version=0.15.14-server.1", "semantic version must be updated")

print("0.15.14 source contracts passed")
