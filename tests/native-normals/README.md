# Native moving-block and glyph normals

`IrisVulkanVertexFormats` gives native shader-pack world geometry a normal attribute while preserving every original attribute offset and value.

| Producer | Original bytes | Native bytes | Lightmap |
| --- | ---: | ---: | --- |
| Moving BLOCK | 28 | 32 | Preserved |
| Lightmapped glyph | 28 | 32 | Preserved |
| See-through glyph | 24 | 28 | Remains absent |

RenderType selects the producer format only for a mapped, eligible native world/shadow draw. Vanilla, OpenGL, menus, bypass/offscreen draws, Sodium terrain, and unrelated textured sky/weather paths retain their original formats. The glyph key list is explicit because `ShaderKey.isText()` also matches names such as SKY_TEXTURED.

Native override compilation resolves the same stable layouts independently of the current frame state. This keeps early warmup and deferred replay consistent without changing original vanilla pipeline getters. StagedVertexBuffer records the selected format before constructing BufferBuilder; the constructor therefore never independently changes its format.

The normal writer retains supplied block normals and computes glyph face normals from the transformed vertex positions, using the same face-normal method as the GL path. Partial primitives keep buffer-relative offsets across allocation growth. Completed bulk pushes are not recorded again by the subsequent end-of-vertex callback.

Sodium's ordinary serializer rejects destination attributes missing from its source. A narrowly scoped managed-layout push path copies named attributes, then supplies normals. This correctly moves Color and drops UV2 when converting Sodium's 28-byte glyph source to the 28-byte see-through destination. Other source/destination formats keep Sodium's existing behavior. Already supplied block normals remain intact.

## CPU verification

Run from the repository root:

```powershell
tests/native-normals/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3'
```

The recorded run passed:

- **1,416 glyph/layout checks:** actual Sodium glyph producer bytes; rotated, nonuniformly scaled and mirrored glyph normals; attribute offsets; preserved UV2 absence; mixed individual/bulk records through ByteBufferBuilder growth; supplied block normals; original pipeline immutability; stable warmup layouts; and vanilla/OpenGL/menu/offscreen/bypass gates.
- **731 moving-block checks:** actual Minecraft BufferBuilder and baked-quad emission for all six faces, using the production BLOCK_WITH_NORMAL format and verifying the original 28-byte attribute prefix remains identical.

The serializer negative control invokes Sodium's actual public VertexSerializerFactory directly. It avoids the registry's dependence on the VertexFormatExtensions mixin, which is not applied in a plain JVM. No separate mixin framework or game is started by these CPU tests. Test-only Unsafe allocation supplies inert pipeline types for eligibility checks, without constructing GPU resources.

Results are in `build/native-normal-tests/IrisVulkanGlyphNormalTest.txt` and `IrisVulkanMovingBlockContractTest.txt`. The printed generic missing-normal expression is diagnostic; the production fix supplies actual normal attributes instead of changing that unrelated fallback globally.

## Visible validation and provenance

The parent validation ran the same six-view fixture before and after the change. Alpha6 reproduced opaque name-tag bars and black falling-block model faces even with vanilla entity shadows disabled. Alpha7 showed readable text and textured falling sand/gravel at matching poses. The no-shadow Mineek fixture also passed.

The tested alpha7 JAR SHA-256 is `70f62969f059f0e4a6e0349fbd36542b286becdae0bf1a38829674dcb5413e74`. Production sources remained unchanged after that build. Runtime fixture evidence is under `build/release-validation/alpha7-entity-faces-high`; CPU tests validate the layout/memory contracts, while those GPU captures validate actual woven producer and draw paths.
