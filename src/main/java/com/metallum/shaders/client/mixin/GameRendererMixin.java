package com.metallum.shaders.client.mixin;

import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.jni.MetalNative;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
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

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        
        // 1. 确保已初始化
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) {
            return;
        }

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        // 2. 获取 Metal 资源句柄
        long cmdBuffer = MetalBridge.getCurrentCommandBufferHandle();
        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        // 调试日志：打印关键句柄
        // 如果日志中没有这行，说明 Mixin 根本没运行
        LOGGER.info("[MetallumMixins] Frame tick - cmdBuffer: {}, colorSrc: {}, depthSrc: {}", cmdBuffer, colorSrc, depthSrc);

        if (cmdBuffer <= 0 || colorSrc <= 0) {
            LOGGER.warn("[MetallumMixins] Skipping frame due to invalid handles.");
            return;
        }

        long colorDst = colorSrc; 
        
        // 3. 准备 Uniform 数据
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        
        ByteBuffer uniformData = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        
        float time = System.currentTimeMillis() / 1000.0f;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        uniformData.putFloat(time);              
        uniformData.putFloat(width);             
        uniformData.putFloat(height);            
        
        // ★ 使用 MetalBridge.getMainCamera() 获取相机
        Camera camera = MetalBridge.getMainCamera();
        if (camera == null) {
            // 如果获取失败，填入默认值
            LOGGER.warn("[MetallumMixins] Camera is null!");
            uniformData.putFloat(0.0f);
            uniformData.putFloat(0.0f);
            uniformData.putFloat(0.0f);
        } else {
            uniformData.putFloat((float) camera.position().x);
            uniformData.putFloat((float) camera.position().y);
            uniformData.putFloat((float) camera.position().z);
        }
        
        uniformData.flip();

        byte[] uniformBytes = new byte[uniformData.remaining()];
        uniformData.get(uniformBytes);

        long device = MetalBridge.getDeviceHandle();
        long uniformBuffer = MetalNative.createBuffer(device, uniformBytes, uniformBytes.length);

        // 4. 执行绘制
        int result = MetalNative.dispatchFullscreen(
            cmdBuffer,
            pipeline,
            colorSrc,
            depthSrc,
            normalSrc,
            colorDst,
            uniformBuffer,
            uniformBytes.length
        );
        
        if (result != 0) {
             LOGGER.error("[MetallumMixins] dispatchFullscreen failed with code: {}", result);
        }
        
        // ★★★ 关键修复：提交命令缓冲区到 GPU ★★★
        // 只有提交后，GPU 才会真正执行着色器渲染
        MetalNative.commitCommandBuffer(cmdBuffer);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        ShaderManager.reload();
    }
}
