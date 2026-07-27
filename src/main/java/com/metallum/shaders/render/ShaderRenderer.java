package com.metallum.shaders.render;

import com.metallum.shaders.ShaderConfig;
import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.jni.MetalNative;
import com.metallum.shaders.shader.ShaderManager;
import com.metallum.shaders.shader.UniformBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Orchestrates the per-frame post-processing chain.
 *
 * <p>Flow:
 * <pre>
 *   Metallum g-buffer (color + depth)
 *        │
 *        ▼
 *   composite pass  ──► intermediate A
 *        │
 *        ▼
 *   bloom_h ──► intermediate B
 *        │
 *        ▼
 *   bloom_v ──► intermediate A
 *        │
 *        ▼
 *   tonemap ──► main framebuffer
 * </pre>
 */
public final class ShaderRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Renderer");

    private static long uniformBufferHandle = 0L;
    private static long lastFrame = -1L;
    private static boolean initialized = false;
    private static int errorCount = 0;
    private static final int MAX_ERRORS_BEFORE_DISABLE = 10;

    private ShaderRenderer() {}

    /**
     * 检查渲染器是否准备好执行。
     */
    public static boolean isReady() {
        if (!initialized) {
            initialized = ShaderManager.isAvailable();
        }
        return initialized && ShaderConfig.INSTANCE.enabled;
    }

    public static void render(Camera camera, float tickDelta) {
        // 快速检查
        if (!ShaderConfig.INSTANCE.enabled) return;
        if (!ShaderManager.isAvailable()) {
            initialized = false;
            return;
        }
        initialized = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        long device = MetalBridge.getDeviceHandle();
        long cmdBuffer = MetalBridge.getCommandBufferHandle();
        if (device <= 0 || cmdBuffer <= 0) {
            if (errorCount++ % 60 == 0) {
                LOGGER.warn("[ShaderRenderer] Invalid device ({}) or command buffer ({})", device, cmdBuffer);
            }
            return;
        }

        long colorSrc = MetalBridge.getMainColorTextureHandle();
        long depthSrc = MetalBridge.getMainDepthTextureHandle();
        if (colorSrc <= 0 || depthSrc <= 0) {
            if (errorCount++ % 60 == 0) {
                LOGGER.warn("[ShaderRenderer] Invalid texture handles: color={}, depth={}", colorSrc, depthSrc);
            }
            return;
        }

        errorCount = 0; // 重置错误计数

        // Build / refresh the uniform buffer
        ByteBuffer uniformData = UniformBuffer.pack(camera, tickDelta, lastFrame + 1);
        byte[] uniformBytes = new byte[uniformData.remaining()];
        uniformData.get(uniformBytes);
        
        long bufHandle = MetalNative.createBuffer(device, uniformBytes, UniformBuffer.TOTAL_SIZE);
        if (bufHandle == 0L) {
            LOGGER.warn("Failed to create uniform buffer; skipping frame.");
            return;
        }

        try {
            // 1. Composite (deferred lighting + fog + moving lights)
            long compositePipe = ShaderManager.getPipeline("composite");
            if (compositePipe != 0L) {
                int result = MetalNative.dispatchFullscreen(cmdBuffer, compositePipe,
                        colorSrc, depthSrc, 0L, colorSrc,
                        bufHandle, UniformBuffer.TOTAL_SIZE);
                if (result != 0 && errorCount++ % 60 == 0) {
                    LOGGER.warn("[ShaderRenderer] Composite dispatch returned error: {}", result);
                }
            }

            // 2. Bloom (two-pass separable Gaussian)
            if (ShaderConfig.INSTANCE.bloom) {
                long bh = ShaderManager.getPipeline("bloom_h");
                long bv = ShaderManager.getPipeline("bloom_v");
                if (bh != 0L && bv != 0L) {
                    for (int i = 0; i < ShaderConfig.INSTANCE.bloomPasses; i++) {
                        MetalNative.dispatchFullscreen(cmdBuffer, bh,
                                colorSrc, 0L, 0L, colorSrc,
                                bufHandle, UniformBuffer.TOTAL_SIZE);
                        MetalNative.dispatchFullscreen(cmdBuffer, bv,
                                colorSrc, 0L, 0L, colorSrc,
                                bufHandle, UniformBuffer.TOTAL_SIZE);
                    }
                }
            }

            // 3. Tone map + saturation + vignette
            long tonemap = ShaderManager.getPipeline("tonemap");
            if (tonemap != 0L) {
                MetalNative.dispatchFullscreen(cmdBuffer, tonemap,
                        colorSrc, 0L, 0L, colorSrc,
                        bufHandle, UniformBuffer.TOTAL_SIZE);
            }

            // 提交命令缓冲区
            MetalNative.commitCommandBuffer(cmdBuffer);
            lastFrame++;

        } catch (Exception e) {
            LOGGER.error("[ShaderRenderer] Exception during render", e);
            if (++errorCount > MAX_ERRORS_BEFORE_DISABLE) {
                LOGGER.error("[ShaderRenderer] Too many errors, temporarily disabling shaders");
                ShaderConfig.INSTANCE.enabled = false;
            }
        } finally {
            // 清理临时缓冲区（但不清理统一缓冲区句柄）
            if (bufHandle != 0L && bufHandle != uniformBufferHandle) {
                MetalNative.releaseBuffer(bufHandle);
            }
        }
    }

    public static void shutdown() {
        initialized = false;
        if (uniformBufferHandle != 0L) {
            MetalNative.release(uniformBufferHandle);
            uniformBufferHandle = 0L;
        }
        lastFrame = -1L;
        errorCount = 0;
    }
}
