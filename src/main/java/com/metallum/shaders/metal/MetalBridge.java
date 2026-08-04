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

    // 缓存纹理句柄和上次验证时间
    private static long cachedColorTextureHandle = -1L;
    private static long cachedDepthTextureHandle = -1L;
    private static long lastTextureValidationTime = 0;
    private static final long TEXTURE_CACHE_VALIDITY_MS = 100; // 100ms缓存有效期

    // 反射缓存
    private static Field renderTargetField;
    private static Field colorTextureField;
    private static Field depthTextureField;
    private static Field cameraFieldCache;

    // 错误计数器
    private static int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        LOGGER.info("=== MetalBridge v2.1 LOADED ===");

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
            LOGGER.info("MetalBridge initialised with device: 0x{}", Long.toHexString(deviceHandle));
        } catch (Throwable t) {
            LOGGER.warn("Failed to initialise MetalBridge", t);
        }
    }

    public static boolean isAvailable() {
        if (!initialised) init();
        return available && deviceHandle > 0;
    }

    public static long getDeviceHandle() {
        if (!isAvailable()) return -1L;
        return deviceHandle;
    }

    public static long getCurrentDeviceHandle() {
        return getDeviceHandle();
    }

    // =====================================================================
    // Camera 获取
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
    // 纹理获取 - 改进版本，带缓存和验证
    // =====================================================================

    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        
        // 缓存检查：避免频繁反射调用
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTextureValidationTime < TEXTURE_CACHE_VALIDITY_MS 
            && cachedColorTextureHandle > 0) {
            return cachedColorTextureHandle;
        }

        try {
            RenderTarget target = getRenderTarget();
            if (target == null) {
                handleError("getRenderTarget returned null");
                return -1L;
            }

            Object tex = getColorTexture(target);
            if (tex == null) {
                handleError("getColorTexture returned null");
                return -1L;
            }

            long handle = extractHandle(tex);
            
            // 验证句柄有效性
            if (!isValidHandle(handle)) {
                handleError("Invalid color texture handle: " + handle);
                return -1L;
            }
            
            // 更新缓存
            cachedColorTextureHandle = handle;
            lastTextureValidationTime = currentTime;
            consecutiveErrors = 0; // 重置错误计数
            
            return handle;
            
        } catch (Throwable t) {
            handleError("Exception getting color texture: " + t.getMessage());
            return -1L;
        }
    }

    public static long getMainDepthTextureHandle() {
        if (!isAvailable()) return -1L;
        
        // 缓存检查
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTextureValidationTime < TEXTURE_CACHE_VALIDITY_MS 
            && cachedDepthTextureHandle > 0) {
            return cachedDepthTextureHandle;
        }

        try {
            RenderTarget target = getRenderTarget();
            if (target == null) return -1L;

            Object tex = getDepthTexture(target);
            if (tex == null) return -1L;

            long handle = extractHandle(tex);
            
            if (!isValidHandle(handle)) {
                return -1L;
            }
            
            cachedDepthTextureHandle = handle;
            lastTextureValidationTime = currentTime;
            
            return handle;
            
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
    // 辅助方法
    // =====================================================================

    private static boolean isValidHandle(long handle) {
        // 有效的句柄应该大于一定值，并且是合理的内存地址
        return handle > 0x1000 && handle < Long.MAX_VALUE;
    }

    private static void handleError(String message) {
        consecutiveErrors++;
        if (consecutiveErrors <= 3) {
            LOGGER.warn("[MetalBridge] {}", message);
        }
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            LOGGER.error("[MetalBridge] Too many consecutive errors, clearing texture cache");
            cachedColorTextureHandle = -1L;
            cachedDepthTextureHandle = -1L;
            consecutiveErrors = 0;
        }
    }

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
