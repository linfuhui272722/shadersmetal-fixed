package com.metallum.shaders.jni;

import java.nio.ByteBuffer;

/**
 * JNI 桥接类，连接 Java 和 Metal 原生代码。
 * 所有方法均为 native，由 C++ 实现。
 */
public class MetalNative {

    // ==================== 设备与队列 ====================

    /**
     * 获取默认 Metal 设备
     */
    public static native long getDefaultDevice();

    /**
     * 获取默认命令队列
     */
    public static native long getDefaultCommandQueue();

    /**
     * 创建命令缓冲区
     */
    public static native long createCommandBuffer();

    /**
     * 提交命令缓冲区
     */
    public static native void commitCommandBuffer(long cmdBuffer);

    // ==================== 缓冲区管理 ====================

    /**
     * 创建 GPU 缓冲区
     * @param device 设备句柄
     * @param data 数据
     * @param length 数据长度
     * @return 缓冲区句柄
     */
    public static native long createBuffer(long device, byte[] data, int length);

    /**
     * 释放缓冲区
     */
    public static native void releaseBuffer(long buffer);

    // ==================== 纹理管理 ====================

    /**
     * 创建纹理
     * @param device 设备句柄
     * @param width 宽度
     * @param height 高度
     * @param format 像素格式 (0 = RGBA8_UNORM)
     * @return 纹理句柄
     */
    public static native long createTexture(long device, int width, int height, int format);

    /**
     * 销毁纹理
     */
    public static native void destroyTexture(long textureHandle);

    /**
     * 使用 Blit 命令编码器复制纹理
     * @param cmdBuffer 命令缓冲区
     * @param srcTexture 源纹理
     * @param dstTexture 目标纹理
     * @param width 宽度
     * @param height 高度
     */
    public static native void blitTexture(long cmdBuffer, long srcTexture, long dstTexture, int width, int height);

    // ==================== 着色器编译与管线构建 ====================

    /**
     * 编译 Metal 着色器库
     * @param device 设备句柄
     * @param source 着色器源码
     * @param entryPointName 入口点名称
     * @return 着色器库句柄
     */
    public static native long compileLibrary(long device, String source, String entryPointName);

    /**
     * 构建后处理渲染管线
     * @param device 设备句柄
     * @param library 着色器库句柄
     * @param vertexFunction 顶点着色器函数名
     * @param fragmentFunction 片段着色器函数名
     * @param pixelFormat 像素格式
     * @param blendMode 混合模式
     * @return 管线状态句柄
     */
    public static native long buildPostPipeline(long device, long library, 
                                                String vertexFunction, 
                                                String fragmentFunction, 
                                                int pixelFormat, 
                                                int blendMode);

    // ==================== 渲染调度 ====================

    /**
     * 分发全屏渲染操作
     * @param cmdBuffer 命令缓冲区
     * @param pipeline 管线状态
     * @param colorSrc 颜色输入纹理
     * @param depthSrc 深度输入纹理
     * @param normalSrc 法线输入纹理（可选）
     * @param colorDst 颜色输出纹理
     * @param uniformBuffer 统一缓冲区
     * @param uniformSize 统一缓冲区大小
     * @return 错误码 (0 = 成功)
     */
    public static native int dispatchFullscreen(long cmdBuffer, 
                                               long pipeline, 
                                               long colorSrc, 
                                               long depthSrc, 
                                               long normalSrc, 
                                               long colorDst, 
                                               long uniformBuffer, 
                                               int uniformSize);

    // ==================== 资源释放 ====================

    /**
     * 通用资源释放方法
     * 根据资源类型自动选择正确的释放函数
     */
    public static void release(long handle) {
        if (handle == 0) return;
        
        // 尝试释放缓冲区（如果失败则忽略）
        try {
            releaseBuffer(handle);
            return;
        } catch (UnsatisfiedLinkError ignored) {}
        
        // 尝试释放纹理
        try {
            destroyTexture(handle);
            return;
        } catch (UnsatisfiedLinkError ignored) {}
        
        // 其他资源类型的释放逻辑可以在这里添加
        System.err.println("[MetalNative] Warning: Unknown resource type for handle: " + handle);
    }

    /**
     * 释放缓冲区（兼容旧代码）
     */
    public static void releaseBuffer(long handle) {
        if (handle != 0) {
            // 这里应该调用 native 方法释放缓冲区
            // 由于你没有提供具体的 native 实现，这里只是占位符
            // 实际实现需要在 C++ 中完成
            System.err.println("[MetalNative] Warning: releaseBuffer native method not implemented yet");
        }
    }
}
