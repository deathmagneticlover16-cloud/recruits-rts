package ee.lastpagans.recruitsrtscamera;

import com.mojang.logging.LogUtils;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.registry.RTSEntities;
import ee.lastpagans.recruitsrtscamera.registry.RTSSounds;
import ee.lastpagans.recruitsrtscamera.config.RTSServerConfig;
import ee.lastpagans.recruitsrtscamera.config.RTSClientConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(RecruitsRTSCameraMod.MOD_ID)
public final class RecruitsRTSCameraMod {
    public static final String MOD_ID = "recruits_rts_camera";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecruitsRTSCameraMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        RTSEntities.register(modBus);
        RTSSounds.register(modBus);

        ModLoadingContext.get().registerConfig(
                ModConfig.Type.SERVER,
                RTSServerConfig.SERVER,
                "recruits-rts-camera-server.toml"
        );
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.CLIENT,
                RTSClientConfig.CLIENT,
                "recruits-rts-camera-client.toml"
        );
        RTSCameraNetwork.register();
    }
}
