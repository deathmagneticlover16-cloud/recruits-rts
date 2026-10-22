package ee.lastpagans.recruitsrtscamera.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Optional registry-only integration with Icy horse sound mods; no hard dependency. */
public final class IcyHorseSoundsCompat {
    private static final float NEIGH_CHANCE = 0.075F;
    private static final float SNORT_CHANCE = 0.35F;
    private static final int MIN_OPPORTUNITY_TICKS = 40;
    private static final int OPPORTUNITY_VARIANCE_TICKS = 40;
    private static final Map<UUID, Long> NEXT_OPPORTUNITY = new HashMap<>();
    private static List<SoundEvent> neighs = List.of();
    private static List<SoundEvent> snorts = List.of();
    private static boolean resolved;

    private IcyHorseSoundsCompat() { }

    public static void maybePlay(
            ServerLevel level,
            UUID groupId,
            List<? extends Entity> mounts,
            boolean running,
            long now
    ) {
        if (level == null || groupId == null || mounts == null || mounts.isEmpty() || !running) return;
        resolve();
        if (neighs.isEmpty() && snorts.isEmpty()) return;
        if (now < NEXT_OPPORTUNITY.getOrDefault(groupId, 0L)) return;
        NEXT_OPPORTUNITY.put(groupId, now + MIN_OPPORTUNITY_TICKS
                + level.random.nextInt(OPPORTUNITY_VARIANCE_TICKS + 1));

        float roll = level.random.nextFloat();
        List<SoundEvent> candidates;
        float volume;
        if (!neighs.isEmpty() && roll < NEIGH_CHANCE) {
            candidates = neighs;
            volume = 0.82F;
        } else if (!snorts.isEmpty() && roll < NEIGH_CHANCE + SNORT_CHANCE) {
            candidates = snorts;
            volume = 0.62F;
        } else {
            return;
        }

        Entity horse = mounts.get(level.random.nextInt(mounts.size()));
        SoundEvent sound = candidates.get(level.random.nextInt(candidates.size()));
        float pitch = 0.92F + level.random.nextFloat() * 0.16F;
        level.playSound(null, horse.getX(), horse.getY(), horse.getZ(),
                sound, SoundSource.NEUTRAL, volume, pitch);
    }

    public static void retainGroups(Collection<UUID> activeGroups) {
        if (activeGroups == null) NEXT_OPPORTUNITY.clear();
        else NEXT_OPPORTUNITY.keySet().retainAll(new HashSet<>(activeGroups));
    }

    public static void reset() {
        NEXT_OPPORTUNITY.clear();
        neighs = List.of();
        snorts = List.of();
        resolved = false;
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        Set<String> namespaces = new HashSet<>();
        for (IModInfo info : ModList.get().getMods()) {
            String identity = (info.getModId() + " " + info.getDisplayName()).toLowerCase(Locale.ROOT);
            if (identity.contains("icy")) namespaces.add(info.getModId());
        }
        if (namespaces.isEmpty()) return;

        List<ResourceLocation> sounds = new ArrayList<>(ForgeRegistries.SOUND_EVENTS.getKeys());
        sounds.sort(Comparator.comparing(ResourceLocation::toString));
        List<SoundEvent> foundNeighs = new ArrayList<>();
        List<SoundEvent> foundSnorts = new ArrayList<>();
        for (ResourceLocation id : sounds) {
            if (!namespaces.contains(id.getNamespace())) continue;
            String path = id.getPath().toLowerCase(Locale.ROOT);
            SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(id);
            if (event == null) continue;
            if (path.contains("neigh") || path.contains("whinny")) foundNeighs.add(event);
            if (path.contains("snort")) foundSnorts.add(event);
        }
        neighs = List.copyOf(foundNeighs);
        snorts = List.copyOf(foundSnorts);
    }
}
