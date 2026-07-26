package com.metallum.shaders.metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.metallum.shaders.jni.MetalNative;

import java.util.Optional;

public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/MetalBridge");

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;

    // 缓存设备句柄，避免重复调用 Native
    private static long deviceHandle = -1L;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        try {
            // 1. 确保 Native 库已加载
            if (!NativeLoader.ensureLoaded()) {
                LOGGER.warn("Native shim not loaded; Metal bridge unavailable.");
                return;
            }

            // 2. 从自己的 MetalNative 获取默认设备
            //    假设 MetalNative 有一个静态方法 getDefaultDevice()
            //    如果没有，你可以改为直接调用 MetalNative 中的 JNI 方法
            deviceHandle = MetalNative.getDefaultDevice();   // ← 需要你实现这个方法
            if (deviceHandle <= 0) {
                LOGGER.warn("Failed to obtain MTLDevice handle from MetalNative.");
                return;
            }

            available = true;
            LOGGER.info("MetalBridge initialised with self-contained MetalNative (device={})", deviceHandle);
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

    // ---------- 纹理获取 ----------
    // 由于你现在没有外部 metal 渲染器，这些纹理句柄无法从外部获得。
    // 你可以返回 -1，然后在 ShaderManager 中判断跳过渲染。
    // 或者你可以自己实现从游戏 framebuffer 获取纹理的方法（需要 JNI 桥接）。
    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 实现获取颜色纹理的 Native 方法
        return -1L;
    }

    public static long getMainDepthTextureHandle() {
        if (!isAvailable()) return -1L;
        return -1L;
    }

    public static Optional<Long> getMainNormalTextureHandle() {
        if (!isAvailable()) return Optional.empty();
        return Optional.empty();
    }

    public static long getCommandQueueHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 如果你需要 command queue，也可以从 MetalNative 获取
        return -1L;
    }

    public static long getCurrentCommandBufferHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 同样从 MetalNative 获取当前 command buffer
        return -1L;
    }

    // 别名方法
    public static long getCommandBufferHandle() {
        return getCurrentCommandBufferHandle();
    }

    public static void submitCommandBuffer() {
        // 如果实现了提交，可以调用 MetalNative
    }
}
