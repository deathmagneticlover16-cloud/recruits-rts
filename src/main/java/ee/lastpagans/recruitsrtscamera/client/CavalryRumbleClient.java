package ee.lastpagans.recruitsrtscamera.client;

import ee.lastpagans.recruitsrtscamera.network.CavalryRumblePacket;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owns continuously positioned cavalry loops and starts their fade immediately on stop. */
public final class CavalryRumbleClient {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<UUID, FadingRumble> SOUNDS = new HashMap<>();

    private CavalryRumbleClient() { }

    public static void update(CavalryRumblePacket packet) {
        if (packet == null || MC.getSoundManager() == null) return;
        FadingRumble sound = SOUNDS.get(packet.groupId());
        if (packet.active()) {
            if (sound == null || sound.isStopped()) {
                sound = new FadingRumble(packet.center(), packet.volume(), packet.pitch());
                SOUNDS.put(packet.groupId(), sound);
                MC.getSoundManager().play(sound);
            } else {
                sound.update(packet.center(), packet.volume(), packet.pitch());
            }
        } else if (sound != null) {
            sound.beginFadeOut();
        }
    }

    public static void tick() {
        SOUNDS.entrySet().removeIf(entry -> entry.getValue().isStopped());
    }

    public static void reset() {
        for (FadingRumble sound : SOUNDS.values()) sound.stopNow();
        SOUNDS.clear();
    }

    private static final class FadingRumble extends AbstractTickableSoundInstance {
        private static final float FADE_IN_STEP = 1.0F / 120.0F;
        private static final float FADE_OUT_STEP = 1.0F / 120.0F;
        private float fade;
        private float targetVolume;
        private float targetPitch;
        private Vec3 targetPosition;
        private int ticksWithoutUpdate;
        private boolean fadingOut;

        FadingRumble(Vec3 position, float volume, float pitch) {
            super(RTSSounds.CAVALRY_RUMBLE.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.targetPosition = position;
            this.targetVolume = volume;
            this.targetPitch = pitch;
            this.x = position.x;
            this.y = position.y;
            this.z = position.z;
            this.volume = 0.0F;
            this.pitch = pitch;
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

        void stopNow() { stop(); }

        @Override
        public boolean canStartSilent() {
            // The fade envelope intentionally begins at zero gain.
            return true;
        }

        @Override
        public void tick() {
            if (++ticksWithoutUpdate > 15) beginFadeOut();
            if (fadingOut) fade = Math.max(0.0F, fade - FADE_OUT_STEP);
            else fade = Math.min(1.0F, fade + FADE_IN_STEP);

            x += (targetPosition.x - x) * 0.25D;
            y += (targetPosition.y - y) * 0.25D;
            z += (targetPosition.z - z) * 0.25D;
            pitch += (targetPitch - pitch) * 0.10F;
            volume = targetVolume * fade;
            if (fadingOut && fade <= 0.0F) stop();
        }
    }
}
