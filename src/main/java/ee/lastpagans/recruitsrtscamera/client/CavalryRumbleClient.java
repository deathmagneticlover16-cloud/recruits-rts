package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.network.CavalryRumblePacket;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owns positional cavalry loops, their slow envelope, and a distance-filtered audio layer. */
public final class CavalryRumbleClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<UUID, GroupRumble> SOUNDS = new HashMap<>();

    private CavalryRumbleClient() { }

    public static void update(CavalryRumblePacket packet) {
        if (packet == null || MC.getSoundManager() == null) return;
        GroupRumble sound = SOUNDS.get(packet.groupId());
        if (packet.active()) {
            if (sound == null || sound.isStopped()) {
                sound = new GroupRumble(packet.center(), packet.volume(), packet.pitch());
                SOUNDS.put(packet.groupId(), sound);
                sound.play();
            } else {
                sound.update(packet.center(), packet.volume(), packet.pitch());
            }
        } else if (sound != null) {
            sound.beginFadeOut();
        }
    }

    public static void tick() {
        SOUNDS.values().forEach(GroupRumble::tick);
        SOUNDS.entrySet().removeIf(entry -> entry.getValue().isStopped());
    }

    public static void reset() {
        for (GroupRumble sound : SOUNDS.values()) sound.stopNow();
        SOUNDS.clear();
    }

    private static final class GroupRumble {
        private static final float FADE_IN_STEP = 1.0F / 120.0F;
        private static final float FADE_OUT_STEP = 1.0F / 120.0F;
        private static final double FILTER_START = 8.0D;
        private static final double FILTER_END = 34.0D;
        private final RumbleLayer clear;
        private final RumbleLayer distant;
        private float fade;
        private float targetVolume;
        private float targetPitch;
        private Vec3 targetPosition;
        private int ticksWithoutUpdate;
        private boolean fadingOut;

        GroupRumble(Vec3 position, float volume, float pitch) {
            targetPosition = position;
            targetVolume = volume;
            targetPitch = pitch;
            clear = new RumbleLayer(RTSSounds.CAVALRY_RUMBLE.get(), position, pitch);
            distant = new RumbleLayer(RTSSounds.CAVALRY_RUMBLE_DISTANT.get(), position, pitch);
        }

        void play() {
            MC.getSoundManager().play(clear);
            MC.getSoundManager().play(distant);
        }

        void update(Vec3 position, float volume, float pitch) {
            targetPosition = position;
            targetVolume = volume;
            targetPitch = pitch;
            ticksWithoutUpdate = 0;
            fadingOut = false;
        }

        void beginFadeOut() {
            fadingOut = true;
            ticksWithoutUpdate = 0;
        }

        void tick() {
            if (++ticksWithoutUpdate > 15) beginFadeOut();
            if (fadingOut) fade = Math.max(0.0F, fade - FADE_OUT_STEP);
            else fade = Math.min(1.0F, fade + FADE_IN_STEP);

            Entity listener = MC.getCameraEntity();
            double distance = listener == null ? 0.0D : listener.position().distanceTo(targetPosition);
            float filterMix = smoothStep(FILTER_START, FILTER_END, distance);
            float gain = targetVolume * fade;
            clear.update(targetPosition, gain * (1.0F - filterMix), targetPitch);
            distant.update(targetPosition, gain * filterMix, targetPitch);

            if (fadingOut && fade <= 0.0F) stopNow();
        }

        boolean isStopped() { return clear.isStopped() && distant.isStopped(); }

        void stopNow() {
            clear.stopNow();
            distant.stopNow();
        }

        private static float smoothStep(double start, double end, double value) {
            float t = (float) Mth.clamp((value - start) / (end - start), 0.0D, 1.0D);
            return t * t * (3.0F - 2.0F * t);
        }
    }

    private static final class RumbleLayer extends AbstractTickableSoundInstance {
        RumbleLayer(SoundEvent event, Vec3 position, float pitch) {
            super(event, SoundSource.NEUTRAL, RandomSource.create());
            looping = true;
            delay = 0;
            attenuation = SoundInstance.Attenuation.LINEAR;
            x = position.x;
            y = position.y;
            z = position.z;
            volume = 0.0F;
            this.pitch = pitch;
        }

        void update(Vec3 position, float gain, float targetPitch) {
            x += (position.x - x) * 0.25D;
            y += (position.y - y) * 0.25D;
            z += (position.z - z) * 0.25D;
            volume = Math.max(0.0F, gain);
            pitch += (targetPitch - pitch) * 0.10F;
        }

        void stopNow() { stop(); }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            // GroupRumble owns the shared envelope and distance crossfade.
        }
    }
}
