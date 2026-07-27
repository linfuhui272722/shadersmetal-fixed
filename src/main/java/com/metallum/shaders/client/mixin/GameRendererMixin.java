package com.metallum.shaders.client.mixin;

import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.jni.MetalNative;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Mixin");
    private static int frameCounter = 0;

    // 辅助方法：将 Matrix4f 写入 ByteBuffer
    private static void putMatrix(ByteBuffer buf, Matrix4f mat) {
        // Metal 矩阵是列主序，JOML 也是列主序，直接 get 4x4 即可
        // 写入 16 个 float (64 字节)
        buf.putFloat(mat.m00());
        buf.putFloat(mat.m01());
        buf.putFloat(mat.m02());
        buf.putFloat(mat.m03());
        buf.putFloat(mat.m10());
        buf.putFloat(mat.m11());
        buf.putFloat(mat.m12());
        buf.putFloat(mat.m13());
        buf.putFloat(mat.m20());
        buf.putFloat(mat.m21());
        buf.putFloat(mat.m22());
        buf.putFloat(mat.m23());
        buf.putFloat(mat.m30());
        buf.putFloat(mat.m31());
        buf.putFloat(mat.m32());
        buf.putFloat(mat.m33());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) return;

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        long cmdBuffer = MetalNative.createCommandBuffer();
        if (cmdBuffer <= 0) return;

        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (colorSrc <= 0) return;

        if (frameCounter % 60 == 0) {
             LOGGER.info("[MetallumMixins] Rendering frame...");
        }
        frameCounter++;

        long colorDst = colorSrc;
        
        Minecraft mc = Minecraft.getInstance();
        Camera camera = MetalBridge.getMainCamera();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        
        // ==========================================
        // 1. 构建 Uniform 数据缓冲区
        // ==========================================
        // 结构体总大小约 784 字节，我们分配 1024 字节以保安全
        ByteBuffer uniformData = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());

        // --- Offset 0: viewProj (mat4) ---
        // 我们暂时用单位矩阵或估算矩阵，因为精确矩阵需要 Hook RenderSystem
        // 如果需要精确渲染，这部分需要从 RenderSystem 获取
        Matrix4f viewProj = new Matrix4f(); 
        // TODO: 如果你能获取到 RenderSystem 的矩阵，请在这里填入真实的 viewProj
        // 示例：简单估算 (这会导致世界位置计算略有偏差，但不会黑屏)
        viewProj.identity(); 
        putMatrix(uniformData, viewProj);

        // --- Offset 64: invViewProj (mat4) ---
        // 这是 Shader 最关键的数据！用于从深度重建世界坐标。
        // 我们需要构建一个基本的逆矩阵。
        Matrix4f invViewProj = new Matrix4f();
        try {
            // 尝试基于摄像机构建一个基础的逆矩阵
            // 注意：完美的矩阵需要 Hook Minecraft 的矩阵栈。
            // 这里使用一个 "足够好" 的近似值，防止 Shader 崩溃。
            float fov = (float) Math.toRadians(mc.gameRenderer.getFov(camera, deltaTracker, true));
            float aspect = (float) width / height;
            
            // 构建一个标准的 Projection * View 并求逆
            new Matrix4f().perspective(fov, aspect, 0.05f, 1000.0f)
                .translate((float)-camera.position().x, (float)-camera.position().y, (float)-camera.position().z)
                .rotateXYZ(-camera.getXRot() * 0.017453292F, -camera.getYRot() * 0.017453292F, 0)
                .invert(invViewProj);
        } catch (Exception e) {
            // 防止计算错误导致崩溃，至少填入单位矩阵
            invViewProj.identity();
        }
        putMatrix(uniformData, invViewProj);

        // --- Offset 128: cameraPos (float4) ---
        uniformData.putFloat((float) camera.position().x);
        uniformData.putFloat((float) camera.position().y);
        uniformData.putFloat((float) camera.position().z);
        uniformData.putFloat(0); // w unused

        // --- Offset 144: sunDir (float4) ---
        // 简单的光照方向 (正上方)
        uniformData.putFloat(0).putFloat(1).putFloat(0).putFloat(0);

        // --- Offset 160: sunColor (float4) ---
        // 阳光颜色 (暖色)
        uniformData.putFloat(1.0f).putFloat(0.9f).putFloat(0.8f).putFloat(0);
        
        // --- Offset 176: moonDir (float4) ---
        uniformData.putFloat(0).putFloat(-1).putFloat(0).putFloat(0);
        
        // --- Offset 192: moonColor (float4) ---
        uniformData.putFloat(0.4f).putFloat(0.4f).putFloat(0.7f).putFloat(0);

        // --- Offset 208: timePack (float4) ---
        float time = System.currentTimeMillis() / 1000.0f;
        uniformData.putFloat(time);      // time
        uniformData.putFloat(1.0f/60f);  // frameTime
        uniformData.putFloat(1.0f);      // exposure
        uniformData.putFloat(1.0f);      // saturation

        // --- Offset 224: fogPack (float4) ---
        uniformData.putFloat(0.002f);    // fogDensity
        uniformData.putFloat(1.0f);      // fogFalloff
        uniformData.putFloat(0.5f);      // skyFogBlend
        uniformData.putFloat(0.2f);      // bloomStrength

        // --- Offset 240: bloomPack (float4) ---
        uniformData.putFloat(0.8f);      // bloomThreshold
        uniformData.putFloat(0.2f);      // vignetteStrength
        uniformData.putFloat(1.0f);      // renderScale
        uniformData.putFloat(0);         // pad

        // --- Offset 256: resolution (float4) ---
        uniformData.putFloat(width);
        uniformData.putFloat(height);
        uniformData.putFloat(0);         // lightCount (暂时设为0，因为动态灯光很复杂)
        uniformData.putFloat(0);         // pad

        // --- Offset 272: lights[16] ---
        // 这里有 16 个 Light 结构体，每个 32 字节。
        // 我们需要用 0 填充剩余部分，确保缓冲区大小正确
        int currentPos = uniformData.position();
        int targetSize = 784; // 结构体总大小
        if (currentPos < targetSize) {
            byte[] zeros = new byte[targetSize - currentPos];
            uniformData.put(zeros);
        }

        uniformData.flip();
        byte[] uniformBytes = new byte[uniformData.remaining()];
        uniformData.get(uniformBytes);

        long device = MetalBridge.getDeviceHandle();
        long uniformBuffer = 0;
        
        try {
            uniformBuffer = MetalNative.createBuffer(device, uniformBytes, uniformBytes.length);

            int result = MetalNative.dispatchFullscreen(
                cmdBuffer, pipeline, colorSrc, depthSrc, normalSrc, colorDst, uniformBuffer, uniformBytes.length
            );
            
            if (result != 0) LOGGER.error("[MetallumMixins] dispatchFullscreen error: {}", result);
            
            MetalNative.commitCommandBuffer(cmdBuffer);
        } catch (Exception e) {
            LOGGER.error("[MetallumMixins] Exception during render", e);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        ShaderManager.reload();
    }
}
