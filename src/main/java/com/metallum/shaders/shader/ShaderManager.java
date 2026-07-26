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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

        LOGGER.info("=== ShaderManager.init() START ===");

        if (!NativeLoader.ensureLoaded()) {
            LOGGER.warn("Native shim not loaded; shaders disabled.");
            return false;
        }
        LOGGER.info("NativeLoader OK");

        if (!MetalBridge.isAvailable()) {
            LOGGER.warn("Metallum Metal context not available; shaders disabled.");
            return false;
        }
        LOGGER.info("MetalBridge OK");

        long device = MetalBridge.getDeviceHandle();
        if (device <= 0) {
            LOGGER.warn("MTLDevice handle is null; shaders disabled.");
            return false;
        }
        LOGGER.info("MTLDevice handle: 0x{}", Long.toHexString(device));

        // Concatenate the include header + main source so the compiler
        // sees the shared structs / uniforms in one translation unit.
        // We bundle the vertex shader once, then each fragment pass.
        // Use loadSourceWithIncludes to recursively handle #include directives.
        LOGGER.info("Loading shader sources...");
        String source = loadSourceWithIncludes("core/fullscreen_vertex.metal")
                + "\n// === composite ===\n" + loadSourceWithIncludes("core/composite_fragment.metal")
                + "\n// === bloom_h ===\n"  + loadSourceWithIncludes("post/bloom_horizontal_fragment.metal")
                + "\n// === bloom_v ===\n"  + loadSourceWithIncludes("post/bloom_vertical_fragment.metal")
                + "\n// === tonemap ===\n"  + loadSourceWithIncludes("post/tonemap_fragment.metal");
        LOGGER.info("Shader source loaded, total length: {} chars", source.length());

        LOGGER.info("Compiling Metal library...");
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

        // --- Build composite pipeline ---
        LOGGER.info("Building pipeline 'composite'...");
        long compositePipe = MetalNative.buildPostPipeline(device, libraryHandle,
                "fullscreen_vertex", "composite_fragment", colorFmt, depthFmt);
        if (compositePipe == 0L) {
            LOGGER.error("Failed to build pipeline 'composite'");
            available = false;
            return false;
        }
        PIPELINES.put("composite", compositePipe);
        LOGGER.info("Pipeline 'composite' ready: handle={}", compositePipe);

        // --- Build bloom_h pipeline ---
        LOGGER.info("Building pipeline 'bloom_h'...");
        long bloomHPipe = MetalNative.buildPostPipeline(device, libraryHandle,
                "fullscreen_vertex", "bloom_horizontal_fragment", colorFmt, 0);
        if (bloomHPipe == 0L) {
            LOGGER.error("Failed to build pipeline 'bloom_h'");
            available = false;
            return false;
        }
        PIPELINES.put("bloom_h", bloomHPipe);
        LOGGER.info("Pipeline 'bloom_h' ready: handle={}", bloomHPipe);

        // --- Build bloom_v pipeline ---
        LOGGER.info("Building pipeline 'bloom_v'...");
        long bloomVPipe = MetalNative.buildPostPipeline(device, libraryHandle,
                "fullscreen_vertex", "bloom_vertical_fragment", colorFmt, 0);
        if (bloomVPipe == 0L) {
            LOGGER.error("Failed to build pipeline 'bloom_v'");
            available = false;
            return false;
        }
        PIPELINES.put("bloom_v", bloomVPipe);
        LOGGER.info("Pipeline 'bloom_v' ready: handle={}", bloomVPipe);

        // --- Build tonemap pipeline ---
        LOGGER.info("Building pipeline 'tonemap'...");
        long tonemapPipe = MetalNative.buildPostPipeline(device, libraryHandle,
                "fullscreen_vertex", "tonemap_fragment", colorFmt, 0);
        if (tonemapPipe == 0L) {
            LOGGER.error("Failed to build pipeline 'tonemap'");
            available = false;
            return false;
        }
        PIPELINES.put("tonemap", tonemapPipe);
        LOGGER.info("Pipeline 'tonemap' ready: handle={}", tonemapPipe);

        available = true;
        LOGGER.info("All 4 pipelines built successfully!");
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

    /**
     * Loads a shader source file and recursively resolves #include directives.
     * Includes are searched using the path exactly as written in the source.
     * To prevent infinite recursion, a set of already included paths is maintained.
     *
     * @param path the path to the shader file (e.g. "core/fullscreen_vertex.metal")
     * @return the source code with all #include directives replaced by the included file content
     */
    private static String loadSourceWithIncludes(String path) {
        return loadSourceWithIncludes(path, new HashSet<>());
    }

    private static String loadSourceWithIncludes(String path, Set<String> processed) {
        String content = loadSource(path);
        if (content.isEmpty()) {
            return content;
        }
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include \"")) {
                int start = trimmed.indexOf('"') + 1;
                int end = trimmed.lastIndexOf('"');
                if (start > 0 && end > start) {
                    String includeFile = trimmed.substring(start, end);
                    // 直接使用源文件中写的路径（可能已经包含 "include/" 前缀）
                    String includePath = includeFile;
                    // 避免无限递归
                    if (processed.contains(includePath)) {
                        LOGGER.warn("Recursive include detected, skipping: {}", includePath);
                        continue;
                    }
                    processed.add(includePath);
                    String includeContent = loadSourceWithIncludes(includePath, processed);
                    processed.remove(includePath);
                    result.append(includeContent);
                    // 添加一个换行保持格式
                    result.append("\n");
                } else {
                    // 格式错误的 include，保留原行
                    result.append(line).append("\n");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
}
