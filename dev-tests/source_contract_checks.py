#!/usr/bin/env python3
"""Targeted source contracts for Commander 0.17.0 behavior."""

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
client_battle = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/ClientBattleState.java")
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
lance_compat = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/EpicKnightsLanceCompat.java")
lance_sync = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/LancePoseSyncPacket.java")
rumble_client = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CavalryRumbleClient.java")
group_flag_client = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/GroupFlagClientState.java")
group_flag_screen = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/ImmersivePaintingsFlagScreen.java")
group_flag_data = source("src/main/java/ee/lastpagans/recruitsrtscamera/persistence/RTSGroupFlagSavedData.java")
group_flag_command = source("src/main/java/ee/lastpagans/recruitsrtscamera/server/GroupFlagCommand.java")
group_flag_set = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/SetGroupFlagPacket.java")
group_flag_sync = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/GroupFlagStateSyncPacket.java")
mounted_facing_client = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/MountedFacingClient.java")
icy_horse_sounds = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/IcyHorseSoundsCompat.java")
mount_push = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/RTSMountEntityPushMixin.java")
mount_control = source("src/main/java/ee/lastpagans/recruitsrtscamera/movement/RTSMountControlRegistry.java")
scheduler = source("src/main/java/ee/lastpagans/recruitsrtscamera/runtime/RecruitAIScheduler.java")
stats = source("src/main/java/ee/lastpagans/recruitsrtscamera/troops/TroopStatistic.java")
stat_registry = source("src/main/java/ee/lastpagans/recruitsrtscamera/troops/TroopStatRegistry.java")
stat_service = source("src/main/java/ee/lastpagans/recruitsrtscamera/troops/TroopStatService.java")
equipment_compat = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/TroopEquipmentCompat.java")
battle = source("src/main/java/ee/lastpagans/recruitsrtscamera/battle/BattleMechanicsManager.java")
routing = source("src/main/java/ee/lastpagans/recruitsrtscamera/battle/RoutedGroupManager.java")
world_renderer = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/TacticalWorldRenderer.java")
render_scene = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/TacticalRenderScene.java")
runtime_registry = source("src/main/java/ee/lastpagans/recruitsrtscamera/runtime/RecruitRuntimeRegistry.java")
spatial_index = source("src/main/java/ee/lastpagans/recruitsrtscamera/runtime/RecruitSpatialIndex.java")
doctrine = source("src/main/java/ee/lastpagans/recruitsrtscamera/formation/FormationDoctrineManager.java")
terrain_cache = source("src/main/java/ee/lastpagans/recruitsrtscamera/navigation/TacticalTerrainCache.java")
language = source("src/main/resources/assets/recruits_rts_camera/lang/en_us.json")

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
require(cavalry, "CommanderAnimationHints.updateMovement(s.rider, true, galloping)",
        "only charge and pass phases should publish the cavalry running animation")
require(cavalry, "case TURN -> 0.78D", "cycling cavalry must turn at an ordinary controlled pace")
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
require(maneuver, "TURN_RADIUS = 3.0D", "cavalry attack loops must use the quarter-radius hairpin")
require(maneuver, "TURN_STEPS = 12", "cavalry hairpins need small stable steering changes")
require(maneuver, "PASS_DISTANCE = 5.5D", "cavalry attack loops must use the quarter-length pass")
require(maneuver, "return turnWaypoint(turnStep);", "pass-to-turn handoff must not insert a stopped tick")
require(maneuver, "turnPoint.add(direction.scale(-1.5D))", "short hairpin exit must carry motion into replanning")
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
require(formation, "cohesionTarget = Math.max(0.68D",
        "the virtual centre must slow while physical members assemble")
require(formation, "cohesionTarget - state.cohesionScale, -0.04D, 0.04D",
        "cohesion pace changes must be eased instead of pulsing")
require(formation, "mover.setDeltaMovement(momentum)",
        "manual-control handoffs must preserve momentum for continuous animation")
require(formation, "ordinarySupportedRise = fallenBehind && direct",
        "verified one-block rises must stay under the continuous slot controller")
require(formation, "desired.y - mover.getY() <= 1.25D",
        "continuous rise handling must remain bounded to an ordinary supported step")
require(pathfinder, "FORMATION_SLOT_MAX_DROP = 1.25D",
        "final formation slots must accept an ordinary supported one-block descent")
require(pathfinder, "allowOrdinaryDescent || destination.y < start.y - 0.60D",
        "direct descent must require a lower destination or an explicitly verified continuation")
require(formation, "TacticalPathfinder.standableAt(level, mover, mover.position())",
        "an unsafe outer slot must hold its current supported column instead of targeting air")
require(formation, "travelling && state.route.complete()",
        "route synchronization must not append an unverified tail to an incomplete route")
require(formation, "state.routeGeneration != routeGeneration",
        "obsolete queued formation routes must not overwrite a replacement order")
require(formation, "agent.pathGeneration != pathGeneration",
        "obsolete queued member paths must not restore a discarded steering goal")
require(formation, "desiredTravelHeading = movementHeading(state)",
        "formation travel yaw must come from the shared route tangent")
require(formation, "angleDifference(state.travelHeading, desiredTravelHeading)",
        "shared travel yaw needs a deadband that rejects tiny route noise")
require(formation, "approachHeading(state.travelHeading, desiredTravelHeading, 4.5F)",
        "the group-owned travel yaw must turn smoothly through angled orders")
