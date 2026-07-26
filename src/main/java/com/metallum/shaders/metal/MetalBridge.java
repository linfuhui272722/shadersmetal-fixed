package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
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
    private static int lastColorTextureId = -1;
    private static int lastDepthTextureId = -1;

    // 反射缓存
    private static Field renderTargetField;
    private static Field colorTextureIdField;
    private static Field depthTextureIdField;

    // 方法缓存
    private static Method getFramebufferMethod;
    private static Method getRenderTargetMethod;
    private static Method getColorTextureIdMethod;
    private static Method getDepthTextureIdMethod;

    // 调试标志
    private static volatile boolean debugPrinted = false;

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
    // 纹理获取
    // =====================================================================

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
    // 反射辅助方法（优先使用方法，备选字段）
    // =====================================================================

    private static RenderTarget getRenderTarget() throws Exception {
        // ★ 调试：首次调用时打印所有相关方法
        if (!debugPrinted) {
            debugPrinted = true;
            System.err.println("[MetallumShaders] Debug: Scanning Minecraft methods for RenderTarget:");
            Method[] methods = Minecraft.class.getDeclaredMethods();
            for (Method m : methods) {
                if (m.getReturnType() == RenderTarget.class ||
                    m.getName().toLowerCase().contains("framebuffer") ||
                    m.getName().toLowerCase().contains("render") ||
                    m.getName().toLowerCase().contains("target")) {
                    System.err.println("[MetallumShaders]   " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                }
            }
            // 再打印所有返回 RenderTarget 的字段（也可能有用）
            Field[] fields = Minecraft.class.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() == RenderTarget.class) {
                    System.err.println("[MetallumShaders]   Field: " + f.getName() + " -> " + f.getType().getSimpleName());
                }
            }
        }

        // 1. 尝试通过 getFramebuffer() 方法（Mojang 映射）
        if (getFramebufferMethod == null) {
            try {
                getFramebufferMethod = Minecraft.class.getMethod("getFramebuffer");
            } catch (NoSuchMethodException ignored) {
                // 忽略
            }
        }
        if (getFramebufferMethod != null) {
            try {
                Object result = getFramebufferMethod.invoke(Minecraft.getInstance());
                if (result instanceof RenderTarget) {
                    System.err.println("[MetallumShaders] Found RenderTarget via getFramebuffer()");
                    return (RenderTarget) result;
                }
            } catch (Exception e) {
                LOGGER.debug("getFramebuffer() failed, trying fallbacks", e);
            }
        }

        // 2. 尝试通过 getRenderTarget() 方法（备选）
        if (getRenderTargetMethod == null) {
            try {
                getRenderTargetMethod = Minecraft.class.getMethod("getRenderTarget");
            } catch (NoSuchMethodException ignored) {
                // 忽略
            }
        }
        if (getRenderTargetMethod != null) {
            try {
                Object result = getRenderTargetMethod.invoke(Minecraft.getInstance());
                if (result instanceof RenderTarget) {
                    System.err.println("[MetallumShaders] Found RenderTarget via getRenderTarget()");
                    return (RenderTarget) result;
                }
            } catch (Exception e) {
                LOGGER.debug("getRenderTarget() failed, trying fallbacks", e);
            }
        }

        // 3. 尝试通过 getMainRenderTarget() 等方法（常见备选）
        String[] methodNames = {"getMainRenderTarget", "getRenderTarget", "getFramebuffer", "getFrameBuffer"};
        for (String name : methodNames) {
            try {
                Method m = Minecraft.class.getMethod(name);
                Object result = m.invoke(Minecraft.getInstance());
                if (result instanceof RenderTarget) {
                    System.err.println("[MetallumShaders] Found RenderTarget via " + name + "()");
                    return (RenderTarget) result;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                LOGGER.debug(name + "() failed", e);
            }
        }

        // 4. 尝试字段（备选）
        if (renderTargetField == null) {
            // 只扫描一次，不重复输出调试信息
            Field[] fields = Minecraft.class.getDeclaredFields();
            for (Field f : fields) {
                if (RenderTarget.class.isAssignableFrom(f.getType())) {
                    renderTargetField = f;
                    renderTargetField.setAccessible(true);
                    System.err.println("[MetallumShaders] Found RenderTarget field via fallback: " + f.getName());
                    break;
                }
            }
            if (renderTargetField == null) {
                LOGGER.error("Cannot find RenderTarget field or method in Minecraft");
                return null;
            }
        }
        return (RenderTarget) renderTargetField.get(Minecraft.getInstance());
    }

    private static int getRenderTargetColorTextureId(RenderTarget target) throws Exception {
        // 1. 尝试通过 getColorTextureId() 方法
        if (getColorTextureIdMethod == null) {
            try {
                getColorTextureIdMethod = RenderTarget.class.getMethod("getColorTextureId");
            } catch (NoSuchMethodException ignored) {
                // 忽略
            }
        }
        if (getColorTextureIdMethod != null) {
            try {
                return (int) getColorTextureIdMethod.invoke(target);
            } catch (Exception e) {
                LOGGER.debug("getColorTextureId() failed, trying fallbacks", e);
            }
        }

        // 2. 尝试通过 getColorTexture() 或 getTexture() 方法（其他映射）
        try {
            Method m = RenderTarget.class.getMethod("getColorTexture");
            return (int) m.invoke(target);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method m = RenderTarget.class.getMethod("getTexture");
            return (int) m.invoke(target);
        } catch (NoSuchMethodException ignored) {
        }

        // 3. 备选：通过字段
        if (colorTextureIdField == null) {
            String[] names = {"colorTextureId", "frameBufferId", "fbo", "colorTexture"};
            for (String name : names) {
                try {
                    colorTextureIdField = RenderTarget.class.getDeclaredField(name);
                    colorTextureIdField.setAccessible(true);
                    System.err.println("[MetallumShaders] Found color texture field via fallback: " + name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (colorTextureIdField == null) {
                LOGGER.error("Cannot find color texture ID field or method");
                return -1;
            }
        }
        return (int) colorTextureIdField.get(target);
    }

    private static int getRenderTargetDepthTextureId(RenderTarget target) throws Exception {
        // 1. 尝试通过 getDepthTextureId() 方法
        if (getDepthTextureIdMethod == null) {
            try {
                getDepthTextureIdMethod = RenderTarget.class.getMethod("getDepthTextureId");
            } catch (NoSuchMethodException ignored) {
                // 忽略
            }
        }
        if (getDepthTextureIdMethod != null) {
            try {
                return (int) getDepthTextureIdMethod.invoke(target);
            } catch (Exception e) {
                LOGGER.debug("getDepthTextureId() failed, trying fallbacks", e);
            }
        }

        // 2. 尝试通过字段
        if (depthTextureIdField == null) {
            String[] names = {"depthTextureId", "depthBufferId", "depthTexture"};
            for (String name : names) {
                try {
                    depthTextureIdField = RenderTarget.class.getDeclaredField(name);
                    depthTextureIdField.setAccessible(true);
                    System.err.println("[MetallumShaders] Found depth texture field via fallback: " + name);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (depthTextureIdField == null) {
                LOGGER.warn("Cannot find depth texture ID field or method");
                return -1;
            }
        }
        return (int) depthTextureIdField.get(target);
    }
}
