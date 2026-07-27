package com.metallum.shaders.jni;

import java.nio.ByteBuffer;

public class MetalNative {
    
    // --- 现有方法 ---
    public static native long getDefaultDevice();
    public static native long createCommandBuffer();
    public static native long createBuffer(long device, byte[] data, int length);
    public static native void commitCommandBuffer(long cmdBuffer);
    public static native long getDefaultCommandQueue();
    
    // ★★★ 新增方法：解决纹理读写冲突 ★★★

    /**
     * 创建一个 Metal 2D 纹理作为渲染目标
     * @param device  设备句柄
     * @param width   宽度
     * @param height  高度
     * @param format  像素格式 (推荐使用 0 = MTLPixelFormatRGBA8Unorm)
     * @return 纹理句柄
     */
    public static native long createTexture(long device, int width, int height, int format);

    /**
     * 销毁纹理
     */
    public static native void destroyTexture(long textureHandle);

    /**
     * 使用 Blit Command Encoder 复制纹理
     * 这用于将 Shader 计算的结果安全地写回主纹理
     */
    public static native void blitTexture(long cmdBuffer, long srcTexture, long dstTexture, int width, int height);
}
