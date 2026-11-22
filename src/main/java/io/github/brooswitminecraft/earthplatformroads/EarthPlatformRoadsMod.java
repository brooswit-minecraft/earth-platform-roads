package io.github.brooswitminecraft.earthplatformroads;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.earthplatformroads.compat.layeredslopes.LayeredSlopesCompat;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Earth Platform Roads: registers the layered-blackstone compat block used by
 * {@code EarthGeneratorRoadMixin} for inclined road surfaces.
 */
@Mod(EarthPlatformRoadsMod.MODID)
public class EarthPlatformRoadsMod {
    public static final String MODID = "earthplatformroads";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EarthPlatformRoadsMod(IEventBus modEventBus, ModContainer modContainer) {
        LayeredSlopesCompat.register(modEventBus);
        LOGGER.info("Earth Platform Roads loaded");
    }
}
