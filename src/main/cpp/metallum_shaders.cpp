//
//  composite_fragment.metal
//  诊断版本：只做颜色采样，无数学计算
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
using namespace metal;

fragment float4 composite_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    depth2d<float, access::sample>   depthTex [[texture(1)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]])
{
    // 1. 修正 UV
    float2 uv = in.uv;
    uv.y = 1.0 - uv.y;

    // 2. 基础采样
    float4 albedo = colorTex.sample(smp, uv);
    float  depth  = depthTex.sample(smp, uv);

    // 3. 简单处理
    // 如果是天空，稍微提亮
    if (depth >= 0.9999) {
        return float4(albedo.rgb * 1.2, albedo.a);
    }

    // 如果是物体，简单加一点亮度
    // 这里不进行任何矩阵变换，防止数据错乱
    return float4(albedo.rgb * 1.15, albedo.a);
}
