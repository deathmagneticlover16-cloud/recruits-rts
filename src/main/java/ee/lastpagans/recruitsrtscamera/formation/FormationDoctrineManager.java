package ee.lastpagans.recruitsrtscamera.formation;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import ee.lastpagans.recruitsrtscamera.RecruitsRTSCameraMod;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.movement.RTSMovementBoostManager;
import ee.lastpagans.recruitsrtscamera.runtime.RecruitRuntimeRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/** Applies the currently remembered formation doctrine to loaded recruits once per second. */
@Mod.EventBusSubscriber(modid = RecruitsRTSCameraMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FormationDoctrineManager {
    private static final UUID SPEED_ID = UUID.fromString("9a9d2ab0-47ec-4b0c-9ee8-7f3f92471a01");
    private static final UUID ATTACK_ID = UUID.fromString("9a9d2ab0-47ec-4b0c-9ee8-7f3f92471a02");
    private static final UUID KNOCKBACK_ID = UUID.fromString("9a9d2ab0-47ec-4b0c-9ee8-7f3f92471a03");
    private static final UUID BASE_KNOCKBACK_ID = UUID.fromString("9a9d2ab0-47ec-4b0c-9ee8-7f3f92471a04");
    private static final double BASE_INFANTRY_KNOCKBACK_RESISTANCE = 0.45D;
    private static final double BASE_CAVALRY_KNOCKBACK_RESISTANCE = 0.60D;

    private FormationDoctrineManager() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % 20 != 0) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            for (AbstractRecruitEntity recruit : RecruitRuntimeRegistry.loaded(level)) refresh(level, recruit);
        }
    }

    private static void refresh(ServerLevel level, AbstractRecruitEntity recruit) {
        if (!recruit.isAlive()) {
            remove(recruit, Attributes.MOVEMENT_SPEED, SPEED_ID);
            remove(recruit, Attributes.ATTACK_DAMAGE, ATTACK_ID);
            remove(recruit, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
            remove(recruit, Attributes.KNOCKBACK_RESISTANCE, BASE_KNOCKBACK_ID);
            return;
        }

        // Large formations look much more stable when ordinary weapon hits cause a
        // stagger rather than launching recruits several blocks out of line. This is a
        // baseline server-fork rule; formation doctrine can still add more resistance.
        ensure(recruit, Attributes.KNOCKBACK_RESISTANCE, BASE_KNOCKBACK_ID,
                "RTS battlefield mass",
                RTSMovementBoostManager.isCavalry(recruit)
                        ? BASE_CAVALRY_KNOCKBACK_RESISTANCE
                        : BASE_INFANTRY_KNOCKBACK_RESISTANCE,
                AttributeModifier.Operation.ADDITION);

        if (!RTSServerConfig.FORMATION_DOCTRINE_ENABLED.get()) {
            removeDoctrine(recruit);
            return;
        }
        UUID groupId = recruit.getGroup();
        if (groupId == null) {
            removeDoctrine(recruit);
            return;
        }

        // Doctrine starts only once RTS Camera has an explicit remembered formation for
        // this group. That prevents every ordinary Recruits group from silently receiving
        // the MARCH modifiers merely because MARCH is the fallback formation. Custom-drawn
        // formations are deliberately neutral for now; doctrine belongs to named presets
        // (and frontage, which is stored as LINE) until we design custom-shape tactics.
        var state = FormationService.state(level, groupId);
        if (state == null || state.mode() == FormationMode.CUSTOM) {
            removeDoctrine(recruit);
            return;
        }

        FormationKind kind = state.active();
        FormationDoctrine doctrine = FormationDoctrine.forKind(kind);
        ensure(recruit, Attributes.MOVEMENT_SPEED, SPEED_ID,
                "RTS formation movement doctrine", doctrine.movementMultiplier(), AttributeModifier.Operation.MULTIPLY_TOTAL);
        ensure(recruit, Attributes.ATTACK_DAMAGE, ATTACK_ID,
                "RTS formation attack doctrine", doctrine.attackMultiplier(), AttributeModifier.Operation.MULTIPLY_TOTAL);
        ensure(recruit, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID,
                "RTS formation cohesion doctrine", doctrine.knockbackResistanceAdd(), AttributeModifier.Operation.ADDITION);
    }

    private static void ensure(
            AbstractRecruitEntity recruit,
            net.minecraft.world.entity.ai.attributes.Attribute attribute,
            UUID id,
            String name,
            double amount,
            AttributeModifier.Operation operation
    ) {
        AttributeInstance instance = recruit.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier existing = instance.getModifier(id);
        if (Math.abs(amount) <= 1.0E-6D) {
            if (existing != null) instance.removeModifier(id);
            return;
        }
        if (existing != null && Math.abs(existing.getAmount() - amount) <= 1.0E-6D
                && existing.getOperation() == operation) {
            return;
        }
        if (existing != null) instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, name, amount, operation));
    }

    private static void removeDoctrine(AbstractRecruitEntity recruit) {
        remove(recruit, Attributes.MOVEMENT_SPEED, SPEED_ID);
        remove(recruit, Attributes.ATTACK_DAMAGE, ATTACK_ID);
        remove(recruit, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
    }

    private static void remove(
            AbstractRecruitEntity recruit,
            net.minecraft.world.entity.ai.attributes.Attribute attribute,
            UUID id
    ) {
        AttributeInstance instance = recruit.getAttribute(attribute);
        if (instance != null && instance.getModifier(id) != null) {
            instance.removeModifier(id);
        }
    }
}
