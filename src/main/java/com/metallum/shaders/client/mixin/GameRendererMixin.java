package com.metallum.shaders.client.mixin;

import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.jni.MetalNative;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
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
    private void metallum_shaders$postRender(float partialTick, long nanoTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        
        // 1. 确保已初始化
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) {
            return;
        }

        // 2. 获取管线 (Shader)
        // 你可以根据需要切换管线，例如 "composite", "tonemap", "bloom_h", "bloom_v"
        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        // 3. 获取 Metal 资源句柄 (通过你写的 MetalBridge)
        long cmdBuffer = MetalBridge.getCurrentCommandBufferHandle();
        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        // 基础检查：如果命令缓冲区或输入纹理无效，跳过
        if (cmdBuffer == 0 || colorSrc == 0) {
            return;
        }

        // 4. 准备输出目标 (Output Texture)
        // 注意：这里你需要使用你已经准备好的输出纹理句柄。
        // 如果你是"就地处理"（直接覆盖原纹理），就用 colorSrc。
        // 如果你创建了专门的输出纹理（比如通过 ShaderRenderer），请替换这里的变量。
        long colorDst = colorSrc; 
        
        // 5. 准备 Uniform 数据 (如果需要)
        // 这里是一个最基本的示例，如果你有专门的 Uniform 管理类，请替换这段逻辑。
        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();
        
        // 分配并填充 Uniform Buffer (根据你的 Shader 定义调整)
        // 假设 Shader 需要: time, screenWidth, screenHeight, cameraPos
        ByteBuffer uniformData = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        
        float time = System.currentTimeMillis() / 1000.0f;
        uniformData.putFloat(time);              // Offset 0: Time
        uniformData.putFloat(width);             // Offset 4: Screen Width
        uniformData.putFloat(height);            // Offset 8: Screen Height
        uniformData.putFloat((float)camera.getPosition().x); // Offset 12: Cam X
        uniformData.putFloat((float)camera.getPosition().y); // Offset 16: Cam Y
        uniformData.putFloat((float)camera.getPosition().z); // Offset 20: Cam Z
        
        uniformData.flip(); // 准备读取

        // 在 Metal 上创建 Uniform Buffer
        long device = MetalBridge.getDeviceHandle();
        long uniformBuffer = MetalNative.createBuffer(device, uniformData.array(), uniformData.remaining());

        // 6. 执行绘制 (Dispatch)
        // 这里就是真正的"扣动扳机"
        int result = MetalNative.dispatchFullscreen(
            cmdBuffer,       // 命令缓冲区
            pipeline,        // Shader 管线
            colorSrc,        // 输入：原版画面颜色
            depthSrc,        // 输入：深度信息
            normalSrc,       // 输入：法线信息
            colorDst,        // 输出：目标纹理
            uniformBuffer,   // Uniform Buffer 指针
            uniformData.remaining() // Uniform 大小
        );
        
        // 调试日志
        if (result != 0) {
             System.err.println("[MetallumShaders] dispatchFullscreen failed with code: " + result);
        }

        // 7. 清理临时 Buffer (可选，取决于 GC 策略，通常 Native 资源需要手动释放)
        // 如果你的 Shader 逻辑需要保留 Buffer，请不要释放它。
        // MetalNative.release(uniformBuffer);
    }

    /**
     * 关闭时的清理逻辑保持不变
     */
    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        ShaderManager.reload();
    }
}