forbid(formation, "mountedTravelHeading(",
       "per-member mounted yaw smoothing must not split rider/mount pairs across the formation")
require(formation, "controlledHeading = state.travelHeading;",
        "moving riders and mounts must receive the exact shared formation yaw")
require(formation, "float previousYaw = mover.getYRot();",
        "rider and mount interpolation must start from the same previous yaw")
require(mounted_facing_client, "recruit.getShouldHoldPos()",
        "client render alignment must be limited to formation-held recruits")
require(mounted_facing_client, "float yaw = mount.getYRot();",
        "client render alignment must use the mount as the shared yaw source")
require(mounted_facing_client, "recruit.yBodyRotO = previousYaw;",
        "rider render interpolation must exactly match its mount")
require(formation, "RecruitAIScheduler.submitIncremental(",
        "formation and member routes must continue through bounded scheduler slices")
require(formation, "state.routeGoal = requestedDestination",
        "partial safe routes must retain their continuation goal")
require(formation, "MAX_PENDING_ROUTE_CONTINUITY = 6.0D",
        "running formations need enough verified continuity to span a multi-slice route handoff")
require(formation, "travelling && state.routePending && state.pendingRouteContinuity",
        "a pending replacement route must not zero the shared formation velocity")
require(formation, "TacticalPathfinder.standableAt(level, representative",
        "pending-route continuity must prove collision-backed terrain on every step")
require(formation, "elevation > 1.25D || elevation < -0.60D",
        "pending-route continuity must reject unsafe rises and drops")
require(formation, "state.routeIndex = routeResumeIndex(state.route.points(), state.center)",
        "a replacement route must resume ahead of a formation that kept moving")

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
require(terrain_cache, "shape.isEmpty() ? List.of()", "unsupported terrain rejection must remain")
require(pathfinder, "new SegmentProbe(", "safe open routes must be straightened incrementally")
require(pathfinder, "requiredAnchors.contains", "route smoothing must preserve constrained terrain anchors")
require(pathfinder, "SEARCH_TIME_BUDGET_NANOS = 20_000_000L",
        "each tactical search must yield a safe partial route before a multi-second tick spike")
require(pathfinder, "RouteBuildSession", "route reconstruction and smoothing must also be resumable")
require(pathfinder, "neighborBuffer", "A-star must reuse its expansion buffer")
require(scheduler, "TASK_SLICE_NANOS = 1_500_000L", "individual AI jobs need a hard scheduler slice")
require(scheduler, "SERVER_TICK_WORK_BUDGET_NANOS = 8_000_000L", "queued AI needs a global tick budget")
require(terrain_cache, "cache.surfaces", "terrain collision shapes must be shared within a server tick")
require(terrain_cache, "cache.fluids", "fluid safety samples must be shared within a server tick")
require(pathfinder, "cancelForTerrainChange", "resumed searches must reject terrain edited between slices")
require(controller, "ClientBattlefieldSnapshot", "hover, vision and card systems need one client snapshot")
require(mounted_facing_client, "commanderLoadedLivingEntities()",
        "mounted render alignment must reuse the client battlefield snapshot")
require(controller, "retainedTacticalScene", "tactical scene data must be retained between render frames")
require(world_renderer, "retainedRoutes", "route geometry must be retained until scene data changes")

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
require(controller, "scene.arrow(armyCenter.add(delta)",
        "Alt+Ctrl must render the world-space facing arrow")
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
require(overlay, "renderHoveredUnitStats(graphics, groups)", "hovered units need the ten-stat inspector")
require(overlay, "for (TroopStatistic statistic : TroopStatistic.values())",
        "hover inspector must render every troop-wide statistic")
require(overlay, "CommanderUiRegistry.record(statistic.id(), groupId",
        "hover stat bars must expose their semantic stat IDs")
require(overlay, "RTSCameraController.commanderMostRecentSelectedGroup()",
        "the stat inspector must remain on the most recently selected group after hover ends")
require(controller, "repairMostRecentSelection(groupId)",
        "selection paths must remember the most recently selected group")
require(facade, "commanderPreferSelectedGroup(clicked)",
        "Shift range selection must give the clicked group inspector precedence")
require(ui_ids, 'HOVER_STATS_PANEL = "hover.stats_panel"', "hover inspector needs a stable panel ID")
require(runtime_registry, "record LoadedSnapshot", "runtime managers need a shared per-tick recruit snapshot")
require(runtime_registry, "cached.gameTime == gameTime && cached.rosterRevision == rosterRevision",
        "same-tick recruit scans must reuse their immutable snapshot")
require(formation, "final Vec3RingBuffer trail", "formation trails need constant-time eviction")
require(formation, "for (UUID id : state.orderedMemberIds)", "formation member order must be cached")
resolve_members = formation[formation.index("private static List<AbstractRecruitEntity> resolveMembers"):
                            formation.index("private static AbstractRecruitEntity resolve(")]
forbid(resolve_members, ".sort(", "formation members must not be sorted every tick")
require(battle, "Math.abs(existing.getAmount() - amount) <= 1.0E-6D",
        "fatigue modifiers must remain untouched when their effective value is unchanged")
require(doctrine, "existing.getOperation() == operation",
        "doctrine modifiers must remain untouched when their effective value is unchanged")
