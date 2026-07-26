package com.metallum.shaders;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common-side entry point. The actual shader work is all client-side
 * (see {@link com.metallum.shaders.client.MetallumShadersClient}); this
 * class only exists so the mod has a stable common entrypoint for
 * metadata, config loading, and platform detection.
 */
public final class MetallumShadersMod implements ModInitializer {

    public static final String MOD_ID = "metallum_shaders";
    public static final String MOD_NAME = "Metallum Shaders";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitialize() {
        LOGGER.info("[{}] Booting on Minecraft 26.2 / Java 25 / Fabric Loom 1.16.2", MOD_NAME);
        LOGGER.info("[{}] This mod requires the Metallum Metal backend. "
                + "If you see GL errors, install Metallum first.", MOD_NAME);

        // Load the (very small) config from disk; defaults are sane.
        ShaderConfig.load();
    }
}
