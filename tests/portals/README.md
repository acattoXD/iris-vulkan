# End portal and gateway contracts

Minecraft 26.2 legitimately renders vanilla end portals and gateways with POSITION-only vertices. Its stock shader computes projective texture coordinates from clip position, samples end-sky from Sampler0 and portal stars from Sampler1, and supplies its own layer colors. Portal/gateway pipeline defines select 15/16 layers.

Iris's active-pack path replaces that contract through the shared `MixinTheEndPortalRenderer` and `MixinTheEndGatewayRenderer`. These hooks must apply on both graphics backends. An identity default for an absent vertex color is useful for strict Vulkan input binding, but alone would leave the wrong texture route and incomplete portal geometry inputs.

| Property | Active Iris portal/gateway contract |
| --- | --- |
| Render type | `RenderTypes.entitySolid(END_PORTAL_LOCATION)` |
| Base texture | Portal stars as Sampler0/pack `tex` |
| Vertex format | Position, Color, UV0, UV1, UV2, Normal |
| Vertex tint | `(0.075,0.15,0.2,1)`, packed by Minecraft to `(19,38,51,255)` |
| UVs | Square 0..0.2, translated by frame time with a 100 second period |
| Lighting | Full bright and no overlay |
| Normals | Actual transformed face directions |
| Geometry | Portal slab y 0.375..0.75; gateway full cube |

Complementary Unbound r5.9.1 maps both block entities to 5025. Its portal effect uses that material identity, face normals, view/world positions, frame time and the portal-star texture. Correct normals distinguish horizontal portal surfaces from vertical gateway faces. Gateway beacon beams remain a separate vanilla submission.

Run the CPU regression from the repository root:

```powershell
tests/portals/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3' -ShaderPack 'path/to/ComplementaryUnbound_r5.9.1.zip'
```

The script compiles the current production hooks, mixin-selection plugin and shader-resource adapter. Only Iris pack-presence/startup services are stubbed. The handlers execute with Minecraft's actual face map, pose transforms, render types and vertex-consumer defaults. Tests cover both backend selections, active/inactive packs, all six faces, UV animation and texture routing.

Minecraft's actual native GLSL compiler and SPIRV-Cross input rebinder reproduce the original missing-color failure, then validate the position-only fallback. A small SPIR-V constant evaluator verifies the compiled output retains a nonwhite, nonopaque color modulator. Supplied colors remain required; unknown attributes and unsupported color types still fail strict binding. The actual selected pack ZIP supplies the 5025 material assertions.

This is a CPU contract test, not a substitute for GPU visual validation. The optional portal probe separately checks actual woven hooks, deferred draw material IDs, bound texture views, portal activation, reload and framebuffer captures.
