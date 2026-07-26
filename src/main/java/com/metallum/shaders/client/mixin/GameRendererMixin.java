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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    // 懒加载缓存，避免类加载时崩溃
    private static Field cameraField;
    private static boolean cameraFieldInitialized = false;

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
        
        // ★ 通过懒加载反射获取 Camera
        Camera camera = getCamera();
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
     * 懒加载反射获取 Minecraft.camera 字段
     */
    private Camera getCamera() {
        if (!cameraFieldInitialized) {
            synchronized (GameRendererMixin.class) {
                if (!cameraFieldInitialized) {
                    try {
                        // 尝试通过反射获取 Minecraft.camera 字段
                        cameraField = Minecraft.class.getDeclaredField("camera");
                        cameraField.setAccessible(true);
                    } catch (NoSuchFieldException e) {
                        System.err.println("[MetallumShaders] Failed to find camera field in Minecraft: " + e.getMessage());
                        // 可尝试备选字段名
                        try {
                            cameraField = Minecraft.class.getDeclaredField("field_175622"); // 中间映射备选
                            cameraField.setAccessible(true);
                            System.err.println("[MetallumShaders] Found field via fallback name 'field_175622'");
                        } catch (NoSuchFieldException ex) {
                            System.err.println("[MetallumShaders] Fallback failed too: " + ex.getMessage());
                        }
                    }
                    cameraFieldInitialized = true;
                }
            }
        }

        if (cameraField == null) {
            return null;
        }
        try {
            return (Camera) cameraField.get(Minecraft.getInstance());
        } catch (IllegalAccessException e) {
            System.err.println("[MetallumShaders] Failed to get camera via reflection: " + e.getMessage());
            return null;
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
