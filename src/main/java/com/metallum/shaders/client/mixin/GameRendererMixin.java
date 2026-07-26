package com.metallum.shaders.client.mixin;

import com.metallum.shaders.shader.ShaderManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 当 GameRenderer 关闭时，也关闭我们的 Metal 管线。
 * MC 26.2 的 GameRenderer 没有 reload 方法，用 close 代替。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "close", at = @At("RETURN"))
    private void metallum_shaders$onClose(CallbackInfo ci) {
        // ShaderManager.reload() 是安全的，可重复调用。
        ShaderManager.reload();
    }
}
