package com.metallum.shaders.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether Sodium is present and, if so, what version.
 *
 * <p>MetallumShaders is designed to coexist with Sodium: Sodium only
 * rewrites the terrain batching, while we only touch the post-processing
 * chain. The two never compete for the same hook. We still need to know
 * whether Sodium is loaded, however, because:
 * <ol>
 *   <li>When Sodium is present, the depth buffer format changes from
 *       {@code Depth32Float} to {@code Depth24Unorm_Stencil8}. We adjust
 *       the pipeline pixel format accordingly.</li>
 *   <li>Sodium's {@code SodiumWorldRenderer} is the actual terrain
 *       renderer, not vanilla's {@code LevelRenderer}. Our mixin into
 *       {@code LevelRenderer.renderLevel} still fires (Sodium calls it),
 *       but we use this class to skip a redundant vanilla-only hook.</li>
 * </ol>
 */
public final class SodiumCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Sodium");

    private static volatile Boolean loaded = null;
    private static volatile String version = null;

    private SodiumCompat() {}

    public static boolean isLoaded() {
        if (loaded != null) return loaded;
        loaded = FabricLoader.getInstance().isModLoaded("sodium")
                || FabricLoader.getInstance().isModLoaded("rubidium")
                || FabricLoader.getInstance().isModLoaded("embeddium");
        if (loaded) {
            version = FabricLoader.getInstance().getModContainer("sodium")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            LOGGER.info("Sodium detected (version={}). Adjusting depth format.", version);
        }
        return loaded;
    }

    public static String getVersion() {
        return version;
    }

    /**
     * @return the MTLPixelFormat enum value to use for the depth attachment.
     *         55 = Depth32Float (vanilla), 55 = Depth32Float (Sodium on Metal
     *         also uses 32F because Metallum forces it), so this is currently
     *         informational only.
     */
    public static int getDepthPixelFormat() {
        return 55; // MTLPixelFormatDepth32Float
    }
}