require(battle, "LAST_POSITIONS.clear();", "battle runtime positions must clear at server stop")
require(rumble, "LAST_MOUNT_SAMPLE.keySet().removeIf", "stale mount samples must be pruned while running")
require(selection_groups, "key == GLFW.GLFW_KEY_BACKSPACE", "Backspace must unbind a direct group hotkey")
require(selection_groups, "BUNDLES.containsKey(bundleSlot)",
        "number hotkeys must only be blocked by an occupied bundle slot")
require(selection_groups, "!DIRECT_HOTKEYS.containsKey(keyForBundleSlot(candidate))",
        "bundle creation must skip numbers occupied by direct hotkeys")
require(face_mixin, "FormationService.rememberFacing", "native Face must persist its facing lock")
require(face_mixin, "@Shadow(remap = false) private UUID group;",
        "native Face packet fields must not use Minecraft remapping")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/NativeOrderFormationReleaseMixin.java"),
        "@Shadow(remap = false) private UUID group;",
        "native upkeep packet fields must not use Minecraft remapping")
require(formation, "CENTER_SPEED = 0.21D", "foot formation travel must be 1.5x faster")
require(formation, "FOOT_NAV_SPEED = 1.17D", "foot slot movement and rotation must be 1.5x faster")
require(movement, "REGULAR_INFANTRY_NAV_SPEED = 1.17D", "regular foot navigation must be 1.5x faster")
require(movement, "HASTY_INFANTRY_SPEED = 1.62D", "running foot navigation must be 1.5x faster")
require(movement, "SLOW_MARCH_CAVALRY_NAV_SPEED = 0.68D", "cavalry slow pace must remain unchanged")
require(movement, "REGULAR_CAVALRY_MOUNT_MULTIPLIER = 0.25D",
        "ordinary formation cavalry must cap its temporary attribute boost at 1.25x")
require(movement, "MAX_CAVALRY_MOUNT_ATTRIBUTE_BONUS = 0.25D",
        "run and charge composition must not turn a recruited horse into a permanent super-horse")
require(movement, "living.getFirstPassenger() == recruit",
        "formation cavalry speed modifiers must require the recruit to be the active mounted rider")
require(movement, "GROUP_ACCELERATION_PER_TICK = 0.015D",
        "formation pace changes need a bounded shared acceleration")
require(movement, "replacement.currentStep = previous.currentStep",
        "Walk-to-Run must carry the current anchor speed across mode replacement")
require(movement, "TacticalCombatManager.chargeAcceleration(recruit)",
        "mounted charges must progressively accelerate")
require(tactical, "return 1.10D + 0.30D * exponential;",
        "melee charges must snowball to a bounded exponential speed bonus")
require(tactical, "rearImpact(attacker, victim) ? 2.0D : 1.0D",
        "rear charge impacts must apply double morale shock")
require(cavalry_combat, "TacticalCombatManager.rearImpact(rider, victim) ? 2.0D : 1.0D",
        "mounted rear impacts must apply double morale shock")
require(attack, "FormationRuntime.retargetFormationImmediately",
        "attack approaches must replace an obsolete route while preserving formation slots")
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
require(controller, "current == FormationKind.CAVALRY_WEDGE ? FormationKind.CAVALRY_WEDGE : FormationKind.LINE",
        "frontage positioning must retain the wedge highlight after Commander menus close")
require(overlay, "RTSCommandFacade.commonSelectedFormationKind() == FormationKind.CAVALRY_WEDGE",
        "the wedge button must derive its highlight from persistent selected-formation state")
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
require(triumph, "OPPORTUNITY_CHANCE = 0.50F", "mounted triumph needs the further increased occasional rate")
require(triumph, "recruit.getMainHandItem().isEmpty()", "mounted triumph must preflight its hand item")
require(rumble, "sample.riders * 2 <= sample.totalMembers", "triumph must only sample cavalry formations")
require(rumble, "charging || running || wedge", "triumph eligibility must include charge, run and wedge")
require(steering, "private static boolean steerMounted(",
        "mounted open-ground movement needs one continuous velocity controller")
require(steering, "setWantedPosition(mover.getX(), mover.getY(), mover.getZ(), 0.0D)",
        "mounted steering must neutralize MoveControl's competing yaw target")
require(mounted_motion, "currentX * 0.30D + requestedX * 0.70D",
        "mounted velocity changes must be damped across ticks")
require(steering, "TacticalPathfinder.canSteerDirect(level, mover, probe, supportedDescent)",
        "direct mounted velocity must retain terrain and hole safety checks")
require(formation_packet, "packet.kind == FormationKind.CAVALRY_WEDGE || packet.kind == FormationKind.SQUARE",
        "wedge and its square toggle must use one native formation controller")
require(formation, "mover != recruit && (travelling || !arrived)",
        "mounted bodies must retain the route tangent through slow arrival cohesion")
require(formation, "? slotError > 4.0D * 4.0D ? 1.06D",
        "mounted laggards need bounded catch-up instead of teleport-like surges")
require(formation, "cohesionDistance = mountedMember ? 0.85D : 1.55D",
        "mounted cohesion must tighten before the virtual centre resumes full pace")
require(formation, "if (slotError > cohesionDistance * cohesionDistance) laggards++",
        "terrain elevation differences must not count as formation insubordination")
require(formation, "elevations.get((elevations.size() - 1) / 2)",
        "new routes must start on an occupied median elevation rather than averaged air")
