package com.metallum.shaders.metal;

import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.jni.NativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

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

    // ---------- 纹理获取（需要额外实现） ----------
    // 如果你自己实现了从游戏 framebuffer 获取纹理的 JNI 方法，
    // 在这里调用它们。目前返回 -1 表示不可用。
    public static long getMainColorTextureHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 实现你自己的纹理获取
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
        // TODO: 如果需要，从 MetalNative 获取
        return -1L;
    }

    public static long getCurrentCommandBufferHandle() {
        if (!isAvailable()) return -1L;
        // TODO: 如果需要，从 MetalNative 获取
        return -1L;
    }

    public static long getCommandBufferHandle() {
        return getCurrentCommandBufferHandle();
    }

    public static void submitCommandBuffer() {
        // TODO: 提交命令
    }
}
