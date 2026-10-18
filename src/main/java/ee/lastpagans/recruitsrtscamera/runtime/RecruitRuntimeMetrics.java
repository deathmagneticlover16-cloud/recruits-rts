package ee.lastpagans.recruitsrtscamera.runtime;

import com.mojang.brigadier.CommandDispatcher;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.LongAdder;

/** Low-overhead counters for before/after battlefield profiling. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitRuntimeMetrics {
    public static final LongAdder BRAIN_UPDATES = new LongAdder();
    public static final LongAdder FORMATION_PATHS = new LongAdder();
    public static final LongAdder AGENT_PATHS = new LongAdder();
    public static final LongAdder PATH_FAILURES = new LongAdder();
    public static final LongAdder CONSTRAINED_ROUTES = new LongAdder();
    public static final LongAdder PROJECTILE_ALERTS = new LongAdder();
    public static final LongAdder SCHEDULER_OVERRUNS = new LongAdder();
    public static final LongAdder NATIVE_GOAL_EVALUATIONS = new LongAdder();
    public static final LongAdder GOALS_SUPPRESSED = new LongAdder();
    public static final LongAdder LOCAL_STEERING_UPDATES = new LongAdder();
    public static final LongAdder RECOVERY_PATHS = new LongAdder();
    public static final LongAdder TERRAIN_INVALIDATIONS = new LongAdder();

    private RecruitRuntimeMetrics() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("recruitsperf")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(summary()), false);
                    return 1;
                }))
                .then(Commands.literal("scheduler").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(schedulerSummary()), false);
                    return 1;
                }))
                .then(Commands.literal("ai").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(aiSummary()), false);
                    return 1;
                }))
                .then(Commands.literal("navigation").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(navigationSummary()), false);
                    return 1;
                }))
                .then(Commands.literal("resetstats").executes(context -> {
                    reset();
                    context.getSource().sendSuccess(() -> Component.literal("Recruit runtime counters reset."), false);
                    return 1;
                })));
    }

    public static String summary() {
        return "Recruit runtime · brains=" + BRAIN_UPDATES.sum()
                + " · formation paths=" + FORMATION_PATHS.sum()
                + " · agent paths=" + AGENT_PATHS.sum()
                + " · failed paths=" + PATH_FAILURES.sum()
                + " · constrained routes=" + CONSTRAINED_ROUTES.sum()
                + " · projectile alerts=" + PROJECTILE_ALERTS.sum()
                + " · scheduler overruns=" + SCHEDULER_OVERRUNS.sum();
    }

    public static String schedulerSummary() {
        return "Recruit scheduler · queued=" + RecruitAIScheduler.queued()
                + " · overruns=" + SCHEDULER_OVERRUNS.sum();
    }

    public static String aiSummary() {
        return "Recruit AI · registered=" + RecruitRuntimeRegistry.brainCount()
                + " · RTS authority=" + RecruitRuntimeRegistry.authorityCount(RecruitRuntimeBrain.MovementAuthority.RTS_FORMATION)
                + " · native authority=" + RecruitRuntimeRegistry.authorityCount(RecruitRuntimeBrain.MovementAuthority.NATIVE_RECRUITS)
                + " · hot=" + RecruitRuntimeRegistry.simulationCount(RecruitRuntimeBrain.SimulationState.HOT)
                + " · warm=" + RecruitRuntimeRegistry.simulationCount(RecruitRuntimeBrain.SimulationState.WARM)
                + " · cold=" + RecruitRuntimeRegistry.simulationCount(RecruitRuntimeBrain.SimulationState.COLD)
                + " · goal checks=" + NATIVE_GOAL_EVALUATIONS.sum()
                + " · suppressed=" + GOALS_SUPPRESSED.sum();
    }

    public static String navigationSummary() {
        return "Recruit navigation · formation paths=" + FORMATION_PATHS.sum()
                + " · agent paths=" + AGENT_PATHS.sum()
                + " · recoveries=" + RECOVERY_PATHS.sum()
                + " · local steering=" + LOCAL_STEERING_UPDATES.sum()
                + " · failures=" + PATH_FAILURES.sum()
                + " · terrain invalidations=" + TERRAIN_INVALIDATIONS.sum();
    }

    public static void reset() {
        BRAIN_UPDATES.reset();
        FORMATION_PATHS.reset();
        AGENT_PATHS.reset();
        PATH_FAILURES.reset();
        CONSTRAINED_ROUTES.reset();
        PROJECTILE_ALERTS.reset();
        SCHEDULER_OVERRUNS.reset();
        NATIVE_GOAL_EVALUATIONS.reset();
        GOALS_SUPPRESSED.reset();
        LOCAL_STEERING_UPDATES.reset();
        RECOVERY_PATHS.reset();
        TERRAIN_INVALIDATIONS.reset();
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        reset();
    }
}
