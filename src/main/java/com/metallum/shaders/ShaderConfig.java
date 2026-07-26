package com.metallum.shaders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight runtime configuration. Mirrors the kind of options MakeUp-UltraFast
 * exposes (bloom, volumetric fog, tone mapping, held-light radius, etc.) but
 * without any GUI — edit the JSON file at
 * {@code config/metallum_shaders.json} and reload with F3+R (handled in the
 * client mixin).
 */
public final class ShaderConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("metallum_shaders.json");

    public static ShaderConfig INSTANCE = new ShaderConfig();

    // ---- Master ----
    public boolean enabled = true;
    public int renderScale = 100;            // 100 = native, 50 = half-res
    public boolean vignette = true;

    // ---- Lighting ----
    public boolean deferredLighting = true;
    public boolean movingLightSources = true;
    public int maxDynamicLights = 16;
    public float heldLightRadius = 12.0f;
    public float heldLightIntensity = 1.4f;
    public float torchFlickerStrength = 0.12f;

    // ---- Atmosphere ----
    public boolean volumetricFog = true;
    public float fogDensity = 0.018f;
    public float fogFalloff = 1.6f;
    public float skyFogBlend = 0.45f;

    // ---- Post ----
    public boolean bloom = true;
    public float bloomStrength = 0.65f;
    public float bloomThreshold = 0.85f;
    public int bloomPasses = 2;

    public boolean toneMapping = true;
    public float exposure = 1.05f;

    public boolean saturationBoost = true;
    public float saturation = 1.12f;

    // ---- Performance ----
    public boolean halfPrecisionDepth = true;
    public boolean earlyOutOnCutscene = true;

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(CONFIG_PATH), ShaderConfig.class);
                LOGGER.info("Loaded config from {}", CONFIG_PATH);
            } else {
                save();
                LOGGER.info("Wrote default config to {}", CONFIG_PATH);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load config, using defaults", e);
        }
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            LOGGER.warn("Could not save config", e);
        }
    }

    public static void reload() {
        load();
        LOGGER.info("Config reloaded");
    }
}
