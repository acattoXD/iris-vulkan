# Native pipeline warmup regression

Run after compiling the current 26.3 project and generating its isolated dependency classpath (one path per line, or a JSON array). Include compile-time JetBrains annotations when exporting the dependencies:

```powershell
./tests/pipeline-warmup/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3'
# An existing JSON dependency export can also be supplied explicitly:
./tests/pipeline-warmup/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3' -RuntimeClassPath 'build/pipeline-warmup-tests/classpath.json'
```

The CPU test recompiles and executes the current production warmup controller, phase mapping, shadow draw policy, pipeline-to-shader mapping, and MRT pipeline adaptation against the 26.3 RenderPearl APIs. A constructor-invoker stub creates real immutable Minecraft pipelines through reflection. Small startup stubs supply an absent live world and inactive legacy hand renderer without initializing Fabric or a graphics context. All stubs and recompiled classes stay in the dedicated test output directory.

Checks cover real LOAD-only descriptors at four viewport sizes (including odd dimensions), 21 unique world pipeline/phase requests, and reuse of real adapted MRT descriptor identities on 1,000 later frames with a fake compiler callback. The exact `ARMOR_CUTOUT_NO_CULL_GLINT` and `CUTOUT_BLOCK` descriptors each occur once in `ENTITIES` and map to the shader keys observed in the cold-compilation log. The preexisting 11 shadow requests remain unchanged, with decorative glint skipped to leave 10 physical requests. Success, failure, skip, and mixed callbacks verify statistics and no per-frame retry. Bytecode inspection verifies that production warmup calls the RenderPearl render-pass `setPipeline` path and contains no draws, attachment clears, or uniform uploads.

This is a CPU contract test: it does not invoke `prime`, create GPU passes, compile Vulkan shaders, or demonstrate actual native cache hits. Live validation must still confirm that the measured armor-glint and cutout-block cold compilations occur during initial world warmup and do not recur on their first draws. Existing render-pass hooks perform the MRT/shadow adaptation used by later draws. Inspect `iris.VulkanShaderCompile` JFR events and the cold-compilation log in an isolated run. The intended tradeoff is longer initial world loading for fewer known first-use compilation stalls; this does not claim a steady-state FPS improvement or eliminate every stutter.
