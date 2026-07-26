package com.metallum.shaders.shader;

import com.metallum.shaders.MetallumShadersMod;
import com.metallum.shaders.ShaderConfig;
import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the bundled {@code .metal} sources, compiles them through
 * Metallum's {@code MTLDevice}, and caches the resulting pipeline state
 * objects keyed by pass name.
 *
 * <p>Passes:
 * <ul>
 *   <li>{@code composite} — deferred lighting + volumetric fog + moving lights</li>
 *   <li>{@code bloom_h} — horizontal separable bloom</li>
 *   <li>{@code bloom_v} — vertical separable bloom</li>
 *   <li>{@code tonemap} — ACES tone map + saturation + vignette</li>
 * </ul>
 */
public final class ShaderManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Shader");

    public static final String SHADER_DIR = "/assets/metallum_shaders/shaders/";

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;

    private static long libraryHandle = 0L;
    private static final Map<String, Long> PIPELINES = new HashMap<>();

    private ShaderManager() {}

    public static synchronized boolean init() {
        if (initialised) return available;
        initialised = true;

        if (!NativeLoader.ensureLoaded()) {
            LOGGER.warn("Native shim not loaded; shaders disabled.");
            return false;
        }
        if (!MetalBridge.isAvailable()) {
            LOGGER.warn("Metallum Metal context not available; shaders disabled.");
            return false;
        }

        long device = MetalBridge.getDeviceHandle();
        if (device <= 0) {
            LOGGER.warn("MTLDevice handle is null; shaders disabled.");
            return false;
        }

        // Concatenate the include header + main source so the compiler
        // sees the shared structs / uniforms in one translation unit.
        // We bundle the vertex shader once, then each fragment pass.
        String source = loadSource("core/fullscreen_vertex.metal")
                + "\n// === composite ===\n" + loadSource("core/composite_fragment.metal")
                + "\n// === bloom_h ===\n"  + loadSource("post/bloom_horizontal_fragment.metal")
                + "\n// === bloom_v ===\n"  + loadSource("post/bloom_vertical_fragment.metal")
                + "\n// === tonemap ===\n"  + loadSource("post/tonemap_fragment.metal");

        libraryHandle = MetalNative.compileLibrary(device, source, "metallum_shaders.metal");
        if (libraryHandle == 0L) {
            LOGGER.error("Failed to compile Metal library — shaders disabled.");
            return false;
        }
        LOGGER.info("Compiled Metallum shader library: handle={}", libraryHandle);

        // Build pipelines. Pixel format 80 = MTLPixelFormatBGRA8Unorm,
        // depth format 55 = MTLPixelFormatDepth32Float.
        int colorFmt = 80;
        int depthFmt = 55;

        PIPELINES.put("composite",
                MetalNative.buildPostPipeline(device, libraryHandle,
                        "fullscreen_vertex", "composite_fragment", colorFmt, depthFmt));
        PIPELINES.put("bloom_h",
                MetalNative.buildPostPipeline(device, libraryHandle,
                        "fullscreen_vertex", "bloom_horizontal_fragment", colorFmt, 0));
        PIPELINES.put("bloom_v",
                MetalNative.buildPostPipeline(device, libraryHandle,
                        "fullscreen_vertex", "bloom_vertical_fragment", colorFmt, 0));
        PIPELINES.put("tonemap",
                MetalNative.buildPostPipeline(device, libraryHandle,
                        "fullscreen_vertex", "tonemap_fragment", colorFmt, 0));

        for (Map.Entry<String, Long> e : PIPELINES.entrySet()) {
            if (e.getValue() == 0L) {
                LOGGER.error("Pipeline '{}' failed to build.", e.getKey());
                available = false;
                return false;
            }
            LOGGER.info("Pipeline '{}' ready: handle={}", e.getKey(), e.getValue());
        }

        available = true;
        LOGGER.info("Metallum shaders initialised.");
        return available;
    }

    public static long getPipeline(String name) {
        Long h = PIPELINES.get(name);
        return h == null ? 0L : h;
    }

    public static boolean isAvailable() {
        return available;
    }

    public static void shutdown() {
        if (!initialised) return;
        long device = MetalBridge.getDeviceHandle();
        for (Long h : PIPELINES.values()) {
            if (h != null && h != 0L) MetalNative.release(h);
        }
        PIPELINES.clear();
        if (libraryHandle != 0L) {
            MetalNative.release(libraryHandle);
            libraryHandle = 0L;
        }
        initialised = false;
        available = false;
    }

    public static void reload() {
        LOGGER.info("Reloading shaders...");
        shutdown();
        ShaderConfig.reload();
        init();
    }

    private static String loadSource(String path) {
        try (InputStream in = MetallumShadersMod.class.getResourceAsStream(SHADER_DIR + path)) {
            if (in == null) {
                LOGGER.error("Missing shader source: {}", path);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to read shader source: {}", path, e);
            return "";
        }
    }
}
