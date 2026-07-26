package com.metallum.shaders.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 窗口大小变化时的回调占位。
 * MC 26.2 用 resize 方法代替 onResolutionChanged。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "resize", at = @At("RETURN"))
    private void metallum_shaders$onResize(CallbackInfo ci) {
        // 窗口大小变化 —— 我们的管线与分辨率无关，
        // 这里暂不处理，保留以备将来使用。
    }
}
