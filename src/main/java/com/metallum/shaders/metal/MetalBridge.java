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
    private static Field colorTextureField;
    private static Field depthTextureField;
    private static Method getColorTextureMethod;
    private static Method getDepthTextureMethod;

    // 诊断标志
    private static boolean colorTextureDiagnosed = false;
    private static boolean depthTextureDiagnosed = false;

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

        // 1. 尝试通过 getColorTexture() 方法
        if (getColorTextureMethod == null) {
            try {
                getColorTextureMethod = RenderTarget.class.getMethod("getColorTexture");
            } catch (NoSuchMethodException ignored) {}
        }
        if (getColorTextureMethod != null) {
            try {
                Object tex = getColorTextureMethod.invoke(target);
                if (tex != null) {
                    long handle = extractMetalHandle(tex, "Color");
                    if (handle > 0) return handle;
                }
            } catch (Exception e) {
                LOGGER.debug("getColorTexture() failed", e);
            }
        }

        // 2. 备选：通过字段
        if (colorTextureField == null) {
            String[] fieldNames = {"colorTexture", "colorTex", "texture", "mainColorTexture", "colorBuffer"};
            for (String name : fieldNames) {
                try {
                    colorTextureField = RenderTarget.class.getDeclaredField(name);
                    colorTextureField.setAccessible(true);
                    LOGGER.info("Found color texture field: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (colorTextureField == null) {
                // 诊断：打印所有字段
                if (!colorTextureDiagnosed) {
                    colorTextureDiagnosed = true;
                    LOGGER.warn("Cannot find color texture field in RenderTarget, available fields:");
                    for (Field f : RenderTarget.class.getDeclaredFields()) {
                        LOGGER.warn("  {} : {}", f.getName(), f.getType().getName());
                    }
                }
                return -1L;
            }
        }
        Object tex = colorTextureField.get(target);
        if (tex == null) return -1L;
        return extractMetalHandle(tex, "Color");
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
                    long handle = extractMetalHandle(tex, "Depth");
                    if (handle > 0) return handle;
                }
            } catch (Exception e) {
                LOGGER.debug("getDepthTexture() failed", e);
            }
        }

        // 2. 备选：通过字段
        if (depthTextureField == null) {
            String[] fieldNames = {"depthTexture", "depthTex", "depthBuffer"};
            for (String name : fieldNames) {
                try {
                    depthTextureField = RenderTarget.class.getDeclaredField(name);
                    depthTextureField.setAccessible(true);
                    LOGGER.info("Found depth texture field: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (depthTextureField == null) {
                if (!depthTextureDiagnosed) {
                    depthTextureDiagnosed = true;
                    LOGGER.warn("Cannot find depth texture field in RenderTarget");
                }
                return -1L;
            }
        }
        Object tex = depthTextureField.get(target);
        if (tex == null) return -1L;
        return extractMetalHandle(tex, "Depth");
    }

    /**
     * 从 MetalGpuTexture 对象中提取 Metal 纹理句柄（long）
     * 支持 MemorySegment 类型的 nativeHandle 字段
     */
    private static long extractMetalHandle(Object texObj, String textureType) throws Exception {
        if (texObj == null) return -1L;
        Class<?> clazz = texObj.getClass();
        LOGGER.info("Extracting {} handle from class: {}", textureType, clazz.getName());

        // 如果直接是 Long 或 Number
        if (texObj instanceof Long) return (Long) texObj;
        if (texObj instanceof Number) return ((Number) texObj).longValue();

        // 检查是否为 MemorySegment，尝试调用 address() 或获取 address 字段
        if (clazz.getName().contains("MemorySegment")) {
            try {
                // 尝试调用 address() 方法
                Method addressMethod = clazz.getMethod("address");
                Object addr = addressMethod.invoke(texObj);
                if (addr instanceof Long) {
                    long h = (Long) addr;
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} via MemorySegment.address()", textureType, h);
                        return h;
                    }
                }
            } catch (NoSuchMethodException e) {
                // 如果 address() 方法不存在，尝试获取 address 字段
                try {
                    Field addressField = clazz.getDeclaredField("address");
                    addressField.setAccessible(true);
                    Object addr = addressField.get(texObj);
                    if (addr instanceof Long) {
                        long h = (Long) addr;
                        if (h > 0) {
                            LOGGER.info("Extracted {} handle {} via MemorySegment.address field", textureType, h);
                            return h;
                        }
                    }
                } catch (NoSuchFieldException ignored) {}
            }
        }

        // 尝试常见字段名
        String[] fieldNames = {"handle", "textureHandle", "metalTextureHandle", "nativeHandle", "pointer", "ptr", "address", "texture"};
        for (String name : fieldNames) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(texObj);
                if (value instanceof Long) {
                    long h = (Long) value;
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} from field {}", textureType, h, name);
                        return h;
                    }
                } else if (value instanceof Number) {
                    long h = ((Number) value).longValue();
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} from field {}", textureType, h, name);
                        return h;
                    }
                } else if (value != null && value.getClass().getName().contains("MemorySegment")) {
                    // 递归处理 MemorySegment
                    long h = extractMetalHandle(value, textureType + "(nested)");
                    if (h > 0) return h;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                LOGGER.warn("Error accessing field {}: {}", name, e.getMessage());
            }
        }

        // 如果没有找到，打印所有字段帮助诊断（但只打印一次）
        LOGGER.warn("Could not extract {} handle from {}. Fields:", textureType, clazz.getName());
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object val = f.get(texObj);
                LOGGER.warn("  {} = {} (type {})", f.getName(), val, val != null ? val.getClass().getSimpleName() : "null");
            } catch (Exception e) {
                LOGGER.warn("  {} : access error", f.getName());
            }
        }

        return -1L;
    }

    private static RenderTarget getRenderTarget() throws Exception {
        // 1.21 版本：从 GameRenderer 获取 mainRenderTarget
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        if (gameRenderer != null) {
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
