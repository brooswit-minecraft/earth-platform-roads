package io.github.brooswitminecraft.earthplatformroads;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Earth Platform Roads. Registers the mod with no gameplay logic;
 * add your own event listeners, registries, and config here.
 */
@Mod(EarthPlatformRoadsMod.MODID)
public class EarthPlatformRoadsMod {
    public static final String MODID = "earthplatformroads";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EarthPlatformRoadsMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Earth Platform Roads loaded");
    }
}
