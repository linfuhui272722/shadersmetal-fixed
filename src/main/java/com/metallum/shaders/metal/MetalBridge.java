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
     * 优先尝试公共方法，再尝试字段，特别处理 MemorySegment
     */
    private static long extractMetalHandle(Object texObj, String textureType) {
        if (texObj == null) return -1L;
        Class<?> clazz = texObj.getClass();
        LOGGER.info("Extracting {} handle from class: {}", textureType, clazz.getName());

        // 1. 尝试公共方法（优先）
        for (Method m : clazz.getMethods()) {
            String name = m.getName().toLowerCase();
            // 优先方法名包含 handle, address, pointer, native
            if (name.contains("handle") || name.contains("address") || name.contains("pointer") || name.contains("native")) {
                if (m.getParameterCount() == 0) {
                    try {
                        m.setAccessible(true); // 如果方法是公共的，但可能依然需要，但 public 不需要
                        Object result = m.invoke(texObj);
                        if (result instanceof Long) {
                            long h = (Long) result;
                            if (h > 0) {
                                LOGGER.info("Extracted {} handle {} via method {}", textureType, h, m.getName());
                                return h;
                            }
                        } else if (result instanceof Number) {
                            long h = ((Number) result).longValue();
                            if (h > 0) {
                                LOGGER.info("Extracted {} handle {} via method {}", textureType, h, m.getName());
                                return h;
                            }
                        } else if (result != null && result.getClass().getName().contains("MemorySegment")) {
                            long h = extractFromMemorySegment(result);
                            if (h > 0) {
                                LOGGER.info("Extracted {} handle {} via MemorySegment from method {}", textureType, h, m.getName());
                                return h;
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Method {} failed: {}", m.getName(), e.getMessage());
                    }
                }
            }
        }

        // 2. 尝试字段（包括私有字段）
        for (Field f : clazz.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(texObj);
                if (val instanceof Long) {
                    long h = (Long) val;
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} from field {}", textureType, h, f.getName());
                        return h;
                    }
                } else if (val instanceof Number) {
                    long h = ((Number) val).longValue();
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} from field {}", textureType, h, f.getName());
                        return h;
                    }
                } else if (val != null && val.getClass().getName().contains("MemorySegment")) {
                    long h = extractFromMemorySegment(val);
                    if (h > 0) {
                        LOGGER.info("Extracted {} handle {} from MemorySegment field {}", textureType, h, f.getName());
                        return h;
                    }
                }
            } catch (Throwable t) {
                // 忽略访问错误（包括模块限制）
                LOGGER.debug("Field {} access error: {}", f.getName(), t.getMessage());
            }
        }

        // 3. 如果所有方法都失败，打印诊断信息
        LOGGER.warn("Could not extract {} handle from {}.", textureType, clazz.getName());
        return -1L;
    }

    /**
     * 从 MemorySegment 对象中提取地址
     */
    private static long extractFromMemorySegment(Object segmentObj) {
        if (segmentObj == null) return -1L;
        try {
            // 尝试调用 address() 方法
            Method addressMethod = segmentObj.getClass().getMethod("address");
            Object result = addressMethod.invoke(segmentObj);
            if (result instanceof Long) {
                long h = (Long) result;
                if (h > 0) return h;
            } else if (result instanceof Number) {
                long h = ((Number) result).longValue();
                if (h > 0) return h;
            }
        } catch (Exception e) {
            LOGGER.debug("MemorySegment.address() failed: {}", e.getMessage());
            // 尝试通过字段
            try {
                Field addrField = segmentObj.getClass().getDeclaredField("address");
                addrField.setAccessible(true);
                Object val = addrField.get(segmentObj);
                if (val instanceof Long) {
                    long h = (Long) val;
                    if (h > 0) return h;
                } else if (val instanceof Number) {
                    long h = ((Number) val).longValue();
                    if (h > 0) return h;
                }
            } catch (Throwable ignored) {}
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