require(formation, "!TacticalCombatManager.ownsTarget(recruit) && recruit.getTarget() != null",
        "formation members must not swing at autonomous targets from locked slots")
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
require(rumble_client, "CLOSE_GAIN = 1.50F", "close cavalry rumble needs the requested gain boost")
require(rumble_client, "gain * CLOSE_GAIN * (1.0F - filterMix)",
        "the gain boost must apply only to the close clear layer")
require(rumble_client, "distant.update(targetPosition, gain * filterMix, targetPitch)",
        "the distant low-pass layer must retain its existing gain curve")
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
require(scheduler, "SERVER_TICK_WORK_BUDGET_NANOS = 8_000_000L",
        "recovery paths need an aggregate tick-time guard during initial cohesion")
require(overlay, "COMMAND_CAVALRY_WEDGE", "cavalry wedge needs a stable command-bar element")
require(overlay, "bodyguardFollow, cavalryWedge", "cavalry wedge must be the right-most command")
for stat_id in ("stat.damage", "stat.melee_skill", "stat.melee_defence", "stat.charge_bonus", "stat.speed",
                "stat.morale", "stat.fatigue", "stat.accuracy", "stat.ammunition", "stat.rate_of_fire"):
    require(stats, '"' + stat_id + '"', "all ten troop-wide statistics must have stable IDs")
require(stats, "public String description()", "troop statistics must expose their documented meaning")
require(stats, "public static TroopStatistic fromId", "future editors need stable statistic lookup by semantic ID")
require(stats, '50.0D, 110.0D, true', "morale metadata must expose its exceptional-leadership ceiling")
require(overlay, "renderTroopStatisticTooltip", "the unit inspector must expose stat descriptions")
require(overlay, "statistic.maximum()", "stat progress bars must respect per-stat display ranges")
for stat_id in ("damage", "melee_skill", "melee_defence", "charge_bonus", "speed", "morale",
                "fatigue", "accuracy", "ammunition", "rate_of_fire"):
    require(language, '"recruits_rts_camera.stat.' + stat_id + '.description"',
            "each troop statistic needs a localizable description")
require(stat_registry, "Map<UUID, TroopStatProfile>", "statistics must be stored per troop group")
require(stat_registry, "replace(Map<UUID, TroopStatProfile>",
        "the registry must expose the authoritative live group-stat snapshot")
require(stat_service, "for (int slot = 0; slot < inventory.getContainerSize(); slot++)",
        "encumbrance must include equipped items and every recruit cargo slot")
require(stat_service, "count * 0.04D", "ordinary ammunition must remain light per projectile")
require(stat_service, "item.getFoodProperties().getNutrition() * 0.06D",
        "large food stores must contribute nutrition-scaled weight")
require(stat_service, "ENCUMBRANCE_RANGED_CAPACITY", "ranged troops need their lower carrying capacity")
require(stat_service, '"RTS inventory encumbrance"', "overload must apply a dedicated movement modifier")
require(stat_service, "fatigueGainMultiplier", "carried load must increase fatigue growth")
require(stat_service, "fatigueRecoveryMultiplier", "carried load must slow fatigue recovery")
require(stat_service, "LivingAttackEvent", "melee skill and defence must resolve real melee hit chances")
require(stat_service, "projectile.setDeltaMovement", "accuracy must affect real recruit projectiles")
require(stat_service, "double base = 110.0D", "unarmored foot formations must have 110 speed")
require(stat_service, "double armorPenalty = armor * 2.5D",
        "four armor points must reduce formation speed from 110 to 100")
forbid(stat_service, "movement.getValue() * 200.0D",
       "walking and running modifiers must never change maximum formation speed")
require(stat_service, "TroopEquipmentCompat.equippedCombatWeapon(recruit)",
        "live stats must follow weapons parked in a recruit's offhand")
require(equipment_compat, '"musketmod"', "Musket Mod must have direct optional integration")
require(equipment_compat, '"magistuarmory", "slavicarmory", "darkagesarmory", "magistuarmoryaddon", "antiquelegacy"',
        "all supplied Epic Knights modules must share direct equipment integration")
for method in ("bayonetDamage", "bulletStdDev", "getLoadingDuration", "getWeight"):
    require(equipment_compat, '"' + method + '"', "equipment integration must read live " + method)
require(equipment_compat, "MethodHandles.privateLookupIn(type, MethodHandles.lookup())",
        "equipment weight discovery must avoid reflection that resolves client-only renderer signatures")
require(equipment_compat, 'findVirtual(type, "getWeight", MethodType.methodType(float.class))',
        "Epic Knights weight integration must link only its exact server-safe primitive signature")
require(equipment_compat, "catch (Throwable ignored)",
        "optional equipment invocation failures must fall back instead of crashing the server")
require(equipment_compat, "RuntimeException | LinkageError ignored",
        "foreign reflection and linkage failures must be contained during discovery")
require(stat_service, "if (Double.isFinite(integratedWeight)) return count * integratedWeight;",
        "failed optional weight lookup must retain the normal equipment-weight fallback")
require(stat_service, "TroopEquipmentCompat.meleeDamage(weapon, recruit.getAttackDamage())",
        "Damage must report melee damage rather than musket projectile damage")
require(equipment_compat, "isMusketGun(off)", "Musket Rest offhand parking must remain visible to stats")
for mod_id in ("musketmod", "magistuarmory", "slavicarmory", "darkagesarmory",
               "magistuarmoryaddon", "antiquelegacy"):
    require(source("src/main/resources/META-INF/mods.toml"), 'modId="' + mod_id + '"',
            "supplied equipment integration needs optional load ordering for " + mod_id)
