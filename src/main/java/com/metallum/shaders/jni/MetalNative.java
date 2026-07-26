package com.metallum.shaders.jni;

/**
 * Raw JNI surface exposed by {@code libmetallum_shaders.dylib}.
 *
 * <p>Every method takes raw Metal handle pointers (the same {@code long}
 * values that {@link com.metallum.shaders.metal.MetalBridge} returns) and
 * returns 0 on success, non-zero on failure. The Java side is responsible
 * for translating those return codes into log messages.
 *
 * <p>The native implementation lives in {@code src/main/cpp/metallum_shaders.cpp}
 * and is compiled into the dylib by the {@code buildNative} gradle task
 * (see {@code build.gradle}).
 */
public final class MetalNative {

    private MetalNative() {}

    /**
     * Compile a Metal source string into a library on the given device.
     *
     * @param deviceHandle  {@code id<MTLDevice>}
     * @param source        MSL source
     * @param sourceName    filename used in error messages
     * @return library handle, or 0 on failure
     */
    public static native long compileLibrary(long deviceHandle, String source, String sourceName);

    /**
     * Build a render pipeline state for a fullscreen-triangle post pass.
     *
     * @param deviceHandle    {@code id<MTLDevice>}
     * @param libraryHandle   {@code id<MTLLibrary>}
     * @param vertexFnName    vertex function name in the library
     * @param fragmentFnName  fragment function name in the library
     * @param pixelFormat     MTLPixelFormat of the color attachment (e.g. 80 = BGRA8Unorm)
     * @param depthPixelFormat MTLPixelFormat of the depth attachment (e.g. 55 = Depth32Float)
     * @return pipeline handle, or 0 on failure
     */
    public static native long buildPostPipeline(long deviceHandle, long libraryHandle,
                                                String vertexFnName, String fragmentFnName,
                                                int pixelFormat, int depthPixelFormat);

    /**
     * Issue a fullscreen-triangle draw with the given pipeline, sampling
     * the supplied color/depth textures and writing into the supplied
     * destination texture.
     *
     * @param cmdBufferHandle  {@code id<MTLCommandBuffer>}
     * @param pipelineHandle   {@code id<MTLRenderPipelineState>}
     * @param colorSrcHandle   {@code id<MTLTexture>} (input color)
     * @param depthSrcHandle   {@code id<MTLTexture>} (input depth)
     * @param normalSrcHandle  {@code id<MTLTexture>} (input normals, 0 if none)
     * @param colorDstHandle   {@code id<MTLTexture>} (output color)
     * @param uniformBuffer    pointer to a packed uniform struct
     * @param uniformSize      size of the uniform struct in bytes
     * @return 0 on success
     */
    public static native int dispatchFullscreen(long cmdBufferHandle, long pipelineHandle,
                                                long colorSrcHandle, long depthSrcHandle,
                                                long normalSrcHandle, long colorDstHandle,
                                                long uniformBuffer, long uniformSize);

    /**
     * Allocate a {@code MTLBuffer} on the device and copy the supplied
     * bytes into it. Returns the buffer handle, or 0 on failure.
     */
    public static native long createBuffer(long deviceHandle, byte[] data, long size);

    /** Release any Metal object by handle. Safe to call with 0. */
    public static native void release(long handle);
}

