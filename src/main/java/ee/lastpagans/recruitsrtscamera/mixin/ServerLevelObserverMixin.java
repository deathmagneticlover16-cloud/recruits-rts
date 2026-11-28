package ee.lastpagans.recruitsrtscamera.mixin;

import ee.lastpagans.recruitsrtscamera.network.CameraSoundPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.server.RTSObserverManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Relays server sound and smoke broadcasts to detached RTS camera observers. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelObserverMixin {
    @Inject(method = "playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"), require = 0)
    private void recruitsRts$relayCameraSound(Player excluded, double x, double y, double z,
                                               SoundEvent sound, SoundSource source,
                                               float volume, float pitch, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        Vec3 position = new Vec3(x, y, z);
        double radius = Math.min(192.0D, Math.max(16.0D, volume * 16.0D));
        for (ServerPlayer listener : RTSObserverManager.remoteListeners(level, position, radius)) {
            if (listener == excluded) continue;
            RTSCameraNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> listener),
                    new CameraSoundPacket(BuiltInRegistries.SOUND_EVENT.getKey(sound), source,
                            x, y, z, volume, pitch));
        }
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
            at = @At("HEAD"), require = 0)
    private <T extends ParticleOptions> void recruitsRts$relayCameraSmoke(
            T particle, double x, double y, double z, int count,
            double spreadX, double spreadY, double spreadZ, double speed,
            CallbackInfoReturnable<Integer> callback) {
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
        if (key == null) return;
        String path = key.getPath();
        if (!(path.contains("smoke") || path.contains("fog") || path.contains("powder"))) return;
        ServerLevel level = (ServerLevel) (Object) this;
        Vec3 position = new Vec3(x, y, z);
        for (ServerPlayer listener : RTSObserverManager.remoteListeners(level, position, 256.0D, 32.0D)) {
            level.sendParticles(listener, particle, true, x, y, z,
                    Math.min(128, Math.max(1, count)), spreadX, spreadY, spreadZ, speed);
        }
    }
}