require(cavalry_combat, "TroopStatService.chargeMultiplier(rider)",
        "charge bonus must affect cavalry impact damage and morale shock")
require(tactical, "TroopStatService.chargeMultiplier(attacker)",
        "charge bonus must affect foot charge damage and morale shock")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/network/BattleStateSyncPacket.java"),
        "for (TroopStatistic statistic : TroopStatistic.values())",
        "all live troop statistics must synchronize to Commander")
require(overlay, "battle.statistics().value(statistic)", "the inspector must use synchronized real statistics")
require(overlay, "battle.carriedWeight()", "the inspector must expose carried weight and capacity")
require(overlay, "battle.foodSupply()", "the inspector must expose remaining food supply")
require(spatial_index, "RecruitRuntimeRegistry.loaded(level)",
        "stage four needs one shared position-correct spatial snapshot per level tick")
require(spatial_index, "public static List<AbstractRecruitEntity> within",
        "stage four needs bounded recruit queries for impact and projectile corridors")
require(cavalry_combat, "RecruitSpatialIndex.within", "cavalry impact scans must use the shared grid")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/ProjectileAwarenessManager.java"),
        "RecruitSpatialIndex.within", "projectile corridors must use the shared grid")
require(battle, "FATIGUE_GAIN_SCALE = 1.0D / 2.3D", "battle fatigue growth must be 2.3x slower")
require(battle, "ROUTING_MORALE = 4.0F", "ordinary rout susceptibility must be halved")
require(battle, "ARCHER_MELEE_ROUTING_MORALE = 8.0F",
        "archer-heavy groups caught in melee must retain their stronger rout vulnerability")
require(battle, "TacticalCombatManager.ranged(recruit)",
        "archer melee vulnerability must use the actual tactical ranged classification")
require(battle, "import ee.lastpagans.recruitsrtscamera.combat.TacticalCombatManager;",
        "battle mechanics must import the tactical ranged classifier")
require(world_renderer, "RenderLevelStageEvent.Stage.AFTER_PARTICLES",
        "battlefield previews must render in world space")
require(world_renderer, "1.0D - Math.exp(-seconds * 7.5D)",
        "group marker anchors need frame-rate-independent smoothing")
require(world_renderer, "Math.sin(time + u0 * Math.PI * 2.2D)",
        "temporary cloth markers need smooth sine-wave animation")
require(world_renderer, '"textures/misc/betsy_ross_group_marker.png"',
        "the temporary 3D group marker must use the Betsy Ross texture")
require(render_scene, "List<RouteRibbon> routes", "the unified scene must own route ribbons")
forbid(render_scene, "FormationFootprint", "transparent formation polygon estimation must remain removed")
forbid(controller, "scene.footprint", "formation previews must render slots without a transparent polygon")
forbid(controller, "scene.route(List.of(start, end)", "frontage planning must not render the long centre bar")
require(controller, "TACTICAL_ROUTE_COLOR = 0xD8FFD36A", "tactical previews must use original yellow")
require(world_renderer, "view.anchorY() + 6.4D", "the cloth flag must be raised four blocks")
require(overlay, "worldY + 6.4D", "raised flag selection must follow the rendered cloth")
forbid(world_renderer, "drawFlagPole", "temporary Betsy Ross markers must render as cloth only")
forbid(world_renderer, "verticalBox", "temporary markers must not synthesize a pole model")
require(render_scene, "float flowSpeed", "animated route ribbons must carry movement speed")
require(world_renderer, "0.075D * route.flowSpeed()",
        "route chevron animation must scale with formation movement pace")
require(controller, "previewFlowSpeed(groupId)", "planned routes must use their group's walk/run state")
require(mounted_motion, "currentSpeed > retainedLimit",
        "mounted damping must reject exceptional incoming fling impulses")
require(mounted_motion, "Math.max(1.25D, formationSpeed + 0.75D)",
        "impulse protection must not clamp legitimate gallop momentum")
require(steering, "Math.min(distanceToSlot, Math.max(0.35D, requested.length() * 5.0D))",
        "mounted ledge probes must never overshoot the final slot")
require(steering, "position.y + (desired.y - position.y) * slotFraction",
        "mounted ledge probes must retain the grounded slot elevation")
require(steering, "canSteerDirect(level, mover, probe, supportedDescent)",
        "a verified one-block descent must survive the mounted inner safety probe")
require(pathfinder, "boolean allowOrdinaryDescent",
        "direct terrain checks need an explicit supported-descent continuation")
require(formation, "supportedRouteSeed(sender.serverLevel(), units, old.center)",
        "replacement orders must ground the live anchor instead of combining unrelated XYZ averages")
require(formation, "TacticalPathfinder.nearestFormationStandable(level, representative, candidate)",
        "replacement route seeds must not fall through an averaged centre into a cave")
require(formation, "state.cohesionScale = old.cohesionScale",
        "replacement orders must preserve their current eased formation pace")
require(formation, "faceNearbyTarget = !travelling && TacticalCombatManager.ownsTarget(recruit)",
        "stationary formation melee must turn into its explicit nearby target")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/network/TranslateGroupsPacket.java"),
        "RTSMovementBoostManager.registerHastyMove", "Alt-drag translation must default to run")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/network/FrontageGroupsPacket.java"),
        "RTSMovementBoostManager.registerHastyMove", "frontage movement must default to run")
