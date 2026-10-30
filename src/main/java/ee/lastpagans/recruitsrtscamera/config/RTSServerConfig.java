package ee.lastpagans.recruitsrtscamera.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Server-side policy and hardening settings for RTS Camera. */
public final class RTSServerConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue WHITELIST_MODE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WHITELIST;
    public static final ForgeConfigSpec.BooleanValue OPS_BYPASS_WHITELIST;
    public static final ForgeConfigSpec.IntValue MAX_ORDERS_PER_SECOND;
    public static final ForgeConfigSpec.IntValue MAX_CUSTOM_ORDERS_PER_SECOND;
    public static final ForgeConfigSpec.IntValue MAX_CUSTOM_STROKE_POINTS;
    public static final ForgeConfigSpec.IntValue MAX_ROUTE_WAYPOINTS;
    public static final ForgeConfigSpec.IntValue MAX_GROUPS_PER_ORDER;
    public static final ForgeConfigSpec.IntValue MAX_RECRUITS_PER_ORDER;
    public static final ForgeConfigSpec.BooleanValue FOG_OF_WAR;
    public static final ForgeConfigSpec.IntValue FOG_PLAYER_VISION_RADIUS;
    public static final ForgeConfigSpec.IntValue FOG_RECRUIT_VISION_RADIUS;
    public static final ForgeConfigSpec.IntValue FOG_CAVALRY_VISION_RADIUS;
    public static final ForgeConfigSpec.IntValue FOG_SHIP_VISION_RADIUS;
    public static final ForgeConfigSpec.BooleanValue FOG_BLOCK_HIDDEN_TARGETING;
    public static final ForgeConfigSpec.BooleanValue FORMATION_DOCTRINE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue NATIVE_FORMATION_RUNTIME;
    public static final ForgeConfigSpec.BooleanValue RUNTIME_BRAIN;
    public static final ForgeConfigSpec.BooleanValue GOAL_SUPPRESSION;
    public static final ForgeConfigSpec.BooleanValue AI_SCHEDULER;
    public static final ForgeConfigSpec.BooleanValue LOCAL_STEERING;
    public static final ForgeConfigSpec.BooleanValue TACTICAL_NAVIGATION;
    public static final ForgeConfigSpec.BooleanValue PROJECTILE_AWARENESS;
    public static final ForgeConfigSpec.IntValue FORMATION_PATHS_PER_TICK;
    public static final ForgeConfigSpec.IntValue AGENT_PATHS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue CAVALRY_STUCK_RECOVERY;
    public static final ForgeConfigSpec.IntValue CAVALRY_STUCK_RECOVERY_DELAY_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_STUCK_RECOVERY_MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_STUCK_RECOVERY_MAX_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue FORMATION_ORDER_WATCHDOG;
    public static final ForgeConfigSpec.IntValue FORMATION_STALL_RECOVERY_TICKS;
    public static final ForgeConfigSpec.DoubleValue FORMATION_STALL_NUDGE_STEP;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_FORMATION_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_HASTY_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_COMBAT_SPEED_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_CHARGE_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_CHARGE_RUNUP;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_FULL_CHARGE_SPEED;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_MELEE_SHOCK;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_RANGED_SHOCK;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_MELEE_PRESSURE;
    public static final ForgeConfigSpec.DoubleValue CAVALRY_RANGED_PRESSURE;
    public static final ForgeConfigSpec SERVER;
    public static final ForgeConfigSpec.DoubleValue ARROW_LIGHT_ARMOR_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue JAVELIN_LIGHT_ARMOR_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue ENCUMBRANCE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ENCUMBRANCE_FOOT_CAPACITY;
    public static final ForgeConfigSpec.DoubleValue ENCUMBRANCE_RANGED_CAPACITY;
    public static final ForgeConfigSpec.DoubleValue ENCUMBRANCE_MOUNTED_BONUS;
    public static final ForgeConfigSpec.DoubleValue ENCUMBRANCE_MAX_SPEED_PENALTY;
    public static final ForgeConfigSpec.DoubleValue ENCUMBRANCE_FATIGUE_SCALE;

    static {
        BUILDER.push("permissions");
        WHITELIST_MODE = BUILDER
                .comment("When true, only players listed in whitelist may open or use Recruits: RTS Camera.")
                .define("whitelistMode", false);
        WHITELIST = BUILDER
                .comment(
                        "Players allowed to use RTS Camera while whitelistMode=true.",
                        "Entries may be UUIDs or current player names. UUIDs are recommended for permanent server lists."
                )
                .defineList("whitelist", List.of(), value -> value instanceof String && !((String) value).isBlank());
        OPS_BYPASS_WHITELIST = BUILDER
                .comment("Allow permission-level 2+ operators even when they are not in the whitelist.")
                .define("opsBypassWhitelist", true);
        BUILDER.pop();

        BUILDER.push("networkSafety");
        MAX_ORDERS_PER_SECOND = BUILDER
                .comment("Maximum ordinary RTS command packets accepted from one player per second.")
                .defineInRange("maxOrdersPerSecond", 24, 4, 100);
        MAX_CUSTOM_ORDERS_PER_SECOND = BUILDER
                .comment("Maximum freehand/custom formation packets accepted from one player per second.")
                .defineInRange("maxCustomOrdersPerSecond", 4, 1, 20);
        MAX_CUSTOM_STROKE_POINTS = BUILDER
                .comment("Hard server-side cap on points in one custom formation stroke.")
                .defineInRange("maxCustomStrokePoints", 160, 16, 512);
        MAX_ROUTE_WAYPOINTS = BUILDER
                .comment("Maximum waypoints accepted in one committed multi-stage route.")
                .defineInRange("maxRouteWaypoints", 16, 2, 32);
        MAX_GROUPS_PER_ORDER = BUILDER
                .comment("Maximum selected Recruits groups resolved by one RTS order. Large private-server battles need more than the old hardcoded 16.")
                .defineInRange("maxGroupsPerOrder", 64, 16, 128);
        MAX_RECRUITS_PER_ORDER = BUILDER
                .comment("Maximum loaded owned recruits resolved by one RTS order. If reached, the order is rejected rather than silently truncating the army.")
                .defineInRange("maxRecruitsPerOrder", 2048, 512, 4096);
        BUILDER.pop();

        BUILDER.push("fogOfWar");
        FOG_OF_WAR = BUILDER
                .comment("Enable tactical Fog of War visibility logic and hidden-target checks for RTS orders. Client visual rendering can be disabled independently.")
                .define("enabled", true);
        FOG_PLAYER_VISION_RADIUS = BUILDER
                .comment("Vision radius around the commander's real player body. The free camera itself grants no vision.")
                .defineInRange("playerVisionRadius", 48, 8, 160);
        FOG_RECRUIT_VISION_RADIUS = BUILDER
                .comment("Vision radius around ordinary owned recruits.")
                .defineInRange("recruitVisionRadius", 40, 8, 128);
        FOG_CAVALRY_VISION_RADIUS = BUILDER
                .comment("Vision radius around mounted owned recruits.")
                .defineInRange("cavalryVisionRadius", 48, 8, 160);
        FOG_SHIP_VISION_RADIUS = BUILDER
                .comment("Vision radius around owned Captains currently commanding a Small Ships vessel.")
                .defineInRange("shipVisionRadius", 64, 8, 192);
        FOG_BLOCK_HIDDEN_TARGETING = BUILDER
                .comment("Reject attack and strategic-fire targets that are outside friendly vision.")
                .define("blockHiddenTargeting", true);
        BUILDER.pop();

        BUILDER.push("formationDoctrine");
        FORMATION_DOCTRINE_ENABLED = BUILDER
                .comment(
                        "Enable the private-server tactical buffs/debuffs attached to preset formations.",
                        "This affects loaded recruits server-side and is intentionally separate from formation geometry."
                )
                .define("enabled", true);
        BUILDER.pop();

        BUILDER.push("tacticalRuntime");
        NATIVE_FORMATION_RUNTIME = BUILDER
                .comment("Use the built-in slot, route, and formation movement controller.")
                .define("nativeFormationRuntime", true);
        RUNTIME_BRAIN = BUILDER
                .comment("Maintain transient orthogonal recruit runtime state and dirty flags.")
                .define("runtimeBrain", true);
        GOAL_SUPPRESSION = BUILDER
                .comment("Gate native utility movement goals while the RTS formation runtime owns movement.")
                .define("goalSuppression", true);
        AI_SCHEDULER = BUILDER
                .comment("Budget formation and individual recovery route calculations across server ticks.")
                .define("aiScheduler", true);
        LOCAL_STEERING = BUILDER
                .comment("Use damped slot steering and spatial-grid friendly separation before requesting recovery paths.")
                .define("localSteering", true);
        TACTICAL_NAVIGATION = BUILDER
                .comment("Use collision-shape-aware tactical routes with stair, ladder, and hole handling.")
                .define("tacticalNavigation", true);
        PROJECTILE_AWARENESS = BUILDER
                .comment("Wake a formation and raise available shields when hostile projectiles enter its corridor or hit a member.")
                .define("projectileAwareness", true);
        FORMATION_PATHS_PER_TICK = BUILDER
                .comment("Maximum new shared formation routes calculated during one server tick.")
                .defineInRange("formationPathsPerTick", 3, 1, 32);
        AGENT_PATHS_PER_TICK = BUILDER
                .comment("Maximum local recruit recovery routes calculated during one server tick.")
                .defineInRange("agentPathsPerTick", 10, 1, 128);
        BUILDER.pop();

        BUILDER.push("formationRecovery");
        CAVALRY_STUCK_RECOVERY = BUILDER
                .comment(
                        "Allow mounted recruits that fail to converge on a formation slot for a long time to request a fresh tactical route.",
                        "Recovery never teleports the mount and must still find a collision-safe path."
                )
                .define("cavalryStuckRecovery", true);
        CAVALRY_STUCK_RECOVERY_DELAY_SECONDS = BUILDER
                .comment("Seconds a cavalry unit may fail to make meaningful progress toward its current formation slot before recovery is considered.")
                .defineInRange("cavalryStuckRecoveryDelaySeconds", 12, 4, 60);
        CAVALRY_STUCK_RECOVERY_MIN_DISTANCE = BUILDER
                .comment("Minimum slot error in blocks before stuck recovery is relevant.")
                .defineInRange("cavalryStuckRecoveryMinDistance", 3.0D, 1.5D, 8.0D);
        CAVALRY_STUCK_RECOVERY_MAX_DISTANCE = BUILDER
                .comment("Legacy compatibility value retained for existing server configs. Tactical recovery never snaps or teleports cavalry.")
                .defineInRange("cavalryStuckRecoveryMaxDistance", 12.0D, 4.0D, 24.0D);
        FORMATION_ORDER_WATCHDOG = BUILDER
                .comment("Keep an explicit RTS move/reform authoritative while the formation runtime is stalled by combat or cohesion, and repair long stalls conservatively.")
                .define("formationOrderWatchdog", true);
        FORMATION_STALL_RECOVERY_TICKS = BUILDER
                .comment("Ticks without meaningful formation-centre progress before the RTS watchdog begins a conservative centre nudge.")
                .defineInRange("formationStallRecoveryTicks", 20, 8, 100);
        FORMATION_STALL_NUDGE_STEP = BUILDER
                .comment("Maximum extra blocks/tick used only while a commanded formation is genuinely stalled. This is not applied during ordinary progress.")
                .defineInRange("formationStallNudgeStep", 0.08D, 0.02D, 0.18D);
        BUILDER.pop();

        BUILDER.push("cavalry");
        CAVALRY_FORMATION_SPEED = BUILDER.comment("Mounted formation travel target in blocks/tick. Foot formations use about 0.2. Terrain, cohesion and mount armor can reduce actual pace.")
                .defineInRange("formationSpeed", 0.50D, 0.25D, 1.0D);
        CAVALRY_HASTY_SPEED = BUILDER.comment("Mounted Hasty Move target in blocks/tick. 0.72 is 14.4 blocks/second before terrain and armor penalties.")
                .defineInRange("hastySpeed", 0.72D, 0.30D, 1.2D);
        CAVALRY_COMBAT_SPEED_MULTIPLIER = BUILDER.comment("Temporary mounted-only movement multiplier during native combat/follow movement. Runtime is capped at 1.25 and removes it when the recruit dismounts.")
                .defineInRange("combatSpeedMultiplier", 1.25D, 1.0D, 1.25D);
        CAVALRY_CHARGE_DAMAGE = BUILDER.comment("Maximum extra damage on a full-speed, full-run-up cavalry impact, before normal armor and damage mitigation. Later contacts spend momentum. Zero disables impact damage.")
                .defineInRange("maxChargeBonusDamage", 48.0D, 0.0D, 120.0D);
        CAVALRY_CHARGE_RUNUP = BUILDER.comment("Uninterrupted advance distance required for full charge momentum.")
                .defineInRange("fullChargeRunupBlocks", 22.0D, 8.0D, 60.0D);
        CAVALRY_FULL_CHARGE_SPEED = BUILDER.comment("Measured horizontal blocks/tick required for full charge damage. Teleports, falls and sharp turns do not build momentum.")
                .defineInRange("fullChargeSpeed", 0.72D, 0.40D, 1.2D);
        CAVALRY_MELEE_SHOCK = BUILDER.comment("Maximum immediate group morale loss from a cavalry impact against foot melee. The strongest impact sets the total shock budget for each defending group over two seconds.")
                .defineInRange("meleeChargeMoraleShock", 14.0D, 0.0D, 100.0D);
        CAVALRY_RANGED_SHOCK = BUILDER.comment("Maximum cavalry charge morale shock against dismounted ranged troops.")
                .defineInRange("rangedChargeMoraleShock", 30.0D, 0.0D, 100.0D);
        CAVALRY_MELEE_PRESSURE = BUILDER.comment("Additional group morale loss per second of damaging close combat against cavalry. Applied once per defending group, not once per horse.")
                .defineInRange("meleeMoralePressurePerSecond", 2.0D, 0.0D, 30.0D);
        CAVALRY_RANGED_PRESSURE = BUILDER.comment("Additional group morale loss per second when cavalry damages dismounted ranged troops in close combat.")
                .defineInRange("rangedMoralePressurePerSecond", 8.0D, 0.0D, 50.0D);
        BUILDER.pop();

        BUILDER.push("projectiles");
        ARROW_LIGHT_ARMOR_DAMAGE = BUILDER.comment("Recruit arrows vs unarmored recruits; scales down to 1x at 20 armor. Vanilla mitigation follows. 1 disables the bonus.")
                .defineInRange("arrowLightArmorDamageMultiplier", 2.0D, 1.0D, 5.0D);
        JAVELIN_LIGHT_ARMOR_DAMAGE = BUILDER.comment("Commander thrown weapons vs unarmored recruits, fading to 1x at 20 armor.")
                .defineInRange("javelinLightArmorDamageMultiplier", 1.6D, 1.0D, 5.0D);
        BUILDER.pop();

        BUILDER.push("encumbrance");
        ENCUMBRANCE_ENABLED = BUILDER.comment("Apply full-inventory weight to recruit speed and fatigue.")
                .define("enabled", true);
        ENCUMBRANCE_FOOT_CAPACITY = BUILDER.comment("Base carrying capacity for ordinary recruits.")
                .defineInRange("footCapacity", 36.0D, 10.0D, 100.0D);
        ENCUMBRANCE_RANGED_CAPACITY = BUILDER.comment("Lower base capacity for bowmen, crossbowmen and nomads, encouraging light secondary weapons.")
                .defineInRange("rangedCapacity", 30.0D, 10.0D, 100.0D);
        ENCUMBRANCE_MOUNTED_BONUS = BUILDER.comment("Capacity added while a recruit rides a living mount.")
                .defineInRange("mountedCapacityBonus", 14.0D, 0.0D, 100.0D);
        ENCUMBRANCE_MAX_SPEED_PENALTY = BUILDER.comment("Maximum movement-speed reduction from inventory weight.")
                .defineInRange("maximumSpeedPenalty", 0.48D, 0.0D, 0.80D);
        ENCUMBRANCE_FATIGUE_SCALE = BUILDER.comment("Additional fatigue gain per point of load ratio above the comfortable threshold.")
                .defineInRange("fatigueScale", 1.35D, 0.0D, 4.0D);
        BUILDER.pop();
        SERVER = BUILDER.build();
    }

    private RTSServerConfig() {
    }
}
