package com.metallum.shaders.jni;

public final class MetalNative {

    private MetalNative() {}

    // ---------- 设备 ----------
    public static native long getDefaultDevice();

    // ---------- 编译 ----------
    public static native long compileLibrary(long deviceHandle, String source, String sourceName);

    // ---------- 管线 ----------
    public static native long buildPostPipeline(long deviceHandle, long libraryHandle,
                                                String vertexFnName, String fragmentFnName,
                                                int pixelFormat, int depthPixelFormat);

    // ---------- 渲染 ----------
    public static native int dispatchFullscreen(long cmdBufferHandle, long pipelineHandle,
                                                long colorSrcHandle, long depthSrcHandle,
                                                long normalSrcHandle, long colorDstHandle,
                                                long uniformBuffer, long uniformSize);

    // ---------- 缓冲 ----------
    public static native long createBuffer(long deviceHandle, byte[] data, long size);
    public static native void release(long handle);

    // ---------- ★ 新增：纹理映射 ----------
    public static native long getMetalTextureFromGLTexture(int textureId);

    // ---------- ★ 新增：命令队列 ----------
    public static native long getDefaultCommandQueue();

    // ---------- ★ 新增：命令缓冲 ----------
    public static native long createCommandBuffer();

    // ★★★ 新增方法：提交命令缓冲区 ★★★
    public static native void commitCommandBuffer(long cmdBufferHandle);
}