forbid(controller, "drawWorldPathSegment(", "battlefield routes must not fall back to pixel-stamped HUD lines")
forbid(controller, "drawYellowWorldPoint(", "formation slots must not fall back to fixed HUD squares")
require(controller, "ORDER_RADIUS = 288.0D", "temporary client order reach must be tripled")
require(source("src/main/java/ee/lastpagans/recruitsrtscamera/network/MoveGroupsPacket.java"),
        "MAX_TARGET_DISTANCE = 336.0D", "server move-order reach must be tripled")

forbid(client_battle, "commanderDropRoutedGroups",
        "routing must not collapse Commander selection or close its command UI")
forbid(facade, "battle.moraleState() >= 2",
        "routed formations must remain visible as status-bearing Commander cards")
forbid(battle, "REAR_SHOCK_COOLDOWN",
        "ordinary rear contact must not repeatedly drain the entire group's morale")
forbid(battle, "ARCHER_MELEE_MORALE_LOSS_PER_SAMPLE",
        "merely having a nearby melee target must not passively drain archer morale")
require(routing, "removeNativeGroupIfRoutFinished",
        "native group deletion must wait until the visible rout has actually finished")
require(client_battle, "routed.forEach(GROUPS::putIfAbsent)",
        "the ROUTING card badge must survive omission from ordinary runtime samples")

require(group_flag_command, 'Commands.literal("setflag")', "group flags need the requested setflag command")
require(group_flag_command, "StringArgumentType.greedyString()",
        "setflag must accept readable group names, including names containing spaces")
require(group_flag_command, "map(RecruitsGroup::getName)",
        "setflag completion must suggest visible group names instead of UUIDs")
require(group_flag_command, "player.getUUID().equals(group.getPlayerUUID())",
        "setflag must only open for a group owned by the command sender")
require(group_flag_screen, "ClientPaintingManager.getPaintings()",
        "the group flag browser must use Immersive Paintings' synchronized library")
require(group_flag_screen, "new SetGroupFlagPacket(",
        "choosing a painting must send the group selection to server authority")
require(group_flag_screen, "extends ImmersivePaintingScreen",
        "group flags must use Immersive Paintings' real editor rather than a substitute grid")
require(group_flag_screen, 'getDeclaredField("entity")',
        "the real editor proxy must use the verified 0.6.13 entity field")
require(group_flag_screen, "ENTITY_FIELD.set(this, proxy)",
        "the private proxy must be installed without transforming Immersive Paintings")
forbid(source("src/main/resources/recruits_rts_camera.mixins.json"), "ImmersivePaintingScreenMixin",
       "optional Immersive Paintings must never be transformed during client startup")
forbid(group_flag_screen, "COLUMNS", "the removed homemade painting grid must not return")
require(group_flag_data, 'DATA_NAME = "recruits_rts_camera_group_flags"',
        "group painting selections must persist across world reloads")
require(group_flag_sync, "RTSGroupFlagSavedData.get(player.serverLevel()).snapshot()",
        "clients must receive persisted group flags when RTS mode starts")
require(group_flag_set, "sender.getUUID().equals(group.getPlayerUUID())",
        "the server must reject painting changes for foreign groups")
require(world_renderer, "GroupFlagClientState.texture(view.groupId())",
        "the waving group flag must resolve its selected painting texture")
require(group_flag_client, '!ModList.get().isLoaded("immersive_paintings")',
        "Immersive Paintings must remain an optional integration")
require(cavalry_combat, "MAX_MOUNTED_SPEED_MULTIPLIER = 1.25D",
        "native cavalry combat must cap the horse speed modifier at 1.25x")
require(cavalry_combat, "mount.getFirstPassenger() == recruit",
        "mount bonuses must only exist while the recruit actually occupies the horse")
visibility = source("src/main/java/ee/lastpagans/recruitsrtscamera/server/RTSVision.java")
visibility_sync = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/EnemyVisibilitySyncPacket.java")
client_visibility = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/ClientEnemyVisibilityState.java")
require(visibility, "ClipContext.Block.COLLIDER", "terrain must occlude hostile formation sight")
require(visibility, "BlockTags.LEAVES", "dense brush must conceal hostile formations")
require(visibility, "BRUSH_REVEAL_RADIUS", "brush concealment must break at close identification range")
require(visibility_sync, "Map<UUID, Boolean>", "the server must synchronize per-formation visibility")
require(client_visibility, "FADE_OUT_PER_TICK", "lost contacts must fade instead of popping out")
require(overlay, "ClientEnemyVisibilityState.targetable", "hidden enemy banners must not remain targetable")
require(cavalry_combat, "equippedMeleeDamage", "cavalry impact must include its equipped weapon")
require(cavalry_combat, "state.lastSpeed", "cavalry impact must scale from measured travel speed")
require(tactical, "Math.expm1", "charge acceleration must grow exponentially for the shared infantry/cavalry system")
require(stat_service, "movementPenalty /= 1.75D", "mounted carried weight impact must be 1.75x lower")
require(network, 'PROTOCOL = "rts-camera-162"', "lance-pose synchronization requires protocol 162")
require(pathfinder, "supportedRiseAhead", "mounted movement needs an early collision-backed rise probe")
require(steering, "Math.max(current.y, 0.52D)",
        "local mounted steering must actively lift over a verified one-block rise")
