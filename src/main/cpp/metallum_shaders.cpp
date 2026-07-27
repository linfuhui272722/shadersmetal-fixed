// metallum_shaders.cpp
// ... (保留头部 include 和全局变量)

// ... (保留 getDefaultDevice, compileLibrary, buildPostPipeline 等函数不变)

// =========================================================================
// dispatchFullscreen (优化：显式标记纹理用途)
// =========================================================================
JNIEXPORT jint JNICALL
Java_com_metallum_shaders_jni_MetalNative_dispatchFullscreen(
    JNIEnv* env, jclass,
    jlong cmdBufferHandle, jlong pipelineHandle,
    jlong colorSrcHandle, jlong depthSrcHandle,
    jlong normalSrcHandle, jlong colorDstHandle,
    jlong uniformBufferHandle, jlong uniformSize) {

    id<MTLCommandBuffer> cmd = (__bridge id<MTLCommandBuffer>)(void*) cmdBufferHandle;
    id<MTLRenderPipelineState> pipe = (__bridge id<MTLRenderPipelineState>)(void*) pipelineHandle;
    id<MTLTexture> colorSrc = (__bridge id<MTLTexture>)(void*) colorSrcHandle;
    id<MTLTexture> depthSrc = (__bridge id<MTLTexture>)(void*) depthSrcHandle;
    id<MTLTexture> colorDst = (__bridge id<MTLTexture>)(void*) colorDstHandle;
    id<MTLBuffer>  uniform  = (__bridge id<MTLBuffer>)(void*) uniformBufferHandle;

    if (!cmd || !pipe || !colorSrc || !depthSrc || !colorDst) return 1;

    id<MTLTexture> actualDst = colorDst;
    bool needsBlit = false;

    if (colorSrc == colorDst) {
        needsBlit = true;
        
        if (g_cachedTempTexture == nil || 
            g_cachedWidth != colorDst.width || 
            g_cachedHeight != colorDst.height ||
            g_cachedFormat != colorDst.pixelFormat) {
            
            @autoreleasepool {
                MTLTextureDescriptor* texDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:colorDst.pixelFormat
                                                                                                    width:colorDst.width
                                                                                                   height:colorDst.height
                                                                                                mipmapped:NO];
                
                // ★★★ 关键修复：显式标记纹理用途，防止 GPU 拒绝写入或读取 ★★★
                texDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
                texDesc.storageMode = MTLStorageModePrivate; // 性能优化：GPU 内部内存

                if (g_sharedDevice) {
                    g_cachedTempTexture = [g_sharedDevice newTextureWithDescriptor:texDesc];
                    g_cachedWidth = colorDst.width;
                    g_cachedHeight = colorDst.height;
                    g_cachedFormat = colorDst.pixelFormat;
                    NSLog(@"[MetallumShaders] Created new cached temp texture with correct usage flags: %lu x %lu", (unsigned long)g_cachedWidth, (unsigned long)g_cachedHeight);
                }
            }
        }
        
        actualDst = g_cachedTempTexture;
        if (!actualDst) return 1;
    }

    MTLRenderPassDescriptor* desc = [MTLRenderPassDescriptor renderPassDescriptor];
    desc.colorAttachments[0].texture = actualDst;
    desc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    desc.colorAttachments[0].storeAction = MTLStoreActionStore;

    id<MTLRenderCommandEncoder> enc = [cmd renderCommandEncoderWithDescriptor:desc];
    [enc setRenderPipelineState:pipe];
    [enc setFragmentTexture:colorSrc atIndex:0];
    [enc setFragmentTexture:depthSrc atIndex:1];
    if (normalSrcHandle) {
        [enc setFragmentTexture:(__bridge id<MTLTexture>)(void*) normalSrcHandle atIndex:2];
    }
    if (uniform) [enc setFragmentBuffer:uniform offset:0 atIndex:0];
    
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];

    if (needsBlit && g_cachedTempTexture != nil) {
        id<MTLBlitCommandEncoder> blitEnc = [cmd blitCommandEncoder];
        [blitEnc copyFromTexture:g_cachedTempTexture sourceSlice:0 sourceLevel:0
                         toTexture:colorDst destinationSlice:0 destinationLevel:0
                          sliceCount:1 levelCount:1];
        [blitEnc endEncoding];
    }

    return 0;
}

// ... (保留 createBuffer, release 等函数不变，注意 createBuffer 使用全局缓存逻辑)
// 确保 createBuffer 是我们之前修正过的那个（复用 g_cachedUniformBuffer）
