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
    private static int frameCounter = 0;

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) return;

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        // ★★★ 修复：创建我们自己的命令缓冲区，不与游戏共用 ★★★
        long cmdBuffer = MetalNative.createCommandBuffer();
        
        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (cmdBuffer <= 0 || colorSrc <= 0) return;

        if (frameCounter % 60 == 0) {
             LOGGER.info("[MetallumMixins] Rendering frame...");
        }
        frameCounter++;

        long colorDst = colorSrc; 
        
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        
        ByteBuffer uniformData = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        uniformData.putFloat(System.currentTimeMillis() / 1000.0f);
        uniformData.putFloat(width);
        uniformData.putFloat(height);
        
        Camera camera = MetalBridge.getMainCamera();
        if (camera != null) {
            uniformData.putFloat((float) camera.position().x);
            uniformData.putFloat((float) camera.position().y);
            uniformData.putFloat((float) camera.position().z);
        } else {
            uniformData.putFloat(0).putFloat(0).putFloat(0);
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