require(formation, "Math.max(motion.y, 0.52D)",
        "obstacle-aware member routes must actively lift mounts over verified rises")
require(lance_compat, 'getMethod("setRaised", LivingEntity.class, boolean.class)',
        "Epic Knights lance integration must use the real public pose API")
require(cavalry, "distanceSq <= 6.0D * 6.0D",
        "lances must lower only on the final charge approach")
require(lance_sync, "EpicKnightsLanceCompat.setRaised", "lance poses must synchronize to clients")
smoke = source("src/main/java/ee/lastpagans/recruitsrtscamera/effects/BlackPowderSmokeManager.java")
musket_rest = source("src/main/java/ee/lastpagans/recruitsrtscamera/compat/MusketRestManager.java")
client_events = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/RTSCameraClientEvents.java")
require(pathfinder, "canyonDepth * canyonDepth * 12.0D",
        "level-endpoint routes must strongly prefer a safe rim detour over a canyon descent")
require(formation, "Math.max(0.68D, 1.0D - laggardShare * 0.55D)",
        "laggards must not reduce the main formation to a crawl")
require(formation, "slotError > 4.0D * 4.0D ? 1.12D",
        "foot laggards need a bounded human catch-up pace")
require(formation, "agent.steeringTarget", "laggards must face their personal route tangent")
require(formation, "Vec3 pathGoal = desired;",
        "distant members must route to their actual grounded slots instead of the formation centre")
forbid(formation, "Vec3 recoveryGoal",
       "a persistent centre recovery target must not collapse outer ranks into a moshpit")
require(steering, "error.x * 0.22D", "final slot correction must be damped instead of magnetic")
require(stat_service, "0.40D + item.getFoodProperties().getNutrition() * 0.06D",
        "a full food stack must strongly encumber its carrier")
forbid(client_events, "FogOfWarWorldRenderer.render(event)",
       "the visual fog bank must be removed without disabling server visibility")
require(smoke, "SCALE = 0.85D", "absorbed black-powder smoke must use the requested smaller spread")
forbid(smoke, "BurntModParticleTypes", "removed third-party particle integration must not return")
require(smoke, "com.camdenscottc.colonialcannons.projectile.CannonballEntity",
        "absorbed smoke must retain Colonial Cannons projectile compatibility")
require(smoke, 'invokeBoolean(projectile, "isExplosive")',
        "Colonial Cannons explosive and solid impacts must remain distinct")
require(smoke, "MAX_CLOUDS = 512", "absorbed smoke must bound persistent cloud cost")
require(smoke, "closest.density = Math.min(32, closest.density + 1)",
        "repeated musketry must accumulate battlefield-obscuring smoke density")
require(smoke, "cloud.particles + cloud.density / 2",
        "dense smoke must emit more fog than isolated shots")
require(smoke, "RTSParticles.BLACK_POWDER_FOG.get()",
        "absorbed firearm effects must use RTS-owned translucent fog")
forbid(smoke, "CAMPFIRE_COSY_SMOKE",
       "black-powder fog must not regress to vanilla campfire puffs")
require(source("src/main/resources/META-INF/mods.toml"), 'modId="colonialcannons"',
        "Colonial Cannons must retain optional load ordering")
forbid(source("src/main/resources/META-INF/mods.toml"), 'modId="burnt"',
       "removed third-party particles must not retain load-order metadata")
require(musket_rest, "IDLE_TICKS = 20 * 5", "absorbed musket rest must park long guns after five quiet seconds")
require(musket_rest, "recruit.getShouldStrategicFire()", "strategic fire must restore the long gun to main hand")
forbid(source("src/main/resources/META-INF/mods.toml"), 'modId="musketrestposition"',
       "RTS must no longer depend on the absorbed musket-rest addon")
camera_shake = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/CavalryCameraShakeClient.java")
morale_command = source("src/main/java/ee/lastpagans/recruitsrtscamera/server/MoraleItemCommand.java")
morale_data = source("src/main/java/ee/lastpagans/recruitsrtscamera/persistence/RTSMoraleItemSavedData.java")
morale_registry = source("src/main/java/ee/lastpagans/recruitsrtscamera/server/MoraleItemRegistry.java")
aura_renderer = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/AuraRadiusRenderer.java")
morale_sync = source("src/main/java/ee/lastpagans/recruitsrtscamera/network/MoraleItemsSyncPacket.java")
require(camera_shake, "Math.expm1(sample.riders / 17.0D)",
        "cavalry camera shake must scale exponentially from a visible seventeen-rider baseline")
require(camera_shake, "MAX_DISTANCE = 96.0D", "cavalry shake must have bounded distance falloff")
require(camera_shake, "0.025D, 1.20D", "walking cavalry shake must remain barely perceptible")
require(formation, "horizontalSpeed * 0.992D",
        "members must coast instead of braking to a stop during asynchronous path planning")
require(formation, "slotVelocity.z * slotVelocity.z > 1.0E-6D",
        "slow walking slots must never be misclassified as stationary")
require(formation, "routeResumeIndex(agent.path.points(), mover.position())",
        "coasting members must resume ahead on a completed route instead of backtracking")
require(formation, "horizontalDistanceSq(lastProgressPosition, position) > 0.03D * 0.03D",
        "slow cavalry progress must not trigger periodic path-controller resets")
require(formation, "now - lastProgressAt >= 30L",
        "genuine stuck recovery needs a stable observation window")
