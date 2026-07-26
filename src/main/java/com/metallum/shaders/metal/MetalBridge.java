package com.metallum.shaders.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reflective bridge to the Metallum mod's Metal context.
 *
 * <p>Metallum (https://github.com/EternityQwQ/MetalUniversal) rewrites
 * Minecraft's OpenGL calls onto Apple Metal. We don't compile against its
 * internals (the jar is shipped as a flat-dir dependency and its API is
 * still in flux), so we look up the entry points we need by reflection.
 *
 * <p>The methods we need are:
 * <ul>
 *   <li>{@code MetallumAPI#getInstance()} — singleton accessor</li>
 *   <li>{@code MetallumAPI#getCurrentDevice()} — returns an
 *       {@code MTLDevice} (or a long handle to one)</li>
 *   <li>{@code MetallumAPI#getCommandQueue()} — returns an
 *       {@code MTLCommandQueue}</li>
 *   <li>{@code MetallumAPI#getCurrentCommandBuffer()} — returns the
 *       in-flight {@code MTLCommandBuffer}</li>
 *   <li>{@code MetallumAPI#getMainColorTexture()} /
 *       {@code getMainDepthTexture()} — the g-buffer attachments</li>
 * </ul>
 *
 * <p>If any of these are missing we log a warning and fall back to a
 * no-op renderer; the mod will not crash the game.
 */
public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/MetalBridge");

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;

    private static Class<?> metallumApiClass;
    private static Method getInstanceMethod;
    private static Method getCurrentDeviceMethod;
    private static Method getCommandQueueMethod;
    private static Method getCurrentCommandBufferMethod;
    private static Method getMainColorTextureMethod;
    private static Method getMainDepthTextureMethod;
    private static Method getMainNormalTextureMethod;
    private static Method submitMethod;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        try {
            // Try a few candidate class names — Metallum's package layout
            // may evolve; we accept any of them.
            String[] candidates = {
                    "com.eternity.metallum.MetallumAPI",
                    "com.eternityq.metallum.MetallumAPI",
                    "io.github.eternityqwq.metallum.MetallumAPI",
                    "com.metallum.api.MetallumAPI"
            };
            for (String name : candidates) {
                try {
                    metallumApiClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            if (metallumApiClass == null) {
                LOGGER.warn("Metallum API class not found on classpath. "
                        + "Shaders will be disabled. Install Metallum from "
                        + "https://github.com/EternityQwQ/MetalUniversal/releases");
                return;
            }

            getInstanceMethod = metallumApiClass.getMethod("getInstance");
            getCurrentDeviceMethod = find(metallumApiClass, "getCurrentDevice", "getDevice");
            getCommandQueueMethod = find(metallumApiClass, "getCommandQueue");
            getCurrentCommandBufferMethod = find(metallumApiClass,
                    "getCurrentCommandBuffer", "getCommandBuffer");
            getMainColorTextureMethod = find(metallumApiClass,
                    "getMainColorTexture", "getColorTexture", "getFramebufferTexture");
            getMainDepthTextureMethod = find(metallumApiClass,
                    "getMainDepthTexture", "getDepthTexture");
            getMainNormalTextureMethod = find(metallumApiClass,
                    "getMainNormalTexture", "getNormalTexture");
            submitMethod = find(metallumApiClass, "submit", "commitCommandBuffer");

            available = true;
            LOGGER.info("Metallum bridge initialised against {}", metallumApiClass.getName());
        } catch (Throwable t) {
            LOGGER.warn("Failed to initialise Metallum bridge", t);
        }
    }

    private static Method find(Class<?> cls, String... names) {
        for (String n : names) {
            try {
                return cls.getMethod(n);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    public static boolean isAvailable() {
        if (!initialised) init();
        return available;
    }

    private static Object api() {
        if (!isAvailable()) return null;
        try {
            return getInstanceMethod.invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("Failed to fetch MetallumAPI instance", t);
            return null;
        }
    }

    /** Returns the raw {@code id<MTLDevice>} pointer as a long, or -1. */
    public static long getCurrentDeviceHandle() {
        Object api = api();
        if (api == null || getCurrentDeviceMethod == null) return -1L;
        try {
            Object dev = getCurrentDeviceMethod.invoke(api);
            if (dev == null) return -1L;
            // Metallum may return either a java.lang.Long, an
            // org.lwjgl.system.Pointer, or a Metal-jni wrapper.
            return toLong(dev);
        } catch (Throwable t) {
            LOGGER.warn("getCurrentDeviceHandle failed", t);
            return -1L;
        }
    }

    /** Alias used by {@link com.metallum.shaders.shader.ShaderManager}. */
    public static long getDeviceHandle() {
        return getCurrentDeviceHandle();
    }

    public static long getCommandQueueHandle() {
        Object api = api();
        if (api == null || getCommandQueueMethod == null) return -1L;
        try {
            return toLong(getCommandQueueMethod.invoke(api));
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static long getCurrentCommandBufferHandle() {
        Object api = api();
        if (api == null || getCurrentCommandBufferMethod == null) return -1L;
        try {
            return toLong(getCurrentCommandBufferMethod.invoke(api));
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** Alias used by {@link com.metallum.shaders.render.ShaderRenderer}. */
    public static long getCommandBufferHandle() {
        return getCurrentCommandBufferHandle();
    }

    public static long getMainColorTextureHandle() {
        Object api = api();
        if (api == null || getMainColorTextureMethod == null) return -1L;
        try {
            return toLong(getMainColorTextureMethod.invoke(api));
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static long getMainDepthTextureHandle() {
        Object api = api();
        if (api == null || getMainDepthTextureMethod == null) return -1L;
        try {
            return toLong(getMainDepthTextureMethod.invoke(api));
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static Optional<Long> getMainNormalTextureHandle() {
        Object api = api();
        if (api == null || getMainNormalTextureMethod == null) return Optional.empty();
        try {
            long h = toLong(getMainNormalTextureMethod.invoke(api));
            return h < 0 ? Optional.empty() : Optional.of(h);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public static void submitCommandBuffer() {
        Object api = api();
        if (api == null || submitMethod == null) return;
        try {
            submitMethod.invoke(api);
        } catch (Throwable t) {
            LOGGER.warn("submitCommandBuffer failed", t);
        }
    }

    private static long toLong(Object o) {
        if (o == null) return -1L;
        if (o instanceof Number n) return n.longValue();
        // Try reflection on common pointer-wrapping types
        try {
            Method m = o.getClass().getMethod("address");
            return (long) m.invoke(o);
        } catch (Throwable ignored) {}
        try {
            Method m = o.getClass().getMethod("getHandle");
            return (long) m.invoke(o);
        } catch (Throwable ignored) {}
        try {
            Method m = o.getClass().getMethod("value");
            return (long) m.invoke(o);
        } catch (Throwable ignored) {}
        return -1L;
    }
}
