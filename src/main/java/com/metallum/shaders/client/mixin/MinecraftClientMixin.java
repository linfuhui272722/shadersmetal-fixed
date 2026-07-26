package com.metallum.shaders.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 窗口大小变化时的回调占位。
 * MC 26.2 (1.21) 中 Minecraft 类的 resize 方法已移除，改名为 resizeDisplay。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    // 修改点：
    // 1. method = "resize" 改为 "resizeDisplay"
    // 2. 移除了方法参数中的 int width, int height (resizeDisplay 方法本身没有这两个参数)
    @Inject(method = "resizeDisplay", at = @At("RETURN"))
    private void metallum_shaders$onResize(CallbackInfo ci) {
        // 窗口大小变化 —— 我们的管线与分辨率无关，
        // 这里暂不处理，保留以备将来使用。
        
        // 注意：如果您后续需要获取具体的宽度和高度，
        // 可以通过 Minecraft.getInstance().getWindow().getWidth() / getHeight() 获取。
    }
}
