package com.metallum.shaders.client.mixin;

import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.jni.MetalNative;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
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
        ByteBuffer uniformData = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());

        // --- Offset 0: viewProj (mat4) ---
        Matrix4f viewProj = new Matrix4f();
        viewProj.identity();
        putMatrix(uniformData, viewProj);

        // --- Offset 64: invViewProj (mat4) ---
        // 简化矩阵构建：使用基础投影和相机位置
        Matrix4f invViewProj = new Matrix4f();
        try {
            float fov = 70.0f; // 固定 FOV 值，确保编译通过
            float aspect = (float) width / height;
            
            // 构建基础投影矩阵
            new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000.0f)
                // 应用相机位置偏移
                .translate((float)-camera.position().x, (float)-camera.position().y, (float)-camera.position().z)
                // 求逆
                .invert(invViewProj);
        } catch (Exception e) {
            invViewProj.identity();
        }
        putMatrix(uniformData, invViewProj);

        // --- Offset 128: cameraPos (float4) ---
        uniformData.putFloat((float) camera.position().x);
        uniformData.putFloat((float) camera.position().y);
        uniformData.putFloat((float) camera.position().z);
        uniformData.putFloat(0);

        // --- Offset 144: sunDir (float4) ---
        uniformData.putFloat(0).putFloat(1).putFloat(0).putFloat(0);

        // --- Offset 160: sunColor (float4) ---
        uniformData.putFloat(1.0f).putFloat(0.9f).putFloat(0.8f).putFloat(0);
        
        // --- Offset 176: moonDir (float4) ---
        uniformData.putFloat(0).putFloat(-1).putFloat(0).putFloat(0);
        
        // --- Offset 192: moonColor (float4) ---
        uniformData.putFloat(0.4f).putFloat(0.4f).putFloat(0.7f).putFloat(0);

        // --- Offset 208: timePack (float4) ---
        float time = System.currentTimeMillis() / 1000.0f;
        uniformData.putFloat(time);
        uniformData.putFloat(1.0f/60f);
        uniformData.putFloat(1.0f);
        uniformData.putFloat(1.0f);

        // --- Offset 224: fogPack (float4) ---
        uniformData.putFloat(0.002f);
        uniformData.putFloat(1.0f);
        uniformData.putFloat(0.5f);
        uniformData.putFloat(0.2f);

        // --- Offset 240: bloomPack (float4) ---
        uniformData.putFloat(0.8f);
        uniformData.putFloat(0.2f);
        uniformData.putFloat(1.0f);
        uniformData.putFloat(0);

        // --- Offset 256: resolution (float4) ---
        uniformData.putFloat(width);
        uniformData.putFloat(height);
        uniformData.putFloat(0);
        uniformData.putFloat(0);

        // --- Offset 272: lights[16] ---
        int currentPos = uniformData.position();
        int targetSize = 784;
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
