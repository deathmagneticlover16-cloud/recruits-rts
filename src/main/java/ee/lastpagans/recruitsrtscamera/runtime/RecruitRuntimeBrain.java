package ee.lastpagans.recruitsrtscamera.runtime;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/** Transient, reconstructable execution state. Native Recruits data remains authoritative. */
public final class RecruitRuntimeBrain {
    public enum OrderState { IDLE, HOLD, MOVE, FOLLOW, PROTECT, PATROL, REST, UPKEEP, RTS_CONTROLLED }
    public enum CombatState { SAFE, ALERT, SEARCHING, ENGAGED, UNDER_FIRE, DISENGAGING, FLEEING }
    public enum MobilityState { FOOT, MOUNTING, MOUNTED, DISMOUNTING, SHIP, IMMOBILE }
    public enum FormationState { NONE, MEMBER, RECOVERING, DETACHED }
    public enum SimulationState { HOT, WARM, COLD, DORMANT }
    public enum MovementAuthority { NATIVE_RECRUITS, PERFORMANCE_BRAIN, RTS_FORMATION, EMERGENCY_RECOVERY, ROUTING }
    public enum DirtyFlag {
        ORDER, COMBAT, TARGET, NAVIGATION, FORMATION, MOBILITY, EQUIPMENT, PERCEPTION, SIMULATION
    }

    private final UUID recruitId;
    private AbstractRecruitEntity recruit;
    private UUID groupId;
    private OrderState order = OrderState.IDLE;
    private CombatState combat = CombatState.SAFE;
    private MobilityState mobility = MobilityState.FOOT;
    private FormationState formation = FormationState.NONE;
    private SimulationState simulation = SimulationState.WARM;
    private MovementAuthority authority = MovementAuthority.NATIVE_RECRUITS;
    private final EnumSet<DirtyFlag> dirty = EnumSet.allOf(DirtyFlag.class);
    private Vec3 desiredSlot;
    private Vec3 desiredVelocity = Vec3.ZERO;
    private UUID assignedTarget;
    private long lastMeaningfulMovement;
    private long lastDamage;
    private long lastWake;

    RecruitRuntimeBrain(AbstractRecruitEntity recruit) {
        this.recruitId = recruit.getUUID();
        attach(recruit);
    }

    public void attach(AbstractRecruitEntity value) {
        recruit = value;
        UUID newGroup = value.getGroup();
        if (groupId == null ? newGroup != null : !groupId.equals(newGroup)) dirty.add(DirtyFlag.FORMATION);
        groupId = newGroup;
        MobilityState nextMobility = value.getVehicle() == null ? MobilityState.FOOT : MobilityState.MOUNTED;
        if (nextMobility != mobility) dirty.add(DirtyFlag.MOBILITY);
        mobility = nextMobility;
    }

    public void claimFormation(Vec3 slot, Vec3 velocity, long gameTime) {
        boolean authorityChanged = authority != MovementAuthority.RTS_FORMATION
                || order != OrderState.RTS_CONTROLLED || formation != FormationState.MEMBER;
        boolean slotChanged = desiredSlot == null || slot == null || desiredSlot.distanceToSqr(slot) > 0.04D;
        authority = MovementAuthority.RTS_FORMATION;
        order = OrderState.RTS_CONTROLLED;
        formation = FormationState.MEMBER;
        desiredSlot = slot;
        desiredVelocity = velocity == null ? Vec3.ZERO : velocity;
        if (authorityChanged) {
            lastWake = gameTime;
            dirty.addAll(EnumSet.of(DirtyFlag.ORDER, DirtyFlag.FORMATION, DirtyFlag.SIMULATION));
        }
        if (slotChanged) dirty.add(DirtyFlag.NAVIGATION);
    }

    public void releaseFormation(long gameTime) {
        authority = MovementAuthority.NATIVE_RECRUITS;
        order = OrderState.IDLE;
        formation = FormationState.NONE;
        desiredSlot = null;
        desiredVelocity = Vec3.ZERO;
        lastWake = gameTime;
        dirty.addAll(EnumSet.of(DirtyFlag.ORDER, DirtyFlag.FORMATION, DirtyFlag.NAVIGATION));
    }

    public void underFire(long gameTime, UUID target) {
        combat = CombatState.UNDER_FIRE;
        assignedTarget = target;
        lastDamage = gameTime;
        lastWake = gameTime;
        simulation = SimulationState.HOT;
        dirty.addAll(EnumSet.of(DirtyFlag.COMBAT, DirtyFlag.PERCEPTION, DirtyFlag.TARGET, DirtyFlag.SIMULATION));
    }

    public void markMoving(long gameTime) {
        lastMeaningfulMovement = gameTime;
        if (simulation == SimulationState.DORMANT || simulation == SimulationState.COLD) {
            simulation = SimulationState.WARM;
            dirty.add(DirtyFlag.SIMULATION);
        }
    }

    public void settle(long gameTime) {
        if (combat == CombatState.UNDER_FIRE && gameTime - lastDamage > 80L) combat = CombatState.ALERT;
        if (authority == MovementAuthority.RTS_FORMATION && gameTime - lastMeaningfulMovement > 40L) {
            simulation = SimulationState.COLD;
        }
    }

    public EnumSet<DirtyFlag> takeDirtyFlags() {
        EnumSet<DirtyFlag> copy = dirty.clone();
        dirty.clear();
        return copy;
    }

    public UUID recruitId() { return recruitId; }
    public AbstractRecruitEntity recruit() { return recruit; }
    public UUID groupId() { return groupId; }
    public OrderState order() { return order; }
    public CombatState combat() { return combat; }
    public MobilityState mobility() { return mobility; }
    public FormationState formation() { return formation; }
    public SimulationState simulation() { return simulation; }
    public MovementAuthority authority() { return authority; }
    public Vec3 desiredSlot() { return desiredSlot; }
    public Vec3 desiredVelocity() { return desiredVelocity; }
    public UUID assignedTarget() { return assignedTarget; }
    public long lastWake() { return lastWake; }
}