require(movement, "horizontalDistance(slot, state.lastRecoverySlot) > 0.005D",
        "the recovery watchdog must recognize a slowly moving formation slot")
require(movement, "state.lastSlotDistance - distance > 0.01D",
        "the recovery watchdog must recognize ordinary walking progress")
forbid(movement,
       "NativeFormationController.requestRecovery(state.groupId, recruit.getUUID());\n        if (controlled instanceof Mob mob) mob.getNavigation().stop();",
       "route recovery must never force a full cavalry navigation stop")
require(client_events, "CavalryCameraShakeClient.apply(event)", "camera angle events must apply hoofbeat shake")
require(overlay, "drawTooltipText", "Commander tooltips need colored key-segment rendering")
require(overlay, "graphics.drawString(MC.font, key, drawX, y, GOLD",
        "every angle-bracketed key segment must render yellow")
require(morale_command, 'Commands.literal("recruitsmoraleitem")', "operators need live morale-item commands")
require(morale_command, "source.hasPermission(2)", "live morale-item changes must be operator-only")
require(morale_command, "StringArgumentType.string()",
        "morale-item commands must accept namespace:item resource locations")
require(morale_data, 'DATA_NAME = "recruits_rts_camera_morale_items"',
        "runtime morale items must persist with the world")
require(morale_registry, "stack.is(RTSTags.MORALE_ITEMS)",
        "runtime morale items must extend rather than replace datapack standards")
require(aura_renderer, "TRIANGLE_FAN", "hovered morale auras need a translucent filled blue circle")
require(aura_renderer, "MOTION_BLOCKING_NO_LEAVES", "morale auras must follow terrain instead of being buried")
require(controller,
        "capturedShowOrdersDown || RTSCameraKeyMappings.SHOW_PLANNED_ORDERS.isDown()",
        "aura radii must only appear while Space is held")
require(morale_sync, "RTSMoraleItemSavedData", "runtime standards must synchronize for client aura detection")
require(client_events, "ClientMoraleItemState.clear()", "runtime morale-item client state must clear on logout")
require(tactical, "Math.expm1(3.0D * progress)",
        "infantry and cavalry charge speed must retain exponential growth")
road_erosion = source("src/main/java/ee/lastpagans/recruitsrtscamera/movement/TroopRoadErosionManager.java")
volley = source("src/main/java/ee/lastpagans/recruitsrtscamera/combat/FormationVolleyManager.java")
bow_volley = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/BowVolleyMixin.java")
crossbow_volley = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/CrossbowVolleyMixin.java")
nomad_volley = source("src/main/java/ee/lastpagans/recruitsrtscamera/mixin/NomadVolleyMixin.java")
card_order = source("src/main/java/ee/lastpagans/recruitsrtscamera/client/UnitCardOrder.java")
require(road_erosion, "Blocks.DIRT_PATH.defaultBlockState()", "troop traffic must erode natural ground into paths")
require(road_erosion, "GameRules.RULE_MOBGRIEFING", "troop road erosion must respect mob griefing policy")
require(road_erosion, "mover == recruit ? 1 : 3", "mounted traffic must erode roads faster than foot traffic")
require(volley, "nextBowDraw", "bow formations need one shared draw and reload clock")
require(volley, "crossbowReady.size() >= expected", "loaded crossbows must wait for formation readiness")
require(volley, "javelinRelease", "javelin formations need one shared release tick")
require(bow_volley, "attackTime = synchronizedTime", "native Bowman timers must consume the formation volley clock")
require(crossbow_volley, "CrossbowItem.isCharged", "loaded Crossbowmen must be held for their volley")
require(nomad_volley, "FormationVolleyManager.bowAttackTime(nomad)",
        "mounted missile units must share their formation volley clock")
require(stat_service, "recruit instanceof BowmanEntity ? 0.22D : 2.75D",
        "Bowmen must carry many javelins while ordinary recruits overload after a few")
require(source("src/main/resources/data/recruits_rts_camera/tags/items/throwable_weapons.json"),
        '"darkagesarmory:steel_javelin"', "Epic Knights Dark Ages javelins must enter RTS throwing logic")
require(overlay, "view.moraleState() >= 2", "routing cards must reject interaction and retain a white overlay")
require(overlay, "Math.sin(phase)", "wavering cards need a flashing translucent overlay")
require(overlay, "graphics.pose().scale(1.04F", "dragged cards must lift with smooth visual parallax")
require(card_order, "RTSClientConfig.UNIT_CARD_ORDER.set", "rearranged card order must persist")
for mapping in ("TOGGLE", "CLEAR", "MOVEMENT_PACE", "SELECTION_GROUP", "ROTATE", "ARMY_PRESET",
                "STRATEGIC_FIRE", "HOLD_FIRE", "FIRE_AT_WILL", "ROUTE_MODE", "COMMIT_ROUTE",
                "RESET_ROUTE", "HALT", "CAMERA_UP", "CAMERA_DOWN", "SHOW_PLANNED_ORDERS"):
    require(keys, "event.register(" + mapping + ")", "every RTS hotkey must remain configurable: " + mapping)
require(source("gradle.properties"), "mod_version=0.22.6-server.1", "semantic version must be updated")
require(controller, "0.22.6-server.1 · DEBUG COMMAND MENU", "debug UI version must be updated")
require(source("BUILD.bat"), "recruits-rts-camera-0.22.6-server.1.jar", "build helper version must be updated")

print("0.22.6-server.1 source contracts passed")
