package com.metallum.shaders.client.mixin;

import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.jni.MetalNative;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /**
     * 核心渲染注入点。
     * 在 Minecraft 渲染完一帧后，立即调用 Metal Shader 进行后处理。
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        
        // 1. 确保已初始化
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) {
            return;
        }

        // 2. 获取管线 (Shader)
        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        // 3. 获取 Metal 资源句柄
        long cmdBuffer = MetalBridge.getCurrentCommandBufferHandle();
        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (cmdBuffer == 0 || colorSrc == 0) {
            return;
        }

        long colorDst = colorSrc; 
        
        // 4. 准备 Uniform 数据
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        
        ByteBuffer uniformData = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        
        float time = System.currentTimeMillis() / 1000.0f;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        uniformData.putFloat(time);              
        uniformData.putFloat(width);             
        uniformData.putFloat(height);            
        
        // ★ 修改点：直接使用 GameRenderer 的 camera 字段，避免访问 Minecraft 的 camera
        Camera camera = this.camera;  // this 会被混入到 GameRenderer 实例中
        if (camera != null) {
            uniformData.putFloat((float) camera.position().x);
            uniformData.putFloat((float) camera.position().y);
            uniformData.putFloat((float) camera.position().z);
        } else {
            // 如果 camera 为空，填充默认值
            uniformData.putFloat(0.0f);
            uniformData.putFloat(0.0f);
            uniformData.putFloat(0.0f);
        }
        
        uniformData.flip();

        long device = MetalBridge.getDeviceHandle();
        long uniformBuffer = MetalNative.createBuffer(device, uniformData.array(), uniformData.remaining());

        // 5. 执行绘制
        int result = MetalNative.dispatchFullscreen(
            cmdBuffer,
            pipeline,
            colorSrc,
            depthSrc,
            normalSrc,
            colorDst,
            uniformBuffer,
            uniformData.remaining()
        );
        
        if (result != 0) {
             System.err.println("[MetallumShaders] dispatchFullscreen failed with code: " + result);
        }
    }

    /**
     * 关闭时的清理逻辑
     */
    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        ShaderManager.reload();
    }
}
