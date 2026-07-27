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

    private static final Matrix4f cachedViewProj = new Matrix4f();
    private static final Matrix4f cachedInvViewProj = new Matrix4f();
    private static final Matrix4f tempProjection = new Matrix4f();
    private static final Matrix4f tempView = new Matrix4f();
    private static ByteBuffer cachedUniformBuffer = null;
    
    // ★★★ 双缓冲纹理：必须使用中间纹理，防止读写同一张纹理导致 Metal 崩溃 ★★★
    private static long cachedTempTextureHandle = -1L;
    private static int cachedWidth = 0;
    private static int cachedHeight = 0;

    private static void putMatrixSafe(ByteBuffer buf, Matrix4f mat) {
        boolean safe = true;
        float[] vals = new float[16];
        mat.get(vals);
        
        for (float v : vals) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                safe = false;
                break;
            }
        }
        
        if (!safe) {
            LOGGER.warn("[MetallumMixins] Invalid matrix detected, sending Identity.");
            mat.identity();
            mat.get(vals);
        }
        
        for (float v : vals) {
            buf.putFloat(v);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Camera camera = MetalBridge.getMainCamera();
        if (camera == null) return;

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        long device = MetalBridge.getDeviceHandle();
        if (device == 0) return;
        
        long cmdBuffer = MetalNative.createCommandBuffer();
        if (cmdBuffer <= 0) return;

        // 获取 Metal 原生句柄
        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (colorSrc <= 0) return;

        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        // ==========================================
        // 1. 准备中间纹理
        // ==========================================
        // 即使句柄是 Metal 的，也不能同时读写同一纹理。
        // 我们创建一个临时纹理作为 Shader 的输出。
        if (cachedTempTextureHandle <= 0 || cachedWidth != width || cachedHeight != height) {
            if (cachedTempTextureHandle > 0) {
                MetalNative.destroyTexture(cachedTempTextureHandle);
            }
            // 创建 RGBA8 纹理
            cachedTempTextureHandle = MetalNative.createTexture(device, width, height, 0);
            cachedWidth = width;
            cachedHeight = height;
            LOGGER.info("[Metallum] Created temp texture for resolving race condition: {}", cachedTempTextureHandle);
        }
        
        long colorDst = cachedTempTextureHandle;

        if (frameCounter % 300 == 0) LOGGER.info("[MetallumMixins] Rendering frame...");
        frameCounter++;

        if (cachedUniformBuffer == null || cachedUniformBuffer.capacity() < 2048) {
            cachedUniformBuffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder());
        }
        cachedUniformBuffer.clear();

        // ==========================================
        // 2. 矩阵计算 (关键修复)
        // ==========================================
        try {
            float fov = 70.0f;
            try { fov = (float) mc.options.fov().get(); } catch (Exception ignored) {}

            float aspect = (float) width / height;
            if (aspect <= 0) aspect = 1.0f;
            
            tempProjection.identity().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000.0f);

            // ★★★ 核心 Metal 修复：将 OpenGL 的 Z 范围 [-1, 1] 映射到 Metal 的 [0, 1] ★★★
            // 即使底层是 Metal，Minecraft 计算的投影矩阵依然是 OpenGL 标准的。
            float[] pVals = new float[16];
            tempProjection.get(pVals);
            
            // 映射公式：Z_metal = Z_gl * 0.5 + 0.5
            pVals[10] = pVals[10] * 0.5f;       // 缩放系数
            pVals[14] = pVals[14] * 0.5f + 0.5f; // 偏移
            
            tempProjection.set(pVals);

            // 视图矩阵
            tempView.identity();
            
            float pitch = 0, yaw = 0;
            if (mc.player != null) {
                pitch = mc.player.getXRot();
                yaw = mc.player.getYRot();
            }

            tempView.rotationY((float) -Math.toRadians(yaw));
            tempView.rotateX((float) -Math.toRadians(pitch));
            
            tempView.translate(
                (float) -camera.position().x, 
                (float) -camera.position().y, 
                (float) -camera.position().z
            );

            tempProjection.mul(tempView, cachedViewProj);
            cachedViewProj.invert(cachedInvViewProj);

        } catch (Exception e) {
            LOGGER.error("Matrix calc error", e);
            cachedViewProj.identity();
            cachedInvViewProj.identity();
        }

        // ==========================================
        // 3. 填充 Uniform Buffer
        // ==========================================
        // 1. viewProj (Offset 0)
        putMatrixSafe(cachedUniformBuffer, cachedViewProj);

        // 2. invViewProj (Offset 64)
        putMatrixSafe(cachedUniformBuffer, cachedInvViewProj);

        // 3. cameraPos (Offset 128)
        cachedUniformBuffer.putFloat((float) camera.position().x);
        cachedUniformBuffer.putFloat((float) camera.position().y);
        cachedUniformBuffer.putFloat((float) camera.position().z);
        cachedUniformBuffer.putFloat(0); // pad

        // 4. sunDir (Offset 144)
        cachedUniformBuffer.putFloat(0).putFloat(1).putFloat(0).putFloat(0);

        // 5. sunColor (Offset 160)
        cachedUniformBuffer.putFloat(1.0f).putFloat(0.9f).putFloat(0.8f).putFloat(0);

        // 6. moonDir (Offset 176)
        cachedUniformBuffer.putFloat(0).putFloat(-1).putFloat(0).putFloat(0);

        // 7. moonColor (Offset 192)
        cachedUniformBuffer.putFloat(0.4f).putFloat(0.4f).putFloat(0.7f).putFloat(0);

        // 8. timePack (Offset 208)
        float time = System.currentTimeMillis() / 1000.0f;
        cachedUniformBuffer.putFloat(time).putFloat(1.0f/60f).putFloat(1.0f).putFloat(1.0f);

        // 9. fogPack (Offset 224)
        cachedUniformBuffer.putFloat(0.002f).putFloat(1.0f).putFloat(0.5f).putFloat(0.2f);

        // 10. bloomPack (Offset 240)
        cachedUniformBuffer.putFloat(0.8f).putFloat(0.2f).putFloat(1.0f).putFloat(0);

        // 11. resolution (Offset 256)
        cachedUniformBuffer.putFloat(width).putFloat(height).putFloat(0).putFloat(0);

        cachedUniformBuffer.flip();

        byte[] uniformBytes = new byte[cachedUniformBuffer.remaining()];
        cachedUniformBuffer.get(uniformBytes);

        // ==========================================
        // 4. 渲染与回写
        // ==========================================
        try {
            long uniformBuffer = MetalNative.createBuffer(device, uniformBytes, uniformBytes.length);
            
            // 第一步：Shader 渲染到临时纹理
            int result = MetalNative.dispatchFullscreen(
                cmdBuffer, pipeline, colorSrc, depthSrc, normalSrc, colorDst, uniformBuffer, uniformBytes.length
            );
            
            if (result != 0) {
                LOGGER.error("Dispatch error: {}", result);
            }

            // 第二步：将处理好的纹理 Blit 回主纹理
            // 这一步是防止画面闪烁和卡死的关键
            MetalNative.blitTexture(cmdBuffer, colorDst, colorSrc, width, height);
            
            MetalNative.commitCommandBuffer(cmdBuffer);
        } catch (Exception e) {
            LOGGER.error("Render error", e);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        if (cachedTempTextureHandle > 0) {
            MetalNative.destroyTexture(cachedTempTextureHandle);
            cachedTempTextureHandle = -1;
        }
        ShaderManager.reload();
    }
}
