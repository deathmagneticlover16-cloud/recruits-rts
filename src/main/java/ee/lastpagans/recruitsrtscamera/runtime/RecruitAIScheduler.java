package ee.lastpagans.recruitsrtscamera.runtime;

import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Main-thread budget scheduler with key-based request deduplication. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecruitAIScheduler {
    private static final long SERVER_TICK_WORK_BUDGET_NANOS = 15_000_000L;
    public enum Priority { CRITICAL, HIGH, MEDIUM, LOW }
    public enum WorkType { FORMATION_PATH, AGENT_PATH, PERCEPTION, MAINTENANCE }

    private static final Map<Priority, ArrayDeque<Work>> QUEUES = new EnumMap<>(Priority.class);
    private static final Set<Key> PENDING = new HashSet<>();

    static {
        for (Priority priority : Priority.values()) QUEUES.put(priority, new ArrayDeque<>());
    }

    private RecruitAIScheduler() {
    }

    public static boolean submit(Priority priority, WorkType type, Object identity, Runnable task) {
        if (priority == null || type == null || identity == null || task == null) return false;
        Key key = new Key(type, identity);
        if (!PENDING.add(key)) return false;
        QUEUES.get(priority).addLast(new Work(key, task));
        return true;
    }

    public static int queued() {
        return QUEUES.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long workStarted = System.nanoTime();
        EnumMap<WorkType, Integer> budgets = new EnumMap<>(WorkType.class);
        boolean budgeted = RTSServerConfig.AI_SCHEDULER.get();
        budgets.put(WorkType.FORMATION_PATH, budgeted ? RTSServerConfig.FORMATION_PATHS_PER_TICK.get() : Integer.MAX_VALUE);
        budgets.put(WorkType.AGENT_PATH, budgeted ? RTSServerConfig.AGENT_PATHS_PER_TICK.get() : Integer.MAX_VALUE);
        budgets.put(WorkType.PERCEPTION, budgeted ? 32 : Integer.MAX_VALUE);
        budgets.put(WorkType.MAINTENANCE, budgeted ? 32 : Integer.MAX_VALUE);
        int criticalGuard = 256;
        priorityLoop:
        for (Priority priority : Priority.values()) {
            ArrayDeque<Work> queue = QUEUES.get(priority);
            int checked = queue.size();
            while (checked-- > 0 && !queue.isEmpty()) {
                Work work = queue.removeFirst();
                int remaining = budgets.getOrDefault(work.key.type, 0);
                if (priority != Priority.CRITICAL && remaining <= 0) {
                    queue.addLast(work);
                    continue;
                }
                if (priority == Priority.CRITICAL && criticalGuard-- <= 0) {
                    queue.addFirst(work);
                    break;
                }
                if (priority != Priority.CRITICAL
                        && System.nanoTime() - workStarted >= SERVER_TICK_WORK_BUDGET_NANOS) {
                    queue.addFirst(work);
                    break priorityLoop;
                }
                if (priority != Priority.CRITICAL) budgets.put(work.key.type, remaining - 1);
                PENDING.remove(work.key);
                try {
                    work.task.run();
                } catch (RuntimeException exception) {
                    RecruitsRTSCameraMod.LOGGER.error("Scheduled recruit work {} failed", work.key, exception);
                }
            }
        }
        if (QUEUES.values().stream().anyMatch(queue -> !queue.isEmpty())) {
            RecruitRuntimeMetrics.SCHEDULER_OVERRUNS.increment();
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        QUEUES.values().forEach(ArrayDeque::clear);
        PENDING.clear();
    }

    private record Key(WorkType type, Object identity) {}
    private record Work(Key key, Runnable task) {}
}
