// metallum_shaders.cpp
// JNI shim that bridges Java -> Apple Metal.
// IMPORTANT: Compiled with -fobjc-arc.

#include <jni.h>
#include <objc/objc.h>
#include <objc/runtime.h>
#include <objc/message.h>
#include <Foundation/Foundation.h>
#include <Metal/Metal.h>
#include <vector>
#include <cstring>

extern "C" {

// =========================================================================
// 全局单例
// =========================================================================
static id<MTLDevice> g_sharedDevice = nil;
static id<MTLCommandQueue> g_sharedQueue = nil;
static id<MTLSamplerState> g_sharedSampler = nil;

// 缓存临时纹理
static id<MTLTexture> g_cachedTempTexture = nil;
static NSUInteger g_cachedWidth = 0;
static NSUInteger g_cachedHeight = 0;
static MTLPixelFormat g_cachedFormat = MTLPixelFormatInvalid;

// ★★★ 关键修复：增加缓冲池大小到 10，防止 CPU 覆盖 GPU 正在使用的资源 ★★★
#define BUFFER_COUNT 10 
static id<MTLBuffer> g_uniformBuffers[BUFFER_COUNT] = { nil };
static NSUInteger g_currentBufferIndex = 0;

// =========================================================================
// 辅助函数：初始化全局 Sampler
// =========================================================================
static void initSampler() {
    if (g_sharedSampler == nil && g_sharedDevice != nil) {
        MTLSamplerDescriptor* desc = [[MTLSamplerDescriptor alloc] init];
        desc.minFilter = MTLSamplerMinMagFilterLinear;
        desc.magFilter = MTLSamplerMinMagFilterLinear;
        desc.mipFilter = MTLSamplerMipFilterNotMipmapped;
        desc.sAddressMode = MTLSamplerAddressModeClampToEdge;
        desc.tAddressMode = MTLSamplerAddressModeClampToEdge;
        desc.maxAnisotropy = 1;
        
        g_sharedSampler = [g_sharedDevice newSamplerStateWithDescriptor:desc];
        NSLog(@"[MetallumShaders] Created shared sampler state");
    }
}

// =========================================================================
// getDefaultDevice
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_getDefaultDevice(JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        if (g_sharedDevice == nil) {
            g_sharedDevice = MTLCreateSystemDefaultDevice();
            if (!g_sharedDevice) return 0LL;
            g_sharedQueue = [g_sharedDevice newCommandQueue];
            initSampler();
        }
        return (jlong)(__bridge_retained void*) g_sharedDevice;
    }
}

// =========================================================================
// compileLibrary
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_compileLibrary(
    JNIEnv *env, jclass clazz, jlong deviceHandle, jstring sourceJ, jstring nameJ) {

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    if (!device) return 0;

    const char* src = env->GetStringUTFChars(sourceJ, nullptr);
    const char* nm  = env->GetStringUTFChars(nameJ, nullptr);
    NSString* source = [NSString stringWithUTF8String:src];
    NSString* name   = [NSString stringWithUTF8String:nm];

    MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
    opts.languageVersion = MTLLanguageVersion2_3;

    NSError* err = nil;
    id<MTLLibrary> lib = [device newLibraryWithSource:source options:opts error:&err];
    if (err) NSLog(@"[MetallumShaders] Failed to compile %@: %@", name, err);

    env->ReleaseStringUTFChars(sourceJ, src);
    env->ReleaseStringUTFChars(nameJ, nm);
    return (jlong) (__bridge_retained void*) lib;
}

// =========================================================================
// buildPostPipeline
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_buildPostPipeline(
    JNIEnv *env, jclass clazz, jlong deviceHandle, jlong libraryHandle,
    jstring vertexNameJ, jstring fragmentNameJ,
    jint colorFormat, jint depthFormat) {

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    id<MTLLibrary> lib   = (__bridge id<MTLLibrary>)(void*) libraryHandle;
    if (!device || !lib) return 0;

    const char* vn = env->GetStringUTFChars(vertexNameJ, nullptr);
    const char* fn = env->GetStringUTFChars(fragmentNameJ, nullptr);
    NSString* vname = [NSString stringWithUTF8String:vn];
    NSString* fname = [NSString stringWithUTF8String:fn];

    id<MTLFunction> vfn = [lib newFunctionWithName:vname];
    id<MTLFunction> ffn = [lib newFunctionWithName:fname];
    if (!vfn || !ffn) {
        NSLog(@"[MetallumShaders] Missing function: %@ / %@", vname, fname);
        env->ReleaseStringUTFChars(vertexNameJ, vn);
        env->ReleaseStringUTFChars(fragmentNameJ, fn);
        return 0;
    }

    MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vfn;
    desc.fragmentFunction = ffn;
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat) colorFormat;
    desc.colorAttachments[0].blendingEnabled = NO;

    if (depthFormat == 55) desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    else if (depthFormat == 0) desc.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
    else desc.depthAttachmentPixelFormat = (MTLPixelFormat) depthFormat;

    NSError* err = nil;
    id<MTLRenderPipelineState> pipe = [device newRenderPipelineStateWithDescriptor:desc error:&err];
    if (err) NSLog(@"[MetallumShaders] Pipeline build failed: %@", err);
    
    env->ReleaseStringUTFChars(vertexNameJ, vn);
    env->ReleaseStringUTFChars(fragmentNameJ, fn);
    return (jlong) (__bridge_retained void*) pipe;
}

