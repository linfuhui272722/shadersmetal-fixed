// metallum_shaders.cpp
//
// JNI shim that bridges Java -> Apple Metal. Compiled into
// libmetallum_shaders.dylib by the `buildNative` gradle task.
//
// IMPORTANT: This file is compiled with -fobjc-arc. All Objective-C
// objects are automatically retained/released. Use __bridge for
// non-ownership transfers, __bridge_retained to hand ownership from
// ARC to a raw jlong, and __bridge_transfer to take ownership back.
//
// JNI function names MUST match the Java class's fully-qualified name:
//   Java class: com.metallum.shaders.jni.MetalNative
//   JNI symbol: Java_com_metallum_shaders_jni_MetalNative_<method>
//
// All Metal handles are passed as `jlong` (raw id<MTL...> pointers).

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
// compileLibrary
//   Java: MetalNative.compileLibrary(long device, String src, String name)
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
    opts.languageVersion = MTLLanguageVersion3_0;

    NSError* err = nil;
    id<MTLLibrary> lib = [device newLibraryWithSource:source
                                              options:opts
                                                error:&err];
    if (err) {
        NSLog(@"[MetallumShaders] Failed to compile %@: %@", name, err);
    }

    env->ReleaseStringUTFChars(sourceJ, src);
    env->ReleaseStringUTFChars(nameJ, nm);

    // Hand ownership of `lib` to the Java side (retained jlong).
    return (jlong) (__bridge_retained void*) lib;
}

// =========================================================================
// buildPostPipeline
//   Java: MetalNative.buildPostPipeline(long device, long library,
//                                       String vertName, String fragName,
//                                       int colorFmt, int depthFmt)
// =========================================================================
JNIEXPORT jlong JNICALL
Java_com_metallum_shaders_jni_MetalNative_buildPostPipeline(
    JNIEnv* env, jclass,
    jlong deviceHandle, jlong libraryHandle,
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
        NSLog(@"[MetallumShaders] Missing vertex/fragment function: %@ / %@", vname, fname);
        env->ReleaseStringUTFChars(vertexNameJ, vn);
        env->ReleaseStringUTFChars(fragmentNameJ, fn);
        return 0;
    }

    MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
    desc.vertexFunction = vfn;
    desc.fragmentFunction = ffn;
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat) colorFormat;
    desc.depthAttachmentPixelFormat = (MTLPixelFormat) depthFormat;
    desc.colorAttachments[0].blendingEnabled = NO;

    NSError* err = nil;
    id<MTLRenderPipelineState> pipe =
        [device newRenderPipelineStateWithDescriptor:desc error:&err];
    if (err) {
        NSLog(@"[MetallumShaders] Pipeline build failed: %@", err);
    }

    env->ReleaseStringUTFChars(vertexNameJ, vn);
    env->ReleaseStringUTFChars(fragmentNameJ, fn);

    return (jlong) (__bridge_retained void*) pipe;
}

// =========================================================================
// dispatchFullscreen
//   Java: MetalNative.dispatchFullscreen(long cmd, long pipe,
//                                        long colorSrc, long depthSrc,
//                                        long normalSrc, long colorDst,
//                                        long uniform, long uniformSize)
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

    MTLRenderPassDescriptor* desc = [MTLRenderPassDescriptor renderPassDescriptor];
    desc.colorAttachments[0].texture = colorDst;
    desc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    // No depth attachment — we only sample depth, we don't write it.

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
    return 0;
}

// =========================================================================
// createBuffer
//   Java: MetalNative.createBuffer(long device, byte[] data, long size)
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
//   Java: MetalNative.release(long handle)
//   Takes ownership back from the jlong and lets ARC release it.
// =========================================================================
JNIEXPORT void JNICALL
Java_com_metallum_shaders_jni_MetalNative_release(JNIEnv*, jclass, jlong handle) {
    if (!handle) return;
    id obj = (__bridge_transfer id)(void*) handle;
    (void) obj; // ARC releases `obj` at end of scope
}

} // extern "C"
