package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Optional;

public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/MetalBridge");

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;
    private static long deviceHandle = -1L;

    // 缓存纹理句柄
    private static long cachedColorTextureHandle = -1L;
    private static long cachedDepthTextureHandle = -1L;
    private static int lastColorTextureId = -1;
    private static int lastDepthTextureId = -1;

    // 反射缓存
    private static Field renderTargetField;
    private static Field colorTextureIdField;
    private static Field depthTextureIdField;

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
    // 纹理获取 - 通过反射从 Minecraft 的 RenderTarget 中获取
    // =====================================================================

    /**
     * 获取主颜色纹理句柄
     * 对应 Yarn: MinecraftClient.getFramebuffer() -> Mojang: Minecraft.mainRenderTarget
     */
    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        
        try {
            RenderTarget renderTarget = getRenderTarget();
            if (renderTarget == null) return -1L;
            
            int textureId = getRenderTargetColorTextureId(renderTarget);
            if (textureId <= 0) return -1L;
            
            if (textureId == lastColorTextureId && cachedColorTextureHandle != -1L) {
                return cachedColorTextureHandle;
            }
            lastColorTextureId = textureId;
            
            cachedColorTextureHandle = MetalNative.getMetalTextureFromGLTexture(textureId);
            return cachedColorTextureHandle;
            
        } catch (Throwable t) {
            LOGGER.warn("Failed to get main color texture", t);
            return -1L;
        }
    }

    public static long getMainDepthTextureHandle() {
        if (!isAvailable()) return -1L;
        
        try {
            RenderTarget renderTarget = getRenderTarget();
            if (renderTarget == null) return -1L;
            
            int textureId = getRenderTargetDepthTextureId(renderTarget);
            if (textureId <= 0) return -1L;
            
            if (textureId == lastDepthTextureId && cachedDepthTextureHandle != -1L) {
                return cachedDepthTextureHandle;
            }
            lastDepthTextureId = textureId;
            
            cachedDepthTextureHandle = MetalNative.getMetalTextureFromGLTexture(textureId);
            return cachedDepthTextureHandle;
            
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
    // 反射辅助方法
    // =====================================================================

    private static RenderTarget getRenderTarget() throws Exception {
        if (renderTargetField == null) {
            // 尝试多个可能的字段名
            String[] names = {"mainRenderTarget", "framebuffer", "frameBuffer", "fbo"};
            for (String name : names) {
                try {
                    renderTargetField = Minecraft.class.getDeclaredField(name);
                    renderTargetField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (renderTargetField == null) {
                LOGGER.warn("Cannot find RenderTarget field in Minecraft");
                return null;
            }
        }
        return (RenderTarget) renderTargetField.get(Minecraft.getInstance());
    }

    private static int getRenderTargetColorTextureId(RenderTarget target) throws Exception {
        if (colorTextureIdField == null) {
            String[] names = {"colorTextureId", "frameBufferId", "fbo"};
            for (String name : names) {
                try {
                    colorTextureIdField = RenderTarget.class.getDeclaredField(name);
                    colorTextureIdField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (colorTextureIdField == null) {
                // 尝试通过 getColorTextureId() 方法获取
                try {
                    java.lang.reflect.Method m = RenderTarget.class.getMethod("getColorTextureId");
                    return (int) m.invoke(target);
                } catch (NoSuchMethodException e) {
                    LOGGER.warn("Cannot find color texture field/method in RenderTarget");
                    return -1;
                }
            }
        }
        return (int) colorTextureIdField.get(target);
    }

    private static int getRenderTargetDepthTextureId(RenderTarget target) throws Exception {
        if (depthTextureIdField == null) {
            String[] names = {"depthTextureId", "depthBufferId"};
            for (String name : names) {
                try {
                    depthTextureIdField = RenderTarget.class.getDeclaredField(name);
                    depthTextureIdField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (depthTextureIdField == null) {
                return -1;
            }
        }
        return (int) depthTextureIdField.get(target);
    }
}