// =========================================================================
// dispatchFullscreen
// =========================================================================
JNIEXPORT jint JNICALL
Java_com_metallum_shaders_jni_MetalNative_dispatchFullscreen(
    JNIEnv *env, jclass clazz, jlong cmdBufferHandle, jlong pipelineHandle,
    jlong colorSrcHandle, jlong depthSrcHandle, jlong normalSrcHandle, 
    jlong colorDstHandle, jlong uniformBufferHandle, jlong uniformSize) {

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
        
        // 重建临时纹理逻辑
        if (g_cachedTempTexture == nil || 
            g_cachedWidth != colorDst.width || 
            g_cachedHeight != colorDst.height ||
            g_cachedFormat != colorDst.pixelFormat) {
            
            @autoreleasepool {
                MTLTextureDescriptor* texDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:colorDst.pixelFormat
                                                                                                    width:colorDst.width
                                                                                                   height:colorDst.height
                                                                                                mipmapped:NO];
                texDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
                texDesc.storageMode = MTLStorageModePrivate;

                if (g_sharedDevice) {
                    g_cachedTempTexture = [g_sharedDevice newTextureWithDescriptor:texDesc];
                    g_cachedWidth = colorDst.width;
                    g_cachedHeight = colorDst.height;
                    g_cachedFormat = colorDst.pixelFormat;
                    NSLog(@"[MetallumShaders] Created cached temp texture (Private): %lu x %lu", (unsigned long)g_cachedWidth, (unsigned long)g_cachedHeight);
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
    
    if (g_sharedSampler) {
        [enc setFragmentSamplerState:g_sharedSampler atIndex:0];
    }
    
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

// =========================================================================
// createBuffer (修正版：大缓冲池)
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_createBuffer(
    JNIEnv *env, jclass clazz, jlong deviceHandle, jbyteArray dataJ, jlong size) {

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    if (!device || !dataJ) return 0;

    // 轮转缓冲索引
    NSUInteger bufferIndex = g_currentBufferIndex;
    const NSUInteger requiredSize = (NSUInteger)size;
    // 分配稍大的空间以减少重新分配
    const NSUInteger allocSize = 4096; 

    // 如果缓冲不存在或太小，重新分配
    if (g_uniformBuffers[bufferIndex] == nil || 
        g_uniformBuffers[bufferIndex].length < requiredSize) {
        
        g_uniformBuffers[bufferIndex] = nil;
        g_uniformBuffers[bufferIndex] = [device newBufferWithLength:allocSize options:MTLResourceStorageModeShared];
    }

    // 获取 Java 数据指针
    jbyte* data = env->GetByteArrayElements(dataJ, nullptr);
    if (!data) return 0;

    // 拷贝数据
    void* bufferContents = [g_uniformBuffers[bufferIndex] contents];
    memcpy(bufferContents, data, (size_t)size);
    
    // 释放 Java 数组引用
    env->ReleaseByteArrayElements(dataJ, data, JNI_ABORT);

    // 返回当前 Buffer 的 Handle
    return (jlong)(__bridge void*) g_uniformBuffers[bufferIndex];
}

// =========================================================================
// commitCommandBuffer (修正版：提交后轮转)
// =========================================================================
JNIEXPORT void JNICALL 
Java_com_metallum_shaders_jni_MetalNative_commitCommandBuffer(JNIEnv *env, jclass clazz, jlong handle) {
    if (!handle) return;
    id<MTLCommandBuffer> buf = (__bridge id<MTLCommandBuffer>)(void*) handle;
    
    // 提交到 GPU
    [buf commit];
    
    // 提交后才轮转索引，确保下一帧使用新的 Buffer
    // 这样理论上当前帧的数据在 GPU 处理完之前不会被覆盖
    g_currentBufferIndex = (g_currentBufferIndex + 1) % BUFFER_COUNT;
}

// =========================================================================
// 其他方法
// =========================================================================
JNIEXPORT void JNICALL
Java_com_metallum_shaders_jni_MetalNative_release(JNIEnv *env, jclass clazz, jlong handle) {
}

JNIEXPORT jlong JNICALL 
Java_com_metallum_shaders_jni_MetalNative_getMetalTextureFromGLTexture(JNIEnv *env, jclass clazz, jint textureId) {
    return 0LL;
}

JNIEXPORT jlong JNICALL 
Java_com_metallum_shaders_jni_MetalNative_getDefaultCommandQueue(JNIEnv *env, jclass clazz) {
    return (jlong)(__bridge_retained void*) g_sharedQueue;
}

JNIEXPORT jlong JNICALL 
Java_com_metallum_shaders_jni_MetalNative_createCommandBuffer(JNIEnv *env, jclass clazz) {
    if (!g_sharedQueue) return 0LL;
    return (jlong)(__bridge_retained void*) [g_sharedQueue commandBuffer];
}

} // extern "C"
