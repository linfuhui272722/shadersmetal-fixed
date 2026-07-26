package com.metallum.shaders.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the native {@code libmetallum_shaders.dylib} from the JAR's
 * native/ios-arm64/ folder, extracts it to a temporary location, and
 * loads it with {@link System#load(String)}.
 */
public final class NativeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/NativeLoader");
    private static volatile boolean loaded = false;

    private NativeLoader() {}

    /**
     * Ensure the native library is loaded. Safe to call multiple times.
     * @return true if the library was successfully loaded, false otherwise.
     */
    public static synchronized boolean ensureLoaded() {
        if (loaded) return true;
        try {
            // Determine the library name for the current platform.
            // iOS arm64 uses "ios-arm64" subfolder.
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            String libName = "libmetallum_shaders.dylib";
            String platformPath;

            // Detect iOS environment (TrollStore usually has 'iPhone' or 'iPad' in os.name)
            if (os.contains("ios") || os.contains("iphone") || os.contains("ipad") || os.contains("darwin")) {
                if (arch.contains("aarch64") || arch.contains("arm64")) {
                    platformPath = "native/ios-arm64/" + libName;
                } else {
                    LOGGER.warn("Unsupported iOS architecture: {}", arch);
                    return false;
                }
            } else {
                // Fallback to "native/osx-arm64" for Mac development (if needed)
                platformPath = "native/osx-arm64/" + libName;
            }

            // Extract the library from the JAR
            Path tempDir = Files.createTempDirectory("metallum_");
            Path targetPath = tempDir.resolve(libName);
            try (InputStream in = NativeLoader.class.getResourceAsStream("/" + platformPath)) {
                if (in == null) {
                    LOGGER.error("Native library not found in JAR: /{}", platformPath);
                    return false;
                }
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Set executable permission (on iOS, not strictly needed but safe)
            targetPath.toFile().setExecutable(true);

            // Load the library
            System.load(targetPath.toAbsolutePath().toString());
            loaded = true;
            LOGGER.info("Loaded native library from {}", targetPath);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to load native library", t);
            return false;
        }
    }
}
