package com.metallum.shaders.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Metallum 用 Metal 替换了 GL 管线；这个 mixin 是一个空操作占位，
 * 保留以便将来拦截 GL 状态泄漏（例如 Sodium 把深度写回开），
 * 并在我们的全屏三角形绘制前重置它。
 *
 * <p>MC 26.2 的 RenderSystem 没有 flipFrame 方法，所以这里不注入任何方法，
 * 仅保留 Mixin 类以备将来使用。
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    // 目前为空，保留以备将来使用。
}
