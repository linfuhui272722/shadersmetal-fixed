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

    private ShaderRenderer() {}

    public static void render(Camera camera, float tickDelta) {
        if (!ShaderConfig.INSTANCE.enabled) return;
        if (!ShaderManager.isAvailable()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        long device = MetalBridge.getDeviceHandle();
        long cmdBuffer = MetalBridge.getCommandBufferHandle();
        if (device <= 0 || cmdBuffer <= 0) return;

        long colorSrc = MetalBridge.getMainColorTextureHandle();
        long depthSrc = MetalBridge.getMainDepthTextureHandle();
        if (colorSrc <= 0 || depthSrc <= 0) return;

        // Build / refresh the uniform buffer
        ByteBuffer uniformData = UniformBuffer.pack(camera, tickDelta, lastFrame + 1);
        if (uniformBufferHandle != 0L) {
            MetalNative.release(uniformBufferHandle);
        }
        // Direct ByteBuffers don't expose .array(); copy into a heap array.
        byte[] uniformBytes = new byte[uniformData.remaining()];
        uniformData.get(uniformBytes);
        uniformBufferHandle = MetalNative.createBuffer(device, uniformBytes,
                UniformBuffer.TOTAL_SIZE);
        if (uniformBufferHandle == 0L) {
            LOGGER.warn("Failed to upload uniform buffer; skipping frame.");
            return;
        }

        // 1. Composite (deferred lighting + fog + moving lights)
        long compositePipe = ShaderManager.getPipeline("composite");
        if (compositePipe != 0L) {
            MetalNative.dispatchFullscreen(cmdBuffer, compositePipe,
                    colorSrc, depthSrc, 0L, colorSrc,
                    uniformBufferHandle, UniformBuffer.TOTAL_SIZE);
        }

        // 2. Bloom (two-pass separable Gaussian)
        if (ShaderConfig.INSTANCE.bloom) {
            long bh = ShaderManager.getPipeline("bloom_h");
            long bv = ShaderManager.getPipeline("bloom_v");
            if (bh != 0L && bv != 0L) {
                for (int i = 0; i < ShaderConfig.INSTANCE.bloomPasses; i++) {
                    MetalNative.dispatchFullscreen(cmdBuffer, bh,
                            colorSrc, 0L, 0L, colorSrc,
                            uniformBufferHandle, UniformBuffer.TOTAL_SIZE);
                    MetalNative.dispatchFullscreen(cmdBuffer, bv,
                            colorSrc, 0L, 0L, colorSrc,
                            uniformBufferHandle, UniformBuffer.TOTAL_SIZE);
                }
            }
        }

        // 3. Tone map + saturation + vignette
        long tonemap = ShaderManager.getPipeline("tonemap");
        if (tonemap != 0L) {
            MetalNative.dispatchFullscreen(cmdBuffer, tonemap,
                    colorSrc, 0L, 0L, colorSrc,
                    uniformBufferHandle, UniformBuffer.TOTAL_SIZE);
        }

        lastFrame++;
    }

    public static void shutdown() {
        if (uniformBufferHandle != 0L) {
            MetalNative.release(uniformBufferHandle);
            uniformBufferHandle = 0L;
        }
    }
}
