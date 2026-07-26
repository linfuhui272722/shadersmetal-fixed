//
// fullscreen_vertex.metal
// MetallumShaders
//
// Single-vertex fullscreen triangle. Drawn with {@code drawPrimitives}
// and a vertex count of 3, no vertex buffer bound. The clip-space
// coordinates are reconstructed from the vertex ID, and the UV is
// derived from them. This is the same trick MakeUp-UltraFast uses for
// all of its post passes.
//

#include <metal_stdlib>
#include "include/uniforms.metalh"
using namespace metal;

vertex VSOut fullscreen_vertex(uint vid [[vertex_id]]) {
    // Produce a triangle that covers the whole screen:
    //   vid 0 -> (-1, -1)
    //   vid 1 -> ( 3, -1)
    //   vid 2 -> (-1,  3)
    float2 positions[3] = {
        float2(-1.0, -1.0),
        float2( 3.0, -1.0),
        float2(-1.0,  3.0)
    };
    float2 p = positions[vid];

    VSOut out;
    out.position = float4(p, 0.0, 1.0);
    // UV origin at top-left to match Metal's texture coordinate convention.
    out.uv = float2((p.x + 1.0) * 0.5, (p.y + 1.0) * 0.5);
    return out;
}
