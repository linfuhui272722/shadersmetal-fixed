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
            LOGGER.info("MetalBridge initialised");
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
    // 纹理获取
    // =====================================================================

    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        try {
            RenderTarget target = getRenderTarget();
            if (target == null) return -1L;

            Object tex = getColorTexture(target);
            if (tex == null) return -1L;

            long handle = extractHandle(tex);
            if (handle > 0x1000) {
                cachedColorTextureHandle = handle;
                return handle;
            }
            return -1L;
        } catch (Throwable t) {
            LOGGER.warn("Failed to get color texture", t);
            return -1L;
        }
    }

    public static long getMainDepthTextureHandle() {
        if (!isAvailable()) return -1L;
        try {
            RenderTarget target = getRenderTarget();
            if (target == null) return -1L;

            Object tex = getDepthTexture(target);
            if (tex == null) return -1L;

            long handle = extractHandle(tex);
            if (handle > 0x1000) {
                cachedDepthTextureHandle = handle;
                return handle;
            }
            return -1L;
        } catch (Throwable t) {
            LOGGER.warn("Failed to get depth texture", t);
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

    public static void submitCommandBuffer() {}

    // =====================================================================
    // 核心方法
    // =====================================================================

    private static RenderTarget getRenderTarget() throws Exception {
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        if (gameRenderer == null) return null;

        if (renderTargetField == null) {
            try {
                renderTargetField = GameRenderer.class.getDeclaredField("mainRenderTarget");
                renderTargetField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                renderTargetField = GameRenderer.class.getDeclaredField("renderTarget");
                renderTargetField.setAccessible(true);
            }
        }
        return (RenderTarget) renderTargetField.get(gameRenderer);
    }

    private static Object getColorTexture(RenderTarget target) throws Exception {
        // 先尝试方法
        try {
            Method m = RenderTarget.class.getMethod("getColorTexture");
            return m.invoke(target);
        } catch (NoSuchMethodException ignored) {}

        // 再尝试字段
        if (colorTextureField == null) {
            colorTextureField = RenderTarget.class.getDeclaredField("colorTexture");
            colorTextureField.setAccessible(true);
        }
        return colorTextureField.get(target);
    }

    private static Object getDepthTexture(RenderTarget target) throws Exception {
        try {
            Method m = RenderTarget.class.getMethod("getDepthTexture");
            return m.invoke(target);
        } catch (NoSuchMethodException ignored) {}

        if (depthTextureField == null) {
            depthTextureField = RenderTarget.class.getDeclaredField("depthTexture");
            depthTextureField.setAccessible(true);
        }
        return depthTextureField.get(target);
    }

    /**
     * 从 MetalGpuTexture 提取 nativeHandle 地址
     * 修复：使用公共 API MemorySegment 接口来避免 IllegalAccessException
     */
    private static long extractHandle(Object tex) throws Exception {
        // 1. 获取 nativeHandle 字段
        Field nativeHandleField = tex.getClass().getDeclaredField("nativeHandle");
        nativeHandleField.setAccessible(true);
        Object memorySegment = nativeHandleField.get(tex);
        if (memorySegment == null) return -1L;

        // 2. 关键修复：
        // 不要使用 memorySegment.getClass() 获取方法，因为那会返回受保护的内部类。
        // 显式使用公共接口 java.lang.foreign.MemorySegment (Java 22+ 标准 API)
        Class<?> memorySegmentClass;
        try {
            // Java 22+ 标准路径
            memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment");
        } catch (ClassNotFoundException e) {
            // 备选：对于较早的孵化器版本 (Java 19-21)，虽然你用的是 Java 25，但保留备选逻辑更稳健
            try {
                memorySegmentClass = Class.forName("jdk.incubator.foreign.MemorySegment");
            } catch (ClassNotFoundException ex) {
                throw new Exception("Cannot find MemorySegment class in JDK", ex);
            }
        }

        // 3. 在公共接口上获取 address() 方法
        Method addressMethod = memorySegmentClass.getMethod("address");
        
        // 4. 调用获取地址
        return (long) addressMethod.invoke(memorySegment);
    }
}
