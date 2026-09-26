# Native world contracts

These standalone JVM tests use the project's compiled classes and cached Minecraft 26.3/Sodium 0.9.2 dependencies. They do not create an OpenGL context, start a game, or resolve dependencies over the network. A few historical helper scripts retain older cache-layout names; use the current Alpha18 build and package instructions for release validation.

After `:common:compileJava`, run on Windows with Java 25 and an installed Complementary Unbound r5.8.1 ZIP:

```powershell
./tests/native-world/verify.ps1 -JdkPath '<path-to-jdk-25>' -ShaderPack '<path-to-local-shader-pack.zip>'
```

Add `-DumpDirectory 'fabric/run-vulkan-world-probe/iris-vulkan-dumps'` to recompile actual prepared stage dumps after native GLSL version normalization through Minecraft's shaderc/SPIRV-Cross frontend. This does not require a Vulkan device.

The checks exercise:

- Actual preprocessed Complementary material IDs for water, oak leaves and short grass, encoded through the production 36-byte Sodium vertex writer and decoded as the shader reads them.
- Separate AO, sprite center, normal/tangent, section index, and local mid-block coordinates.
- Nested world phases and entity/block/item material scopes, including restoration after a draw or frame reset.
- Shader-family routing for held items, held-map text, block entities and shadow passes.
- Projection-buffer layout preservation, shader-pack clip conversion, hand depth compression and native cloud-face bindings.
- Real shaderc compilation to Vulkan SPIR-V, including an early return in the pack's vertex entry point.
- Every method, shadowed field and invocation targeted by the native world mixins against installed Minecraft/Sodium bytecode.
- The actual compiled frame callbacks with GPU side effects replaced by a depth-buffer fixture: `depthtex2` must contain opaque depth before deferred, then refresh again before the later native hand draw. This prevents Sildur from identifying every opaque pixel as a hand pixel and multiplying shadow positions by 6.5; the final hand/terrain/sky distinction is also checked.
- Compile timing: 10,000 actual source, world-pipeline, and screen-pipeline cache hits each produce no new timing events. Explicit cold-work events record successful/failed status and JFR duration without a GPU device.

These are implementation contracts. A successful run does not prove that an entire shader pack renders correctly; that requires the separate in-game world probe and visual inspection.

The [camera regression checks](../projection/README.md) cover the shared camera mixin, shader-active gate, and projection/model-view split. They also document the visible Sildur walking/turning probe and its recorded before/after results.

To investigate first-use stalls, inspect `iris.VulkanShaderCompile` events in a JFR recording. They separate source transformation from native world/screen pipeline compilation and include the shader key, pipeline/program, duration, and success status. Cache hits skip the timing helper; cold work lasting at least 100 ms also produces a `Native Vulkan cold ... took ... ms` log entry. These are measurements, not prewarming or shader-quality changes.
