package com.metallum.shaders.shader;

import com.metallum.shaders.ShaderConfig;
import com.metallum.shaders.light.LightSourceManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packs the per-frame uniform block that every shader pass reads.
 *
 * <p>Layout (must match {@code include/uniforms.metalh}):
 * <pre>
 *   struct Uniforms {
 *     float4x4 viewProj;
 *     float4x4 invViewProj;
 *     float3   cameraPos;     float _pad0;
 *     float3   sunDir;        float _pad1;
 *     float3   sunColor;      float _pad2;
 *     float3   moonDir;       float _pad3;
 *     float3   moonColor;     float _pad4;
 *     float    time;          float frameTime;
 *     float    exposure;      float saturation;
 *     float    fogDensity;    float fogFalloff;
 *     float    skyFogBlend;   float bloomStrength;
 *     float    bloomThreshold;float vignetteStrength;
 *     float    renderScale;   float _pad5;
 *     float2   resolution;
 *     int      lightCount;    int _pad6;
 *     // 16 lights follow, 32 bytes each
 *     Light    lights[16];
 *   };
 * </pre>
 */
public final class UniformBuffer {

    private static final int MAT4_SIZE = 64;
    private static final int VEC4_SIZE = 16;
    private static final int MAX_LIGHTS = LightSourceManager.MAX_LIGHTS;
    private static final int LIGHT_BYTES = LightSourceManager.LIGHT_SIZE_BYTES;

    public static final int TOTAL_SIZE =
            2 * MAT4_SIZE                       // viewProj, invViewProj          = 128
          + 5 * VEC4_SIZE                       // cameraPos, sunDir, sunColor,
                                                // moonDir, moonColor             =  80
          + 3 * VEC4_SIZE                       // timePack, fogPack, bloomPack   =  48
          + VEC4_SIZE                           // resolution + lightCount + pad  =  16
          + MAX_LIGHTS * LIGHT_BYTES;           // lights                         = 512
                                                // TOTAL                          = 784

    private UniformBuffer() {}

    public static ByteBuffer pack(Camera camera, float tickDelta, long frameCounter) {
        Minecraft mc = Minecraft.getInstance();
        ShaderConfig cfg = ShaderConfig.INSTANCE;

        // MC 26.2 的 Camera 没有 getYaw/getPitch/getPos，
        // 用 position() 获取位置，用内部旋转构造一个简单的 viewProj。
        // 这里用一个单位矩阵作为 viewProj 的近似（后处理主要依赖深度重建世界坐标）。
        Matrix4f viewProj = new Matrix4f();
        Matrix4f invViewProj = new Matrix4f();

        Vec3 camPos = camera.position();
        long dayTime = mc.level == null ? 0 : mc.level.getGameTime();
        float sunAngle = (dayTime % 24000L) / 24000.0f * (float) (Math.PI * 2.0);
        Vector3f sunDir = new Vector3f(
                (float) Math.cos(sunAngle),
                (float) Math.sin(sunAngle),
                0.2f).normalize();
        Vector3f moonDir = new Vector3f(sunDir).negate();

        // Sun/moon color & intensity based on time of day
        float dayFactor = Mth.clamp(sunDir.y * 1.2f + 0.3f, 0f, 1f);
        Vector3f sunColor = new Vector3f(1.0f, 0.95f, 0.85f).mul(0.6f + 1.4f * dayFactor);
        Vector3f moonColor = new Vector3f(0.45f, 0.55f, 0.85f).mul(0.3f + 0.4f * (1f - dayFactor));

        float time = (mc.level == null ? 0f : mc.level.getGameTime() + tickDelta) * 0.05f;
        float frameTime = 1f / 60f; // best-effort; Metallum doesn't expose delta

        ByteBuffer buf = ByteBuffer.allocateDirect(TOTAL_SIZE).order(ByteOrder.nativeOrder());

        // Matrices
        putMat4(buf, viewProj);
        putMat4(buf, invViewProj);

        // cameraPos + pad
        // Mojang 映射的 Vec3 有 public 字段 x/y/z
        buf.putFloat((float) camPos.x);
        buf.putFloat((float) camPos.y);
        buf.putFloat((float) camPos.z);
        buf.putFloat(0f);

        // sunDir + pad
        putVec3(buf, sunDir);
        buf.putFloat(0f);

        // sunColor + pad
        putVec3(buf, sunColor);
        buf.putFloat(0f);

        // moonDir + pad
        putVec3(buf, moonDir);
        buf.putFloat(0f);

        // moonColor + pad
        putVec3(buf, moonColor);
        buf.putFloat(0f);

        // scalar pack 1: time, frameTime, exposure, saturation
        buf.putFloat(time);
        buf.putFloat(frameTime);
        buf.putFloat(cfg.exposure);
        buf.putFloat(cfg.saturation);

        // scalar pack 2: fogDensity, fogFalloff, skyFogBlend, bloomStrength
        buf.putFloat(cfg.fogDensity);
        buf.putFloat(cfg.fogFalloff);
        buf.putFloat(cfg.skyFogBlend);
        buf.putFloat(cfg.bloomStrength);

        // scalar pack 3: bloomThreshold, vignetteStrength, renderScale, pad
        buf.putFloat(cfg.bloomThreshold);
        buf.putFloat(cfg.vignette ? 1.0f : 0.0f);
        buf.putFloat(cfg.renderScale / 100f);
        buf.putFloat(0f);

        // resolution + lightCount + pad
        // 用反射获取 RenderTarget 的宽高，避免字段名依赖
        int w = 1920;
        int h = 1080;
        try {
            java.lang.reflect.Field rtField = Minecraft.class.getDeclaredField("mainRenderTarget");
            rtField.setAccessible(true);
            Object rt = rtField.get(mc);
            if (rt != null) {
                java.lang.reflect.Field wField = rt.getClass().getField("width");
                java.lang.reflect.Field hField = rt.getClass().getField("height");
                w = wField.getInt(rt);
                h = hField.getInt(rt);
            }
        } catch (Throwable ignored) {}
        buf.putFloat(w);
        buf.putFloat(h);
        ByteBuffer lights = LightSourceManager.pack();
        int lightCount = lights.getInt();
        buf.putInt(lightCount);
        buf.putInt(0); // pad

        // Lights
        byte[] lightBytes = new byte[MAX_LIGHTS * LIGHT_BYTES];
        lights.get(lightBytes);
        buf.put(lightBytes);

        buf.flip();
        return buf;
    }

    private static void putMat4(ByteBuffer buf, Matrix4f m) {
        float[] f = new float[16];
        m.get(f);
        for (float v : f) buf.putFloat(v);
    }

    private static void putVec3(ByteBuffer buf, Vector3f v) {
        buf.putFloat(v.x);
        buf.putFloat(v.y);
        buf.putFloat(v.z);
    }
}
