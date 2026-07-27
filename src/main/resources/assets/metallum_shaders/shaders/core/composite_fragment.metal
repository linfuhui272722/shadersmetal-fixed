//
//  composite_fragment.metal
//  MetallumShaders
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
    sampler smp [[sampler(0)]] // <--- 修正：这里必须是双括号 ]]
) {
    // 修复：翻转 UV，因为 Minecraft 传递的是 OpenGL 纹理坐标系
    float2 uv = in.uv;
    uv.y = 1.0 - uv.y;

    float4 albedo = colorTex.sample(smp, uv);
    float  depth  = depthTex.sample(smp, uv);

    // Sky detection
    if (depth >= 0.9999) {
        return float4(albedo.rgb * 1.2, albedo.a);
    }

    float3 worldPos = worldPosFromDepth(uv, depth, u.invViewProj);
    float3 viewDir  = normalize(u.cameraPos.xyz - worldPos);

    // ---- Normal Reconstruction ----
    float2 texel = 1.0 / u.resolution.xy;
    
    float dR = depthTex.sample(smp, uv + float2( texel.x, 0.0));
    float dL = depthTex.sample(smp, uv + float2(-texel.x, 0.0));
    float dU = depthTex.sample(smp, uv + float2(0.0,  texel.y));
    float dD = depthTex.sample(smp, uv + float2(0.0, -texel.y));
    
    float3 pR = worldPosFromDepth(uv + float2( texel.x, 0.0), dR, u.invViewProj);
    float3 pL = worldPosFromDepth(uv + float2(-texel.x, 0.0), dL, u.invViewProj);
    float3 pU = worldPosFromDepth(uv + float2(0.0,  texel.y), dU, u.invViewProj);
    float3 pD = worldPosFromDepth(uv + float2(0.0, -texel.y), dD, u.invViewProj);

    float3 rawNormal = cross(pR - pU, pU - pL) + cross(pU - pL, pL - pD);
    float len = length(rawNormal);
    
    float3 normal;
    
    // ★★★ 关键修复：防止 normalize(0) 导致 GPU 挂死 ★★★
    if (len < 0.00001 || isnan(len)) {
        normal = float3(0.0, 1.0, 0.0); // 默认向上
    } else {
        normal = rawNormal / len;
    }

    if (dot(normal, viewDir) < 0.0) normal = -normal;

    // ---- Lighting ----
    float sunLambert = max(0.0, dot(normal, u.sunDir.xyz));
    float moonLambert = max(0.0, dot(normal, u.moonDir.xyz));
    
    float3 sunContribution  = u.sunColor.rgb  * sunLambert  * 1.15;
    float3 moonContribution = u.moonColor.rgb * moonLambert * 0.35;

    float skyFactor = normal.y * 0.5 + 0.5;
    float3 skyAmbient = mix(float3(0.18, 0.20, 0.27),
                            float3(0.55, 0.62, 0.75), skyFactor) * 0.35;

    float3 lit = albedo.rgb * (sunContribution + moonContribution + skyAmbient);

    // ---- Fog ----
    float distToCam = length(u.cameraPos.xyz - worldPos);
    float fogFactor = 1.0 - exp(-u.fogPack.x * pow(distToCam, u.fogPack.y));
    fogFactor = clamp(fogFactor, 0.0, 1.0);
    float3 fogColor = mix(u.sunColor.rgb * 0.45, u.moonColor.rgb * 0.55, smoothstep(0.0, 0.5, u.sunDir.y));
    lit = mix(lit, fogColor, fogFactor * u.fogPack.z);

    return float4(lit, albedo.a);
}
