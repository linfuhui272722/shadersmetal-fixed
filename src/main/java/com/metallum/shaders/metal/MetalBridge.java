package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Self-contained Metal bridge that uses our own native library.
 * No external Metallum mod is required.
 */
public final class MetalBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/MetalBridge");

    private static volatile boolean initialised = false;
    private static volatile boolean available = false;
    private static long deviceHandle = -1L;

    private MetalBridge() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        try {
            // 1. 加载 Native 库
            if (!NativeLoader.ensureLoaded()) {
                LOGGER.warn("Native shim not loaded; Metal bridge unavailable.");
                return;
            }

            // 2. 从 MetalNative 获取设备
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

    // ---------- 纹理获取（当前返回 -1，需要进一步实现） ----------
    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 实现从游戏 framebuffer 获取颜色纹理
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
        // TODO: 如果实现 command queue 获取，可添加
        return -1L;
    }

    public static long getCurrentCommandBufferHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 实现获取当前 command buffer
        return -1L;
    }

    public static long getCommandBufferHandle() {
        return getCurrentCommandBufferHandle();
    }

    public static void submitCommandBuffer() {
        // TODO: 实现提交
    }
}
