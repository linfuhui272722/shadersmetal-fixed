//
//  composite_fragment.metal
//  MetallumShaders
//
//  修复版：移除重复定义，保留安全检查
//

#include <metal_stdlib>
#include "include/uniforms.metalh" // 确保引用现有头文件
using namespace metal;

// 安全的深度反投影辅助函数
static float3 safeWorldPosFromDepth(float2 uv, float depth, float4x4 invVP) {
    float4 clipPos = float4(uv * 2.0 - 1.0, depth, 1.0);
    float4 worldPos = invVP * clipPos;
    // 防止除零
    if (worldPos.w == 0.0) return float3(0.0);
    return worldPos.xyz / worldPos.w;
}

fragment float4 composite_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    depth2d<float, access::sample>   depthTex [[texture(1)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]])
{
    // 1. UV 修正
    float2 uv = in.uv;
    uv.y = 1.0 - uv.y; // 修正 Y 轴

    float4 albedo = colorTex.sample(smp, uv);
    float  depth  = depthTex.sample(smp, uv);

    // 天空检测
    if (depth >= 0.9999 || depth <= 0.0001) {
        return float4(albedo.rgb * 1.1, albedo.a);
    }

    // 2. 安全的世界坐标计算
    float3 worldPos = safeWorldPosFromDepth(uv, depth, u.invViewProj);

    // ★★★ 诊断核心：如果计算出错，返回亮粉色 ★★★
    // 如果你看到粉色，说明 Java 端传来的矩阵数据有误。
    if (any(isnan(worldPos)) || any(isinf(worldPos))) {
        return float4(1.0, 0.0, 1.0, 1.0); // 亮粉色
    }

    float3 diff = u.cameraPos.xyz - worldPos;
    float viewDist = length(diff);
    
    if (viewDist < 0.001) {
        return float4(albedo.rgb, albedo.a);
    }
    float3 viewDir = diff / viewDist;

    // 3. 法线重建
    float2 texel = 1.0 / u.resolution.xy;
    
    float dR = depthTex.sample(smp, uv + float2( texel.x, 0.0));
    float dL = depthTex.sample(smp, uv + float2(-texel.x, 0.0));
    float dU = depthTex.sample(smp, uv + float2(0.0,  texel.y));
    float dD = depthTex.sample(smp, uv + float2(0.0, -texel.y));
    
    float3 pR = safeWorldPosFromDepth(uv + float2( texel.x, 0.0), dR, u.invViewProj);
    float3 pL = safeWorldPosFromDepth(uv + float2(-texel.x, 0.0), dL, u.invViewProj);
    float3 pU = safeWorldPosFromDepth(uv + float2(0.0,  texel.y), dU, u.invViewProj);
    float3 pD = safeWorldPosFromDepth(uv + float2(0.0, -texel.y), dD, u.invViewProj);

    float3 rawNormal = cross(pR - pU, pU - pL) + cross(pU - pL, pL - pD);
    float len = length(rawNormal);
    
    float3 normal;
    if (len < 0.00001 || isnan(len)) {
        normal = float3(0.0, 1.0, 0.0);
    } else {
        normal = rawNormal / len;
    }

    if (dot(normal, viewDir) < 0.0) normal = -normal;

    // 4. 光照计算 (完整逻辑)
    float sunLambert = max(0.0, dot(normal, u.sunDir.xyz));
    float moonLambert = max(0.0, dot(normal, u.moonDir.xyz));
    
    float3 sunContribution  = u.sunColor.rgb  * sunLambert  * 1.15;
    float3 moonContribution = u.moonColor.rgb * moonLambert * 0.35;

    float skyFactor = normal.y * 0.5 + 0.5;
    float3 skyAmbient = mix(float3(0.18, 0.20, 0.27),
                            float3(0.55, 0.62, 0.75), skyFactor) * 0.35;

    float3 lit = albedo.rgb * (sunContribution + moonContribution + skyAmbient);

    // 5. 雾效 (完整逻辑)
    float fogFactor = 1.0 - exp(-u.fogPack.x * pow(viewDist, u.fogPack.y));
    fogFactor = clamp(fogFactor, 0.0, 1.0);
    float3 fogColor = mix(u.sunColor.rgb * 0.45, u.moonColor.rgb * 0.55, smoothstep(0.0, 0.5, u.sunDir.y));
    lit = mix(lit, fogColor, fogFactor * u.fogPack.z);

    return float4(lit, albedo.a);
}
