package com.metallum.shaders.jni;

public final class MetalNative {
    private MetalNative() {}
    public static native long getDefaultDevice();
    public static native long compileLibrary(long deviceHandle, String source, String sourceName);
    public static native long buildPostPipeline(long deviceHandle, long libraryHandle, String vertexFnName, String fragmentFnName, int pixelFormat, int depthPixelFormat);
    public static native int dispatchFullscreen(long cmdBufferHandle, long pipelineHandle, long colorSrcHandle, long depthSrcHandle, long normalSrcHandle, long colorDstHandle, long uniformBuffer, long uniformSize);
    public static native long createBuffer(long deviceHandle, byte[] data, long size);
    public static native void release(long handle);
    public static native long getMetalTextureFromGLTexture(int textureId);
    public static native long getDefaultCommandQueue();
    public static native long createCommandBuffer();
    public static native void commitCommandBuffer(long cmdBufferHandle);
}
