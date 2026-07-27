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

    // ★★★ 优化：复用 Matrix 对象和 ByteBuffer，避免 GC 压力 ★★★
    private static final Matrix4f cachedViewProj = new Matrix4f();
    private static final Matrix4f cachedInvViewProj = new Matrix4f();
    private static final Matrix4f tempProjection = new Matrix4f();
    private static final Matrix4f tempView = new Matrix4f();
    private static ByteBuffer cachedUniformBuffer = null; // 缓存 Buffer

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
        // ==========================================
        // 1. 前置检查 (防止内存泄露的关键！)
        // ==========================================
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) return;

        Minecraft mc = Minecraft.getInstance();
        
        // ★★★ 关键修复：如果世界未加载，直接返回。
        // 必须在调用任何 MetalNative 方法之前检查！
        // 否则 createCommandBuffer 会创建对象但无法释放，导致泄露卡死。
        if (mc.level == null) return;

        Camera camera = MetalBridge.getMainCamera();
        if (camera == null) return;

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        long device = MetalBridge.getDeviceHandle();
        if (device == 0) return;

        // ==========================================
        // 2. 准备资源 (确保需要渲染时才分配)
        // ==========================================
        long cmdBuffer = MetalNative.createCommandBuffer();
        if (cmdBuffer <= 0) return;

        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (colorSrc <= 0) return;

        long colorDst = colorSrc;
        
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        if (frameCounter % 300 == 0) {
             LOGGER.info("[MetallumMixins] Rendering frame...");
        }
        frameCounter++;

        // ==========================================
        // 3. 构建 Uniform 数据 (复用 Buffer)
        // ==========================================
        if (cachedUniformBuffer == null || cachedUniformBuffer.capacity() < 1024) {
            cachedUniformBuffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
        }
        cachedUniformBuffer.clear(); // 重置指针

        // --- 获取矩阵 ---
        try {
            float fov = 70.0f; 
            float aspect = (float) width / height;
            float near = 0.05f;
            float far = 1000.0f;
            
            tempProjection.identity().perspective((float) Math.toRadians(fov), aspect, near, far);
            tempView.identity().translation(
                -(float)camera.position().x, 
                -(float)camera.position().y, 
                -(float)camera.position().z
            );
            
            tempProjection.mul(tempView, cachedViewProj);
            cachedViewProj.invert(cachedInvViewProj);
            
        } catch (Exception e) {
            cachedViewProj.identity();
            cachedInvViewProj.identity();
        }

        putMatrix(cachedUniformBuffer, cachedViewProj);
        putMatrix(cachedUniformBuffer, cachedInvViewProj);

        cachedUniformBuffer.putFloat((float) camera.position().x);
        cachedUniformBuffer.putFloat((float) camera.position().y);
        cachedUniformBuffer.putFloat((float) camera.position().z);
        cachedUniformBuffer.putFloat(0);

        cachedUniformBuffer.putFloat(0).putFloat(1).putFloat(0).putFloat(0);
        cachedUniformBuffer.putFloat(1.0f).putFloat(0.9f).putFloat(0.8f).putFloat(0);
        cachedUniformBuffer.putFloat(0).putFloat(-1).putFloat(0).putFloat(0);
        cachedUniformBuffer.putFloat(0.4f).putFloat(0.4f).putFloat(0.7f).putFloat(0);

        float time = System.currentTimeMillis() / 1000.0f;
        cachedUniformBuffer.putFloat(time);
        cachedUniformBuffer.putFloat(1.0f/60f);
        cachedUniformBuffer.putFloat(1.0f);
        cachedUniformBuffer.putFloat(1.0f);

        cachedUniformBuffer.putFloat(0.002f);
        cachedUniformBuffer.putFloat(1.0f);
        cachedUniformBuffer.putFloat(0.5f);
        cachedUniformBuffer.putFloat(0.2f);

        cachedUniformBuffer.putFloat(0.8f);
        cachedUniformBuffer.putFloat(0.2f);
        cachedUniformBuffer.putFloat(1.0f);
        cachedUniformBuffer.putFloat(0);

        cachedUniformBuffer.putFloat(width);
        cachedUniformBuffer.putFloat(height);
        cachedUniformBuffer.putFloat(0);
        cachedUniformBuffer.putFloat(0);

        int currentPos = cachedUniformBuffer.position();
        int targetSize = 784;
        if (currentPos < targetSize) {
            cachedUniformBuffer.position(targetSize); // 填充到目标大小
        }

        cachedUniformBuffer.flip();
        byte[] uniformBytes = new byte[cachedUniformBuffer.remaining()];
        cachedUniformBuffer.get(uniformBytes);

        // ==========================================
        // 4. 提交渲染
        // ==========================================
        try {
            long uniformBuffer = MetalNative.createBuffer(device, uniformBytes, uniformBytes.length);
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
