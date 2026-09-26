# Native nametag material and alpha contracts

Run with the actual Complementary Unbound r5.9.1 pack:

```powershell
./tests/nametags/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3' -ShaderPack 'C:/path/to/ComplementaryUnbound_r5.9.1.zip'
```

This CPU regression constructs actual Minecraft 26.2
`NameTagFeatureRenderer.Submit` records, resolves the actual pack's `name_tag`
entry (50112), and calls production `IrisVulkanEntityContext.fromSubmit` and
material scopes. It checks separation from parent entity/block/item IDs,
uniform-state propagation, scope restoration and missing-mapping behavior.
It also checks the four real text/background pipelines and the active
`IrisVulkanWorldPipelineStates.colorStates` attachment adaptation preserve
inherited alpha blending on all four mapped attachments (logical 0, 3, 6, 4).
The compile/run results are recorded in `build/nametag-tests/result.txt`.

The texture checks execute actual `BakedSheetGlyph.createEffect` and background
render-type selection: the normal stitched white background glyph uses TEXT or
TEXT_SEE_THROUGH with its glyph atlas bound to Sampler0. It does not use the
separate untextured TEXT_BG pipelines. The latter have no Sampler0 by design;
OpenGL supplies a white pixel for untextured pack shaders, while the existing
native world binder requires an explicit binding. That separate route needs a
live occurrence before attributing the reported bars to it. The test also
checks ENTITY_SHADOW explicitly binds Minecraft's shadow texture and verifies
the actual PNG contains transparent edges and nonzero center alpha.

The original native path resolves a nametag submission to the empty material.
OpenGL's `entity_render_context.MixinEntityRenderer` instead sets the pack's
`minecraft:name_tag` ID during `NameTagFeatureRenderer.buildGroup`. That mixin
is excluded from Vulkan; merely enabling it would update captured uniforms
without updating native material runs. Complementary's 50112 branch disables
directional shading and adjusts glyph brightness and low-alpha backgrounds.

A separate source audit found that `ShaderKey.TEXT` assumes the extended Iris
glyph format, including a normal. OpenGL's buffer builder computes the quad
normal; Minecraft's native TEXT and TEXT_SEE_THROUGH vertex formats have no
normal. The old native missing-input fallback replaces `iris_Normal` with zero,
which the pack normalizes and uses for lighting/normal output. This audit does
not establish a GPU visual verdict or assert that material IDs alone fix the
reported bars.

The four vanilla text/background pipelines use SRC_ALPHA /
ONE_MINUS_SRC_ALPHA for color and ONE / ONE_MINUS_SRC_ALPHA for alpha. The active
native world adapter inherits these equations on every mapped attachment when
there is no program or per-buffer override. Explicit shader-pack blend
overrides need their own tests.
