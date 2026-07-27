// metallum_shaders.cpp
//
// JNI shim that bridges Java -> Apple Metal.
//
// IMPORTANT: This file is compiled with -fobjc-arc.
// All Objective-C objects are automatically retained/released.

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
// 全局单例：防止每帧创建 Device/Queue 导致崩溃
// =========================================================================
static id<MTLDevice> g_sharedDevice = nil;
static id<MTLCommandQueue> g_sharedQueue = nil;

// =========================================================================
// getDefaultDevice
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_getDefaultDevice(JNIEnv* env, jclass) {
    @autoreleasepool {
        if (g_sharedDevice == nil) {
            g_sharedDevice = MTLCreateSystemDefaultDevice();
            if (!g_sharedDevice) {
                NSLog(@"[MetallumShaders] Failed to create default MTLDevice");
                return 0LL;
            }
            // 同时创建 Queue
            g_sharedQueue = [g_sharedDevice newCommandQueue];
        }
        return (jlong)(__bridge_retained void*) g_sharedDevice;
    }
}

// =========================================================================
// compileLibrary
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_compileLibrary(
    JNIEnv* env, jclass, jlong deviceHandle, jstring sourceJ, jstring nameJ) {

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    if (!device) return 0;

    const char* src = env->GetStringUTFChars(sourceJ, nullptr);
    const char* nm  = env->GetStringUTFChars(nameJ, nullptr);
    NSString* source = [NSString stringWithUTF8String:src];
    NSString* name   = [NSString stringWithUTF8String:nm];

    MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
    opts.languageVersion = MTLLanguageVersion2_3;

    NSError* err = nil;
    id<MTLLibrary> lib = [device newLibraryWithSource:source
                                              options:opts
                                                error:&err];
    if (err) {
        NSLog(@"[MetallumShaders] Failed to compile %@: %@", name, err);
    }

    env->ReleaseStringUTFChars(sourceJ, src);
    env->ReleaseStringUTFChars(nameJ, nm);

    return (jlong) (__bridge_retained void*) lib;
}

// =========================================================================
// buildPostPipeline
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_buildPostPipeline(
    JNIEnv* env, jclass,
    jlong deviceHandle, jlong libraryHandle,
    jstring vertexNameJ, jstring fragmentNameJ,
    jint colorFormat, jint depthFormat) {

    NSLog(@"[MetallumShaders] buildPostPipeline: device=0x%llx, library=0x%llx",
          (unsigned long long)deviceHandle, (unsigned long long)libraryHandle);
    NSLog(@"[MetallumShaders]   colorFormat=%d, depthFormat=%d", (int)colorFormat, (int)depthFormat);

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    id<MTLLibrary> lib   = (__bridge id<MTLLibrary>)(void*) libraryHandle;
    if (!device || !lib) {
        NSLog(@"[MetallumShaders]   Invalid device or library");
        return 0;
    }

    const char* vn = env->GetStringUTFChars(vertexNameJ, nullptr);
    const char* fn = env->GetStringUTFChars(fragmentNameJ, nullptr);
    NSString* vname = [NSString stringWithUTF8String:vn];
    NSString* fname = [NSString stringWithUTF8String:fn];

    id<MTLFunction> vfn = [lib newFunctionWithName:vname];
    id<MTLFunction> ffn = [lib newFunctionWithName:fname];
    if (!vfn || !ffn) {
        NSLog(@"[MetallumShaders] Missing vertex/fragment function: %@ / %@", vname, fname);
        env->ReleaseStringUTFChars(vertexNameJ, vn);
        env->ReleaseStringUTFChars(fragmentNameJ, fn);
        return 0;
    }

    MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vfn;
    desc.fragmentFunction = ffn;
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat) colorFormat;
    desc.colorAttachments[0].blendingEnabled = NO;

    if (depthFormat == 55) {
        desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    } else if (depthFormat == 0) {
        desc.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
    } else {
        desc.depthAttachmentPixelFormat = (MTLPixelFormat) depthFormat;
    }
    NSLog(@"[MetallumShaders]   After assignment: depthAttachmentPixelFormat = %d", (int)desc.depthAttachmentPixelFormat);

    NSError* err = nil;
    id<MTLRenderPipelineState> pipe =
        [device newRenderPipelineStateWithDescriptor:desc error:&err];
    if (err) {
        NSLog(@"[MetallumShaders] Pipeline build failed: %@", err);
    } else {
        NSLog(@"[MetallumShaders] Pipeline built successfully");
    }

    env->ReleaseStringUTFChars(vertexNameJ, vn);
    env->ReleaseStringUTFChars(fragmentNameJ, fn);

    return (jlong) (__bridge_retained void*) pipe;
}

