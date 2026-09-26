# Alpha13 candidate: omit unused render targets

Version `1.11.5-vulkan-alpha.13+mc26.3`; JAR SHA256 `e98be84e4a0329fa159ad7bf53fd75066d28e2195c8d709e302a782569ec8532`.

**The controlled High comparison passes; Ultra and broader lifecycle validation remain pending. Alpha10 remains installed.** This candidate inherits Alpha11's compute changes and Alpha12's shared initial depth clear. No additional whole-game FPS improvement was measured in the High comparison.

## Change

Render-target planning no longer allocates a color buffer solely because a shared header declares an unused simple sampler. It preserves actual references, image declarations, arrays, multiple or unfamiliar declarations, and declarations still retained by the existing native resource compiler. Unresolved preprocessor directives disable this pruning for that source.

The planner still examines vertex, fragment, geometry, tessellation and compute sources. Draw-buffer outputs, mipmap requests, explicit flips, storage references and the internal fallback targets remain independent requirements. Custom-resource precedence is unchanged; the scan deliberately does not prune referenced custom sampler aliases. No shader source, numerical operation or quality setting is changed.

## Verified resource reduction

The real ProgramSet parser and compiled target-planning/allocation/clear methods were replayed against the frozen Alpha12 JAR, with GPU calls recorded instead of executed:

| Complementary Unbound r5.9.1 preset, 2560x1440 | Result in all three dimensions |
|---|---|
| High | Only colortex8 is omitted: two RGBA16F textures, 58,982,400 bytes (56.25 MiB) of nominal pixel storage. One steady-frame clear and three first-frame clears are removed. |
| Ultra | No targets or clear commands are omitted. Water still writes colortex8 and composite1 still samples it. |

These byte counts describe requested texture payload, including the two sides of the target. They are not a measurement of actual driver VRAM residency, allocation-pool size or FPS. Retained formats, dimensions, mip levels, clear values and clear ordering requirements are unchanged.

## Checks

- Full Fabric release build passes.
- Scanner regression cases cover all 32 indices, legacy aliases, used/unused samplers, arrays, storage images, multiple declarations, metadata, comments, unresolved directives, invalid indices and compiler-retained declarations.
- 2,203 checks against the final Gradle classes cover real parser/planner/allocation behavior across High/Ultra and Overworld/Nether/End, plus all shader stages, setup/shadow/final/array compute paths, mip/flip requests and the shadowcolor namespace.
- One model instance switches High to Ultra and back, resizes, repeats unchanged configurations and returns to the no-pack fallback. Required textures initialize when enabled; removed targets expose no stale view; old textures and views close once.
- Against Alpha12, the final JAR changes only fabric.mod.json, IrisVulkanTargetIndices.class and IrisVulkanTargetModel.class. No test recorder or capability facade is packaged in the mod.

The allocation replay uses the real compiler's pruning helper and the real program/property parsers. GPU device calls, native-mode selection and live fog state are recorded/fixed for the test. It proves the tested planning and allocation behavior, not rendered visual equivalence, driver behavior or third-party compatibility.

Evidence: `build/optimization-alpha13-build.log`, `build/optimization-alpha13-target-compiler-guard.log` and `build/alpha13-liveness-final` in this port; source inventories are under `build/colortex8-liveness/comp-r5.9.1-high-ultra-02`. TargetIndices class hash: `37a9563a1eb25f0436838d1d0a9e58503b0acff6acc09b9c2d22ee95f9d1a526`; TargetModel class hash: `520d873a7d02076ed5a83ed77229765bd4534038ab34100f414cbf642ec14516`.

## Runtime gate

The user explicitly requested launch of the prepared High comparison. Both visible games ran sequentially without mouse, keyboard or focus automation, completed all three20-second samples with zero observed focus changes, saved images and closed normally. Strict input/rendering checks match. Artifact SHA256 above is unchanged.

| Median per-window mean | Alpha10 | Alpha13 |
|---|---:|---:|
| Frame interval |3.965846 ms|3.973720 ms|
| Equivalent FPS |252.15|251.65|
| GPU world time |3.816251 ms|3.823266 ms|
| Render-thread CPU time |1.994219 ms|2.107544 ms|
| World submission time |1.413718 ms|1.478153 ms|

FPS is effectively unchanged in this one pair, with a0.2% difference. CPU means are higher in this run; repeated alternating runs would be needed before attributing that change to the code. Do not claim a further FPS or CPU improvement from this result. The260FPS cap and single test scene limit interpretation.

Actual allocation logs confirm that only colortex8 disappears and all retained formats/dimensions match. This removes the two textures'56.25MiB nominal payload; driver VRAM residency was not queried. Both after/turn image pairs were reviewed: glass/panes, nametags, block surfaces, shadows and the held item remain visible, with no obvious new static issue. Glint animation phase differs in the turned view; static captures cannot prove a continuous-motion fix.

Evidence in the parent workspace: `build/optimization-26.3/controlled-alpha10-vs-alpha13-high.json` and `alpha13-high-runtime-review.json`; profiles `build/port-26.3/benchmark-alpha10-high-liveness-01` and `benchmark-alpha13-high-liveness-01`. The immutable packaged candidate still records its earlier pre-game validation status; this later record adds evidence without changing the JAR.

The previous Alpha10/Alpha11 Ultra pair remains unlaunched. Camera-dependent glass flicker, broad device/pack validation and the latest combined candidate's Ultra behavior remain open. The user's launch request covered the High comparison; it did not authorize desktop input automation.
