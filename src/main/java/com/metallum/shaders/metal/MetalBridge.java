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
    private static boolean handleExtractDiagnosed = false;

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
            LOGGER.info("getMainColorTextureHandle returned 0x{}", Long.toHexString(handle));
            if (handle <= 0x1000) {
                LOGGER.debug("Color texture handle {} is invalid (<= 0x1000), skipping", handle);
                return -1L;
            }
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
            LOGGER.info("getMainDepthTextureHandle returned 0x{}", Long.toHexString(handle));
            if (handle <= 0x1000) {
                LOGGER.debug("Depth texture handle {} is invalid (<= 0x1000), skipping", handle);
                return -1L;
            }

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
                    long handle = extractMetalHandle(tex);
                    if (handle > 0x1000) return handle;
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
                LOGGER.warn("Cannot find color texture field in RenderTarget");
                return -1L;
            }
        }
        Object tex = colorTextureField.get(target);
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
                    if (handle > 0x1000) return handle;
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
                LOGGER.warn("Cannot find depth texture field in RenderTarget");
                return -1L;
            }
        }
        Object tex = depthTextureField.get(target);
        if (tex == null) return -1L;
        return extractMetalHandle(tex);
    }

    /**
     * 从 MetalGpuTexture 对象中提取 Metal 纹理句柄（long）
     * 优先使用 nativeHandle 字段（MemorySegment），并调用 address() 方法
     */
    private static long extractMetalHandle(Object texObj) {
        if (texObj == null) return -1L;
        Class<?> clazz = texObj.getClass();

        // 1. 优先直接访问 nativeHandle 字段
        try {
            Field nativeHandleField = clazz.getDeclaredField("nativeHandle");
            nativeHandleField.setAccessible(true);
            Object nativeHandle = nativeHandleField.get(texObj);
            if (nativeHandle != null) {
                long addr = extractMemorySegmentAddress(nativeHandle);
                if (addr > 0x1000) {
                    LOGGER.info("Extracted handle 0x{} from nativeHandle field", Long.toHexString(addr));
                    return addr;
                } else {
                    LOGGER.warn("Extracted address {} from nativeHandle is not valid", addr);
                }
            }
        } catch (NoSuchFieldException e) {
            LOGGER.warn("No nativeHandle field found in {}", clazz.getName());
        } catch (Throwable t) {
            LOGGER.warn("Failed to access nativeHandle: {}", t.getMessage());
        }

        // 2. 尝试调用可能返回 MemorySegment 或 Long 的方法
        for (Method m : clazz.getMethods()) {
            String name = m.getName().toLowerCase();
            if ((name.contains("handle") || name.contains("address") || name.contains("native")) && m.getParameterCount() == 0) {
                try {
                    Object result = m.invoke(texObj);
                    if (result != null) {
                        if (result.getClass().getName().contains("MemorySegment")) {
                            long addr = extractMemorySegmentAddress(result);
                            if (addr > 0x1000) {
                                LOGGER.info("Extracted handle 0x{} via method {}", Long.toHexString(addr), m.getName());
                                return addr;
                            }
                        } else if (result instanceof Long || result instanceof Number) {
                            long val = ((Number) result).longValue();
                            if (val > 0x1000) {
                                LOGGER.info("Extracted handle 0x{} via method {}", Long.toHexString(val), m.getName());
                                return val;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 3. 遍历所有字段（跳过 views, device, mtlPixelFormat 等）
        for (Field f : clazz.getDeclaredFields()) {
            String name = f.getName();
            if ("views".equals(name) || "device".equals(name) || "mtlPixelFormat".equals(name)) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object val = f.get(texObj);
                if (val != null) {
                    if (val.getClass().getName().contains("MemorySegment")) {
                        long addr = extractMemorySegmentAddress(val);
                        if (addr > 0x1000) {
                            LOGGER.info("Extracted handle 0x{} from field {}", Long.toHexString(addr), name);
                            return addr;
                        }
                    } else if (val instanceof Long || val instanceof Number) {
                        long num = ((Number) val).longValue();
                        if (num > 0x1000) {
                            LOGGER.info("Extracted handle 0x{} from field {}", Long.toHexString(num), name);
                            return num;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 4. 如果所有尝试都失败，打印诊断信息（仅一次）
        if (!handleExtractDiagnosed) {
            handleExtractDiagnosed = true;
            LOGGER.warn("Failed to extract a valid Metal handle from {}", clazz.getName());
            LOGGER.warn("Fields of {}:", clazz.getName());
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(texObj);
                    LOGGER.warn("  {} = {} (type {})", f.getName(), val, val != null ? val.getClass().getSimpleName() : "null");
                } catch (Throwable t) {
                    LOGGER.warn("  {} : access error", f.getName());
                }
            }
        }

        return -1L;
    }

    /**
     * 从 MemorySegment 对象中提取地址（使用公共方法 address()）
     */
    private static long extractMemorySegmentAddress(Object segmentObj) {
        if (segmentObj == null) return -1L;
        try {
            Method addressMethod = segmentObj.getClass().getMethod("address");
            Object result = addressMethod.invoke(segmentObj);
            if (result instanceof Long) {
                long addr = (Long) result;
                if (addr > 0) {
                    LOGGER.info("MemorySegment address via method = 0x{}", Long.toHexString(addr));
                    return addr;
                }
            } else if (result instanceof Number) {
                long addr = ((Number) result).longValue();
                if (addr > 0) {
                    LOGGER.info("MemorySegment address via method = 0x{}", Long.toHexString(addr));
                    return addr;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to get address from MemorySegment via method: {}", t.getMessage());
            // 尝试直接访问 address 字段
            try {
                Field addrField = segmentObj.getClass().getDeclaredField("address");
                addrField.setAccessible(true);
                Object val = addrField.get(segmentObj);
                if (val instanceof Long) {
                    long addr = (Long) val;
                    if (addr > 0) {
                        LOGGER.info("MemorySegment address via field = 0x{}", Long.toHexString(addr));
                        return addr;
                    }
                } else if (val instanceof Number) {
                    long addr = ((Number) val).longValue();
                    if (addr > 0) {
                        LOGGER.info("MemorySegment address via field = 0x{}", Long.toHexString(addr));
                        return addr;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return -1L;
    }

    private static RenderTarget getRenderTarget() throws Exception {
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
