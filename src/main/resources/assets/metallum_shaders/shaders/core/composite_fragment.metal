//
// composite_fragment.metal
// MetallumShaders
//
// The big one. Reads the g-buffer (color + depth) that Metallum/Sodium
// produced, reconstructs world position, applies:
//   1. Sun + moon directional lighting (MakeUp-UltraFast style)
//   2. Up to MAX_LIGHTS moving point lights (held torches, dropped
//      glowstone, lava, etc.) with torch-flicker noise
//   3. Volumetric exponential fog blended toward the sky color
//   4. Subtle ambient occlusion approximation from depth derivatives
//
// Output is written back into the same color attachment so the next
// pass (bloom) can sample it.
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
#include "include/common.metalh"
using namespace metal;

fragment float4 composite_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    depth2d<float, access::sample>   depthTex [[texture(1)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]]
) {
    float2 uv = in.uv;

    float4 albedo = colorTex.sample(smp, uv);
    float  depth  = depthTex.sample(smp, uv);

    // Sky pixels — depth == 1.0 — pass through with a slight tint.
    if (depth >= 0.9999) {
        float3 sky = mix(albedo.rgb, albedo.rgb * u.sunColor.rgb * 0.5, 0.15);
        return float4(sky, albedo.a);
    }

    float3 worldPos = worldPosFromDepth(uv, depth, u.invViewProj);
    float3 viewDir  = normalize(u.cameraPos.xyz - worldPos);

    // ---- Reconstruct a cheap normal from depth derivatives ----
    // This is the same trick MakeUp-UltraFast uses when no normal buffer
    // is available: sample the 4-neighbour depth, rebuild world positions,
    // cross the resulting tangents.
    float2 texel = 1.0 / u.resolution.xy;
    float dR = depthTex.sample(smp, uv + float2( texel.x, 0.0));
    float dL = depthTex.sample(smp, uv + float2(-texel.x, 0.0));
    float dU = depthTex.sample(smp, uv + float2(0.0,  texel.y));
    float dD = depthTex.sample(smp, uv + float2(0.0, -texel.y));
    float3 pR = worldPosFromDepth(uv + float2( texel.x, 0.0), dR, u.invViewProj);
    float3 pL = worldPosFromDepth(uv + float2(-texel.x, 0.0), dL, u.invViewProj);
    float3 pU = worldPosFromDepth(uv + float2(0.0,  texel.y), dU, u.invViewProj);
    float3 pD = worldPosFromDepth(uv + float2(0.0, -texel.y), dD, u.invViewProj);
    float3 normal = normalize(cross(pR - pU, pU - pL) + cross(pU - pL, pL - pD));
    if (dot(normal, viewDir) < 0.0) normal = -normal;

    // ---- 1. Sun + moon directional light ----
    float sunLambert = max(0.0, dot(normal, u.sunDir.xyz));
    float moonLambert = max(0.0, dot(normal, u.moonDir.xyz));
    float3 sunContribution  = u.sunColor.rgb  * sunLambert  * 1.15;
    float3 moonContribution = u.moonColor.rgb * moonLambert * 0.35;

    // Soft sky ambient — hemisphere lighting.
    float skyFactor = normal.y * 0.5 + 0.5;
    float3 skyAmbient = mix(float3(0.18, 0.20, 0.27),
                            float3(0.55, 0.62, 0.75), skyFactor) * 0.35;

    float3 lit = albedo.rgb * (sunContribution + moonContribution + skyAmbient);

    // ---- 2. Moving point lights ----
    int lightCount = int(u.resolution.z);
    for (int i = 0; i < MAX_LIGHTS; i++) {
        if (i >= lightCount) break;
        Light L = u.lights[i];

        float3 toLight = L.positionAndRadius.xyz - worldPos;
        float dist = length(toLight);
        if (dist > L.positionAndRadius.w) continue;

        float3 Ldir = toLight / max(dist, 1e-4);

        // Torch flicker — two octaves of value noise.
        float flicker = 1.0;
        if (L.colorAndIntensity.r > L.colorAndIntensity.g * 1.3) {
            // Warm light — assume torch-style, apply flicker.
            flicker = 1.0 + (noise1D(u.timePack.x * 7.0 + float(i) * 13.0) - 0.5) * 0.18;
            flicker *= 1.0 + (noise1D(u.timePack.x * 23.0 + float(i) * 7.0) - 0.5) * 0.08;
        }

        float wrap = wrapLight(normal, Ldir, 0.35);
        float att  = lightAttenuation(worldPos, L.positionAndRadius.xyz,
                                      L.positionAndRadius.w);
        float intensity = L.colorAndIntensity.a * flicker * att * wrap;
        lit += albedo.rgb * L.colorAndIntensity.rgb * intensity;
    }

    // ---- 3. Volumetric fog ----
    float distToCam = length(u.cameraPos.xyz - worldPos);
    float fogFactor = 1.0 - exp(-u.fogPack.x * pow(distToCam, u.fogPack.y));
    fogFactor = clamp(fogFactor, 0.0, 1.0);
    float3 fogColor = mix(u.sunColor.rgb * 0.45, u.moonColor.rgb * 0.55,
                          smoothstep(0.0, 0.5, u.sunDir.y));
    lit = mix(lit, fogColor, fogFactor * u.fogPack.z);

    // ---- 4. Subtle depth-based AO ----
    float ao = 1.0 - smoothstep(0.0, 0.6,
        abs(dR - depth) + abs(dL - depth) + abs(dU - depth) + abs(dD - depth));
    lit *= mix(0.85, 1.0, ao);

    return float4(lit, albedo.a);
}
