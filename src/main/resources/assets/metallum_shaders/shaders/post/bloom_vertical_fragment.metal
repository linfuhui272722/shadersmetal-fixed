//
// bloom_vertical_fragment.metal
// MetallumShaders
//
// Second pass of the separable bloom. Same kernel, Y axis. Adds the
// blurred result back onto the original color at {@code bloomStrength}.
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
#include "include/common.metalh"
using namespace metal;

fragment float4 bloom_vertical_fragment(
    VSOut in [[stage_in]],
    texture2d<float, access::sample> colorTex [[texture(0)]],
    constant Uniforms& u [[buffer(0)]],
    sampler smp [[sampler(0)]]
) {
    float2 uv = in.uv;
    float2 texel = 1.5 / u.resolution.xy;

    float3 original = colorTex.sample(smp, uv).rgb;
    float3 sum = float3(0.0);
    for (int i = 0; i < 9; i++) {
        float2 offset = float2(0.0, texel.y * (float(i) - 4.0));
        sum += colorTex.sample(smp, uv + offset).rgb * WEIGHTS[i];
    }
    return float4(original + sum * u.fogPack.w, 1.0);
}
