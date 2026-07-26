//
// bloom_horizontal_fragment.metal
// MetallumShaders
//
// First pass of a two-pass separable Gaussian blur for bloom.
// Samples 9 taps along the X axis with a 1.5x texel offset to keep the
// kernel cheap. Bright pixels above the threshold are isolated first
// (luminance-based), then blurred.
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
#include "include/common.metalh"
using namespace metal;

constant float WEIGHTS[9] = {
    0.013519, 0.050120, 0.130645, 0.236084, 0.301137,
    0.236084, 0.130645, 0.050120, 0.013519
};

fragment float4 bloom_horizontal_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]]
) {
    float2 uv = in.uv;
    float2 texel = 1.5 / u.resolution.xy;

    // Isolate bright pixels first.
    float3 center = colorTex.sample(smp, uv).rgb;
    float lum = dot(center, float3(0.2126, 0.7152, 0.0722));
    float3 bright = center * smoothstep(u.bloomPack.x, u.bloomPack.x + 0.25, lum);

    float3 sum = float3(0.0);
    sum += bright * WEIGHTS[0] * 0.5; // edge taps share the center sample
    for (int i = 1; i < 9; i++) {
        float2 offset = float2(texel.x * (float(i) - 4.0), 0.0);
        float3 s = colorTex.sample(smp, uv + offset).rgb;
        float sl = dot(s, float3(0.2126, 0.7152, 0.0722));
        s *= smoothstep(u.bloomPack.x, u.bloomPack.x + 0.25, sl);
        sum += s * WEIGHTS[i];
    }
    return float4(sum, 1.0);
}
