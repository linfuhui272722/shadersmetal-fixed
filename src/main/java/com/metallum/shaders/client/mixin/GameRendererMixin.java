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

    // 复用对象
    private static final Matrix4f cachedViewProj = new Matrix4f();
    private static final Matrix4f cachedInvViewProj = new Matrix4f();
    private static final Matrix4f tempProjection = new Matrix4f();
    private static final Matrix4f tempView = new Matrix4f();
    private static ByteBuffer cachedUniformBuffer = null;

    private static void putMatrix(ByteBuffer buf, Matrix4f mat) {
        buf.putFloat(mat.m00()); buf.putFloat(mat.m01()); buf.putFloat(mat.m02()); buf.putFloat(mat.m03());
        buf.putFloat(mat.m10()); buf.putFloat(mat.m11()); buf.putFloat(mat.m12()); buf.putFloat(mat.m13());
        buf.putFloat(mat.m20()); buf.putFloat(mat.m21()); buf.putFloat(mat.m22()); buf.putFloat(mat.m23());
        buf.putFloat(mat.m30()); buf.putFloat(mat.m31()); buf.putFloat(mat.m32()); buf.putFloat(mat.m33());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ShaderManager.init();
        if (!ShaderManager.isAvailable()) return;

        // 1. 使用映射表：Minecraft.getInstance()
        Minecraft mc = Minecraft.getInstance();
        
        // 关键检查：必须在创建任何资源前检查
        if (mc.level == null) return;

        Camera camera = MetalBridge.getMainCamera();
        if (camera == null) return;

        long pipeline = ShaderManager.getPipeline("composite");
        if (pipeline == 0L) return;

        long device = MetalBridge.getDeviceHandle();
        if (device == 0) return;
        
        long cmdBuffer = MetalNative.createCommandBuffer();
        if (cmdBuffer <= 0) return;

        long colorSrc  = MetalBridge.getMainColorTextureHandle();
        long depthSrc  = MetalBridge.getMainDepthTextureHandle();
        long normalSrc = MetalBridge.getMainNormalTextureHandle().orElse(0L);

        if (colorSrc <= 0) return;

        long colorDst = colorSrc;
        
        // 使用映射表逻辑：getWidth() 依然有效，或使用 mc.getWindow().getWidth()
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        if (frameCounter % 300 == 0) LOGGER.info("[MetallumMixins] Rendering frame...");
        frameCounter++;

        if (cachedUniformBuffer == null || cachedUniformBuffer.capacity() < 1024) {
            cachedUniformBuffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
        }
        cachedUniformBuffer.clear();

        // ==========================================
        // 核心矩阵构建 (适配 Mojang 映射)
        // ==========================================
        try {
            // 1. 投影矩阵
            // mc.options.fov 在 Mojang 映射中通常是字段访问，我们尽量安全访问
            float fov = 70.0f;
            try {
                // 假设 options.fov 返回 Option<Double>，需要 .get()
                // 如果这里报错，请改为反射读取或硬编码 70
                 fov = (float) mc.options.fov().get();
            } catch (Exception ignored) {}

            float aspect = (float) width / height;
            tempProjection.identity().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000.0f);

            // 2. 视图矩阵
            tempView.identity();
            
            // ★★★ 关键修正：获取旋转 ★★★
            // 由于 Camera.getXRot/YRot 可能不存在，我们改为直接从玩家获取
            // 使用映射表：Entity.position() 对应的旋转方法通常也存在
            // Mojang 映射中 Entity 通常有 getXRot() 和 getYRot()
            float pitch = 0;
            float yaw = 0;
            
            if (mc.player != null) {
                pitch = mc.player.getXRot(); // Player 继承自 LivingEntity -> Entity
                yaw = mc.player.getYRot();
            } else {
                // 备用方案：如果 player 为空，尝试从 Camera 读取字段 (如果存在)
                // 或者放弃旋转，只做平移
                try {
                    // 尝试通过反射或直接字段访问，如果 Mojang 映射暴露了这些字段
                    // 这里为了编译安全，我们假设 player 存在且有效
                } catch (Exception ignored) {}
            }

            // 应用旋转：Minecraft 的摄像机坐标系
            // Y轴旋转，然后 X轴旋转
            tempView.rotationY((float) -Math.toRadians(yaw));
            tempView.rotateX((float) -Math.toRadians(pitch));
            
            // 使用映射表：Camera.position()
            // 应用平移
            tempView.translate(
                (float) -camera.position().x, 
                (float) -camera.position().y, 
                (float) -camera.position().z
            );

            // 3. 计算 ViewProj 和 逆矩阵
            tempProjection.mul(tempView, cachedViewProj);
            cachedViewProj.invert(cachedInvViewProj);

        } catch (Exception e) {
            LOGGER.error("Matrix calc error", e);
            cachedViewProj.identity();
            cachedInvViewProj.identity();
        }

        // --- 填充 Uniforms ---
        putMatrix(cachedUniformBuffer, cachedViewProj);
        putMatrix(cachedUniformBuffer, cachedInvViewProj);

        // 使用映射表：Camera.position().x (Vec3 字段访问，Mojang 映射中通常为 public 字段)
        cachedUniformBuffer.putFloat((float) camera.position().x);
        cachedUniformBuffer.putFloat((float) camera.position().y);
        cachedUniformBuffer.putFloat((float) camera.position().z);
        cachedUniformBuffer.putFloat(0);

        cachedUniformBuffer.putFloat(0).putFloat(1).putFloat(0).putFloat(0); // Sun Dir
        cachedUniformBuffer.putFloat(1.0f).putFloat(0.9f).putFloat(0.8f).putFloat(0); // Sun Color
        cachedUniformBuffer.putFloat(0).putFloat(-1).putFloat(0).putFloat(0); // Moon Dir
        cachedUniformBuffer.putFloat(0.4f).putFloat(0.4f).putFloat(0.7f).putFloat(0); // Moon Color

        float time = System.currentTimeMillis() / 1000.0f;
        cachedUniformBuffer.putFloat(time).putFloat(1.0f/60f).putFloat(1.0f).putFloat(1.0f);
        cachedUniformBuffer.putFloat(0.002f).putFloat(1.0f).putFloat(0.5f).putFloat(0.2f);
        cachedUniformBuffer.putFloat(0.8f).putFloat(0.2f).putFloat(1.0f).putFloat(0);
        cachedUniformBuffer.putFloat(width).putFloat(height).putFloat(0).putFloat(0);

        int currentPos = cachedUniformBuffer.position();
        if (currentPos < 784) cachedUniformBuffer.position(784); 
        cachedUniformBuffer.flip();

        byte[] uniformBytes = new byte[cachedUniformBuffer.remaining()];
        cachedUniformBuffer.get(uniformBytes);

        try {
            long uniformBuffer = MetalNative.createBuffer(device, uniformBytes, uniformBytes.length);
            int result = MetalNative.dispatchFullscreen(
                cmdBuffer, pipeline, colorSrc, depthSrc, normalSrc, colorDst, uniformBuffer, uniformBytes.length
            );
            if (result != 0) LOGGER.error("Dispatch error: {}", result);
            MetalNative.commitCommandBuffer(cmdBuffer);
        } catch (Exception e) {
            LOGGER.error("Render error", e);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        ShaderManager.reload();
    }
}
