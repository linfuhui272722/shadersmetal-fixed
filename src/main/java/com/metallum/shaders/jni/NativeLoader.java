package com.metallum.shaders.jni;

import com.metallum.shaders.MetallumShadersMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the small JNI shim library ({@code libmetallum_shaders.dylib} on
 * macOS) that actually issues Metal {@code MTLRenderCommandEncoder} calls.
 *
 * <p>The shim is intentionally tiny — it only knows how to:
 * <ol>
 *   <li>compile a Metallum-bundled {@code .metal} source into a
 *       {@code id&lt;MTLLibrary&gt;} via {@code MTLDevice.newLibraryWithSource},</li>
 *   <li>build a {@code MTLRenderPipelineState} for a vertex+fragment pair,</li>
 *   <li>issue a fullscreen triangle draw with that pipeline bound to the
 *       g-buffer color/depth textures that Metallum exposes.</li>
 * </ol>
 *
 * <p>If the native library cannot be loaded (e.g. running on non-macOS or
 * without Metallum), all native methods become no-ops and the mod logs a
 * warning instead of crashing.
 */
public final class NativeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Native");

    private static volatile boolean loaded = false;
    private static volatile boolean attempted = false;

    private NativeLoader() {}

    public static synchronized boolean ensureLoaded() {
        if (attempted) return loaded;
        attempted = true;

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("mac")) {
            LOGGER.warn("Metallum shaders require macOS (Metal). Detected: {}. "
                    + "Shaders will be disabled.", osName);
            return false;
        }

        // Try system load first (in case the user installed the lib globally)
        try {
            System.loadLibrary("metallum_shaders");
            loaded = true;
            LOGGER.info("Loaded libmetallum_shaders from java.library.path");
            return true;
        } catch (UnsatisfiedLinkError ignored) {}

        // Otherwise extract the bundled dylib from the jar
        String arch = System.getProperty("os.arch", "");
        String archDir = arch.contains("aarch64") || arch.contains("arm64")
                ? "arm64" : "x86_64";
        String resourcePath = "/native/macos-" + archDir + "/libmetallum_shaders.dylib";

        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.warn("Bundled native library not found at {} — shaders disabled. "
                        + "Build the native component with `./gradlew buildNative`.", resourcePath);
                return false;
            }
            Path tmp = Files.createTempFile("libmetallum_shaders", ".dylib");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
            LOGGER.info("Extracted and loaded bundled libmetallum_shaders from {}", tmp);
            return true;
        } catch (IOException | UnsatisfiedLinkError e) {
            LOGGER.warn("Failed to load native shim", e);
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }
}

