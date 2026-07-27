package com.metallum.shaders.client.mixin;

import com.metallum.shaders.MetallumShadersMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer mixin - 主要用于初始化和调试。
 * 实际的着色器渲染在 LevelRendererMixin 中进行，避免双重渲染。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    private static int frameCounter = 0;

    @Inject(method = "render", at = @At("RETURN"))
    private void metallum_shaders$postRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // 移除渲染逻辑，避免与 LevelRendererMixin 冲突
        // 调试日志：每300帧打印一次
        frameCounter++;
        if (frameCounter % 300 == 0) {
            MetallumShadersMod.LOGGER.info("[MetallumMixins] Frame tick: {}", frameCounter);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        com.metallum.shaders.shader.ShaderManager.shutdown();
    }
}