// =========================================================================
// dispatchFullscreen
// =========================================================================
JNIEXPORT jint JNICALL
Java_com_metallum_shaders_jni_MetalNative_dispatchFullscreen(
    JNIEnv* env, jclass,
    jlong cmdBufferHandle, jlong pipelineHandle,
    jlong colorSrcHandle, jlong depthSrcHandle,
    jlong normalSrcHandle, jlong colorDstHandle,
    jlong uniformBufferHandle, jlong uniformSize) {

    id<MTLCommandBuffer> cmd = (__bridge id<MTLCommandBuffer>)(void*) cmdBufferHandle;
    id<MTLRenderPipelineState> pipe =
        (__bridge id<MTLRenderPipelineState>)(void*) pipelineHandle;
    id<MTLTexture> colorSrc = (__bridge id<MTLTexture>)(void*) colorSrcHandle;
    id<MTLTexture> depthSrc = (__bridge id<MTLTexture>)(void*) depthSrcHandle;
    id<MTLTexture> colorDst = (__bridge id<MTLTexture>)(void*) colorDstHandle;
    id<MTLBuffer>  uniform  = (__bridge id<MTLBuffer>)(void*) uniformBufferHandle;

    if (!cmd || !pipe || !colorSrc || !depthSrc || !colorDst) return 1;

    // 检测纹理读写冲突：如果源和目标是同一个纹理，需要使用临时纹理
    // 否则 Metal 会禁止这种情况（Render Target Feedback Loop）
    id<MTLTexture> actualDst = colorDst;
    bool needsBlitBack = false;
    MTLTextureDescriptor* texDesc = nil;
    id<MTLTexture> tempTex = nil;

    if (colorSrc == colorDst) {
        // 创建临时纹理
        texDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:colorDst.pixelFormat
                                                                    width:colorDst.width
                                                                   height:colorDst.height
                                                                mipmapped:NO];
        tempTex = [g_sharedDevice newTextureWithDescriptor:texDesc];
        actualDst = tempTex;
        needsBlitBack = true;
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
        id<MTLTexture> normalSrc = (__bridge id<MTLTexture>)(void*) normalSrcHandle;
        [enc setFragmentTexture:normalSrc atIndex:2];
    }
    if (uniform) {
        [enc setFragmentBuffer:uniform offset:0 atIndex:0];
    }
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];

    // 如果使用了临时纹理，需要拷贝回目标纹理
    if (needsBlitBack && tempTex != nil) {
        id<MTLBlitCommandEncoder> blitEnc = [cmd blitCommandEncoder];
        [blitEnc copyFromTexture:tempTex
                     sourceSlice:0
                     sourceLevel:0
                       toTexture:colorDst
                destinationSlice:0
                destinationLevel:0
                      sliceCount:1
                      levelCount:1];
        [blitEnc endEncoding];
    }

    return 0;
}

// =========================================================================
// createBuffer
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_createBuffer(
    JNIEnv* env, jclass, jlong deviceHandle, jbyteArray dataJ, jlong size) {

    id<MTLDevice> device = (__bridge id<MTLDevice>)(void*) deviceHandle;
    if (!device || !dataJ) return 0;

    jbyte* data = env->GetByteArrayElements(dataJ, nullptr);
    id<MTLBuffer> buf = [device newBufferWithBytes:data
                                            length:(NSUInteger) size
                                           options:MTLResourceStorageModeShared];
    env->ReleaseByteArrayElements(dataJ, data, JNI_ABORT);

    return (jlong) (__bridge_retained void*) buf;
}

// =========================================================================
// release
// =========================================================================
JNIEXPORT void JNICALL
Java_com_metallum_shaders_jni_MetalNative_release(JNIEnv*, jclass, jlong handle) {
    if (!handle) return;
    id
