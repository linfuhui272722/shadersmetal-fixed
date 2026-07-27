package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
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
    private static Field cameraFieldCache;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        // 调试日志：确认新代码已加载
        LOGGER.info("=== MetalBridge NEW CODE v2.0 LOADED ===");

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
    // Camera 获取 (核心修复)
    // =====================================================================

    public static Camera getMainCamera() {
        if (!isAvailable()) return null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer == null) return null;

            GameRenderer renderer = mc.gameRenderer;

            if (cameraFieldCache != null) {
                try {
                    return (Camera) cameraFieldCache.get(renderer);
                } catch (IllegalAccessException ignored) {}
            }

            Field[] fields = GameRenderer.class.getDeclaredFields();
            for (Field field : fields) {
                if (Camera.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    cameraFieldCache = field;
                    LOGGER.info("Found Camera field in GameRenderer: {}", field.getName());
                    return (Camera) field.get(renderer);
                }
            }

            LOGGER.warn("Could not find Camera field in GameRenderer by type.");
            return null;

        } catch (Throwable t) {
            LOGGER.warn("Failed to get main camera", t);
            return null;
        }
    }

    // =====================================================================
    // 纹理获取 (增加调试日志)
    // =====================================================================

    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        try {
            RenderTarget target = getRenderTarget();
            if (target == null) {
                LOGGER.warn("[Debug] getRenderTarget returned null");
                return -1L;
            }

            Object tex = getColorTexture(target);
            if (tex == null) {
                LOGGER.warn("[Debug] getColorTexture returned null");
                return -1L;
            }

            long handle = extractHandle(tex);
            // 只在句柄变化时打印，避免刷屏
            if (handle != cachedColorTextureHandle) {
                LOGGER.info("[Debug] Color texture handle updated: {} (0x{})", handle, Long.toHexString(handle));
            }
            
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
        try {
            Method m = RenderTarget.class.getMethod("getColorTexture");
            return m.invoke(target);
        } catch (NoSuchMethodException ignored) {}

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

    private static long extractHandle(Object tex) throws Exception {
        Field nativeHandleField = tex.getClass().getDeclaredField("nativeHandle");
        nativeHandleField.setAccessible(true);
        Object memorySegment = nativeHandleField.get(tex);
        if (memorySegment == null) return -1L;

        Class<?> memorySegmentClass;
        try {
            memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment");
        } catch (ClassNotFoundException e) {
            try {
                memorySegmentClass = Class.forName("jdk.incubator.foreign.MemorySegment");
            } catch (ClassNotFoundException ex) {
                throw new Exception("Cannot find MemorySegment class in JDK", ex);
            }
        }

        Method addressMethod = memorySegmentClass.getMethod("address");
        return (long) addressMethod.invoke(memorySegment);
    }
}
