package com.metallum.shaders.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 当我们的着色器启用时，禁用原版的 PostChain，
 * 这样原版的 creeper.json / spider.json 等后处理不会和我们的 composite pass 冲突。
 *
 * <p>当用户关闭我们的着色器（F6）时，原版后处理恢复正常。
 */
@Mixin(PostChain.class)
public abstract class PostChainMixin {

    @Inject(method = "process", at = @At("HEAD"), cancellable = true)
    private void metallum_shaders$suppressVanillaPost(RenderTarget renderTarget, 
                                                       GraphicsResourceAllocator allocator, 
                                                       CallbackInfo ci) {
        if (com.metallum.shaders.ShaderConfig.INSTANCE.enabled
                && com.metallum.shaders.shader.ShaderManager.isAvailable()) {
            ci.cancel();
        }
    }
}
