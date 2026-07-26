# MetallumShaders — Fabric mod source tree

This is the source for a **Fabric mod** (not a shaderpack zip) that injects
MakeUp-UltraFast-style shaders into Minecraft Java 26.2 *without* Iris or
OptiFine. It targets the **Metallum** mod (https://github.com/EternityQwQ/MetalUniversal)
which rewrites Minecraft's rendering backend to Apple **Metal**, so all shader
sources are written in **Metal Shading Language (MSL)** instead of GLSL.

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft Java | 26.2 |
| Java JDK | 25 (Adoptium recommended) |
| Fabric Loader | 0.16.14+ |
| Fabric API | 0.130.0+ |
| Fabric Loom | 1.16.2 |
| Metallum | 0.1.0+ (drop the jar into `libs/`) |
| Sodium | optional, will not conflict |

## Build

```bash
# 1. Put the Metallum jar from
#    https://github.com/EternityQwQ/MetalUniversal/releases
#    into ./libs/metallum-0.1.0.jar
mkdir -p libs && cp ~/Downloads/metallum-0.1.0.jar libs/

# 2. Build
./gradlew build
```

The output jar will be in `build/libs/metallum-shaders-1.0.0.jar`.

## CI

`codemagic.yaml` is provided at the repo root for CodeMagic builds.

## How it works

1. A `ServerLevelEvents` / `WorldRenderEvents`-style hook (implemented via
   Mixin into `LevelRenderer.renderLevel`) is fired after the vanilla
   terrain pass.
2. The mod asks Metallum's `MetalContext` for the current `MTLDevice` and
   command encoder.
3. A full-screen triangle is drawn with a custom MSL program that samples
   the depth + color g-buffer that Metallum already populates, and applies:
   - Per-pixel deferred lighting (sun + moon + held-light)
   - **Moving light sources** (player-held torch, eye-of-ender glow, etc.)
   - Volumetric fog
   - Bloom (separable blur, two-pass)
   - Tone mapping (ACES) + gamma
4. Sodium's terrain pass is left untouched — we only read its g-buffer.

See `src/main/resources/assets/metallumshaders/shaders/` for the MSL sources.
