# Packs without post-processing shaders

`mineekshader.zip` supplies gbuffers programs but no final, composite, deferred,
prepare, or shadow programs. It is a valid world shader pack. The OpenGL
`FinalPassRenderer` copies logical `colortex0` to the main framebuffer whenever
the final shader is absent. Native Vulkan must retain its world-frame renderer
and perform the equivalent presentation even with an empty screen-pass graph.

Run from the port root with Java 25 and the current compiled dependency classes:

```powershell
./tests/gbuffers-only/verify.ps1 -JdkPath '<path-to-jdk-25>' -ShaderPack '<path-to-local-shader-pack.zip>' -LegacyArtifact '<optional historical artifact path>'
```

The optional `LegacyArtifact` must name the unfixed previous release. The test
compiles current production sources into a separate test output directory. It
reads the supplied pack through the real `ShaderProperties` and `ProgramSet`
parsers and creates its actual `IrisVulkanScreenPassPlanner` graph. Only unused
ShaderPack startup fields are bypassed, avoiding a Minecraft startup.

ASM then copies the compiled production `IrisVulkanScreenPassExecutor.render`,
`IrisVulkanFinalPassRenderer.hasRunnablePasses`, and native world
`usesShadowMaps`/`renderShadows` methods into recording fixtures. Their branches,
graph calls, mode decisions and ordering remain unchanged. Minecraft access,
GPU objects and subordinate GPU draw/copy helpers are recording sinks; this is
not a GPU test and does not test texture-copy pixel contents.

The checks cover empty-graph world presentation; a prepare-only graph; no-final
presentation when deferred already ran before translucents; a composite-only
graph; preservation of a declared final shader; disabled drawing/build-only
diagnostics; and no-shadow execution still calling `afterShadows` once. Strict
constructor validation of unavailable declared nodes remains production's
responsibility; a skipped final fixture verifies that presentation does not
silently replace a declared final shader.

With the previous-release argument, the same compiled-method replay verifies
all three old failures: empty renderer rejected, empty graph returned without
presentation, and earlier deferred rendering failed to trigger the final copy.
Pack and previous-release hashes are printed in `build/gbuffers-only-tests/result.txt`.

The presentation copy currently requires `colortex0` to have the main target's
format and dimensions. That covers this pack's default RGBA8 target. Format
conversion or scaling for other packs is not established by this regression.
The Vulkan path still needs a real GPU run and screenshot review for visual
correctness, resource lifetime, and shader reload behavior.
