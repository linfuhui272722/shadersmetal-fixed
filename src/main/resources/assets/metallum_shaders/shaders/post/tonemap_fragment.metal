//
// tonemap_fragment.metal
// MetallumShaders
//
// Final post pass. Applies:
//   1. Exposure scaling
//   2. ACES filmic tone map
//   3. Saturation boost (in HSV space, cheap version)
//   4. Vignette
//   5. Subtle dither to break up 8-bit banding
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
#include "include/common.metalh"
using namespace metal;

fragment float4 tonemap_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]]
) {
    float2 uv = in.uv;
    float3 color = colorTex.sample(smp, uv).rgb;

    // 1. Exposure
    color *= u.timePack.z;

    // 2. ACES tone map
    color = acesTonemap(color);

    // 3. Saturation — cheap luminance-preserving boost.
    float lum = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(lum), color, u.timePack.w);

    // 4. Vignette
    color *= vignette(uv, 0.85) * u.bloomPack.y + (1.0 - u.bloomPack.y);

    // 5. Dither
    float d = hash12(uv * u.resolution.xy) - 0.5;
    color += d / 255.0;

    return float4(clamp(color, 0.0, 1.0), 1.0);
}
