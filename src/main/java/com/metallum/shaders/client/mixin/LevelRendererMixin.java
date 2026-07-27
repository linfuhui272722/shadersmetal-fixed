package com.metallum.shaders.client.mixin;

import com.metallum.shaders.MetallumShadersMod;
import com.metallum.shaders.client.MetallumShadersClient;
import com.metallum.shaders.render.ShaderRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code LevelRenderer#render} 返回前注入，运行我们的后处理链。
 *
 * <p>MC 26.2 的 LevelRenderer.render 方法签名与旧版不同，
 * 这里用 @At("RETURN") 注入到方法末尾。
 * Mixin 在编译时不检查方法签名，只在运行时匹配，
 * 如果签名不匹配，Mixin 会在运行时跳过（不会崩溃）。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    private static long lastRenderTime = 0;
    private static int skippedFrames = 0;

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$afterWorldRender(CallbackInfo ci) {
        // 轮询按键
        MetallumShadersClient.pollKeys();

        // 节流：每帧最多渲染一次，每16ms（约60fps）渲染一次
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRenderTime < 16) {
            skippedFrames++;
            return;
        }
        lastRenderTime = currentTime;

        // 运行延迟光照 + 后处理链。如果禁用或 Metallum 缺失则为 no-op。
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null) return;

        // 验证游戏状态
        if (!ShaderRenderer.isReady()) {
            if (skippedFrames % 60 == 0) {
                MetallumShadersMod.LOGGER.debug("[Metallum] Renderer not ready, skipping (skipped {} frames)", skippedFrames);
            }
            return;
        }

        // GameRenderer.mainCamera 是 private，用反射获取
        Camera camera = null;
        try {
            java.lang.reflect.Field f = mc.gameRenderer.getClass().getDeclaredField("mainCamera");
            f.setAccessible(true);
            camera = (Camera) f.get(mc.gameRenderer);
        } catch (Throwable t) {
            if (skippedFrames % 60 == 0) {
                MetallumShadersMod.LOGGER.warn("[Metallum] Could not get mainCamera", t);
            }
        }

        if (camera != null) {
            ShaderRenderer.render(camera, 0.0f);
        }
    }
}
