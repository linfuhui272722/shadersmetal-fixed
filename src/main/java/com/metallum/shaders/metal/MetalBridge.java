package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/MetalBridge");

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;
    private static long deviceHandle = -1L;

    // 缓存纹理句柄
    private static long cachedColorTextureHandle = -1L;
    private static long cachedDepthTextureHandle = -1L;

    // 反射缓存
    private static Field renderTargetField;
    private static Field colorTextureHandleField;
    private static Field depthTextureHandleField;
    private static Method getColorTextureMethod;
    private static Method getDepthTextureMethod;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        try {
            if (!NativeLoader.ensureLoaded()) {
                LOGGER.warn("Native shim not loaded; Metal bridge unavailable.");
                return;
            }

            deviceHandle = MetalNative.getDefaultDevice();
            if (deviceHandle <= 0) {
                LOGGER.warn("Failed to obtain MTLDevice handle from MetalNative.");
                return;
            }

            available = true;
            LOGGER.info("MetalBridge initialised (device handle: 0x{})", Long.toHexString(deviceHandle));
        } catch (Throwable t) {
            LOGGER.warn("Failed to initialise MetalBridge", t);
        }
    }

    public static boolean isAvailable() {
        if (!initialised) init();
        return available;
    }

    public static long getDeviceHandle() {
        if (!isAvailable()) return -1L;
        return deviceHandle;
    }

    public static long getCurrentDeviceHandle() {
        return getDeviceHandle();
    }

    // =====================================================================
    // 纹理获取 - 直接获取 Metal 纹理句柄
    // =====================================================================

    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;

        try {
            long handle = getRenderTargetColorTextureHandle();
            if (handle <= 0) return -1L;
            
            if (handle == cachedColorTextureHandle) {
                return cachedColorTextureHandle;
            }
            cachedColorTextureHandle = handle;
            return handle;

        } catch (Throwable t) {
            LOGGER.warn("Failed to get main color texture", t);
            return -1L;
        }
    }

    public static long getMainDepthTextureHandle() {
        if (!isAvailable()) return -1L;

        try {
            long handle = getRenderTargetDepthTextureHandle();
            if (handle <= 0) return -1L;

            if (handle == cachedDepthTextureHandle) {
                return cachedDepthTextureHandle;
            }
            cachedDepthTextureHandle = handle;
            return handle;

        } catch (Throwable t) {
            LOGGER.warn("Failed to get main depth texture", t);
            return -1L;
        }
    }

    public static Optional<Long> getMainNormalTextureHandle() {
        return Optional.empty();
    }

    // =====================================================================
    // 命令缓冲区
    // =====================================================================

    public static long getCommandQueueHandle() {
        if (!isAvailable()) return -1L;
        return MetalNative.getDefaultCommandQueue();
    }

    public static long getCurrentCommandBufferHandle() {
        if (!isAvailable()) return -1L;
        return MetalNative.createCommandBuffer();
    }

    public static long getCommandBufferHandle() {
        return getCurrentCommandBufferHandle();
    }

    public static void submitCommandBuffer() {
        // 可扩展
    }

    // =====================================================================
    // 反射辅助方法：获取 Metal 纹理句柄
    // =====================================================================

    private static long getRenderTargetColorTextureHandle() throws Exception {
        RenderTarget target = getRenderTarget();
        if (target == null) return -1L;

        // 1. 尝试通过 getColorTexture() 方法（如果返回 MetalGpuTexture）
        if (getColorTextureMethod == null) {
            try {
                getColorTextureMethod = RenderTarget.class.getMethod("getColorTexture");
            } catch (NoSuchMethodException ignored) {}
        }
        if (getColorTextureMethod != null) {
            try {
                Object tex = getColorTextureMethod.invoke(target);
                if (tex != null) {
                    long handle = extractMetalHandle(tex);
                    if (handle > 0) return handle;
                }
            } catch (Exception e) {
                LOGGER.debug("getColorTexture() failed", e);
            }
        }

        // 2. 备选：通过字段 colorTexture（或其他名称）
        if (colorTextureHandleField == null) {
            String[] fieldNames = {"colorTexture", "colorTex", "texture", "mainColorTexture"};
            for (String name : fieldNames) {
                try {
                    colorTextureHandleField = RenderTarget.class.getDeclaredField(name);
                    colorTextureHandleField.setAccessible(true);
                    LOGGER.info("Found color texture field: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (colorTextureHandleField == null) {
                LOGGER.error("Cannot find color texture field/method");
                return -1L;
            }
        }
        Object tex = colorTextureHandleField.get(target);
        if (tex == null) return -1L;
        return extractMetalHandle(tex);
    }

    private static long getRenderTargetDepthTextureHandle() throws Exception {
        RenderTarget target = getRenderTarget();
        if (target == null) return -1L;

        // 1. 尝试通过 getDepthTexture() 方法
        if (getDepthTextureMethod == null) {
            try {
                getDepthTextureMethod = RenderTarget.class.getMethod("getDepthTexture");
            } catch (NoSuchMethodException ignored) {}
        }
        if (getDepthTextureMethod != null) {
            try {
                Object tex = getDepthTextureMethod.invoke(target);
                if (tex != null) {
                    long handle = extractMetalHandle(tex);
                    if (handle > 0) return handle;
                }
            } catch (Exception e) {
                LOGGER.debug("getDepthTexture() failed", e);
            }
        }

        // 2. 备选：通过字段 depthTexture
        if (depthTextureHandleField == null) {
            String[] fieldNames = {"depthTexture", "depthTex", "depthBuffer"};
            for (String name : fieldNames) {
                try {
                    depthTextureHandleField = RenderTarget.class.getDeclaredField(name);
                    depthTextureHandleField.setAccessible(true);
                    LOGGER.info("Found depth texture field: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (depthTextureHandleField == null) {
                LOGGER.warn("Cannot find depth texture field/method");
                return -1L;
            }
        }
        Object tex = depthTextureHandleField.get(target);
        if (tex == null) return -1L;
        return extractMetalHandle(tex);
    }

    /**
     * 从 MetalGpuTexture 对象中提取 Metal 纹理句柄（long）
     */
    private static long extractMetalHandle(Object texObj) throws Exception {
        // 尝试获取 handle 字段（long）
        Class<?> clazz = texObj.getClass();
        // 如果类名包含 "MetalGpuTexture"，则直接提取
        if (clazz.getSimpleName().contains("MetalGpuTexture") || clazz.getName().contains("MetalGpuTexture")) {
            try {
                Field handleField = clazz.getDeclaredField("handle");
                handleField.setAccessible(true);
                return (long) handleField.get(texObj);
            } catch (NoSuchFieldException e) {
                // 尝试其他字段名
                try {
                    Field ptrField = clazz.getDeclaredField("pointer");
                    ptrField.setAccessible(true);
                    return (long) ptrField.get(texObj);
                } catch (NoSuchFieldException ignored) {}
                try {
                    Field nativePtrField = clazz.getDeclaredField("nativePtr");
                    nativePtrField.setAccessible(true);
                    return (long) nativePtrField.get(texObj);
                } catch (NoSuchFieldException ignored) {}
            }
        }
        // 如果直接是 Long 类型，则直接返回
        if (texObj instanceof Long) {
            return (Long) texObj;
        }
        if (texObj instanceof Number) {
            return ((Number) texObj).longValue();
        }
        // 尝试通过 toString 或其他方式（不推荐）
        LOGGER.warn("Unable to extract handle from object of type {}", texObj.getClass().getName());
        return -1L;
    }

    private static RenderTarget getRenderTarget() throws Exception {
        // 1.21 版本：从 GameRenderer 获取 mainRenderTarget
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        if (gameRenderer != null) {
            // 尝试直接访问字段
            if (renderTargetField == null) {
                try {
                    renderTargetField = GameRenderer.class.getDeclaredField("mainRenderTarget");
                    renderTargetField.setAccessible(true);
                    LOGGER.info("Found mainRenderTarget field in GameRenderer");
                } catch (NoSuchFieldException ignored) {
                    try {
                        renderTargetField = GameRenderer.class.getDeclaredField("renderTarget");
                        renderTargetField.setAccessible(true);
                        LOGGER.info("Found renderTarget field in GameRenderer");
                    } catch (NoSuchFieldException ignored2) {
                        LOGGER.error("Cannot find RenderTarget field in GameRenderer");
                    }
                }
            }
            if (renderTargetField != null) {
                Object target = renderTargetField.get(gameRenderer);
                if (target instanceof RenderTarget) {
                    return (RenderTarget) target;
                }
            }
        }

        LOGGER.error("Cannot find RenderTarget");
        return null;
    }
}
