# Alpha7 changes and validation

Version: `1.11.3-vulkan-alpha.7+mc26.2`. Tested JAR SHA-256: `70f62969f059f0e4a6e0349fbd36542b286becdae0bf1a38829674dcb5413e74`.

Requirements remain Minecraft 26.2, Java 25 or newer, Fabric Loader 0.19.2+, and exactly one supported Sodium build: `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. Replace the previous Iris JAR; this unofficial fork retains mod ID `iris`.

## Nametags and falling blocks

Native deferred nametag draws previously lost the shader pack's `minecraft:name_tag` material. The material is now resolved before batching and restored during each draw, without inheriting unrelated block or item IDs. Complementary Unbound r5.9.1 uses material 50112 to select its text lighting and background alpha treatment.

The native block and text vertex layouts omitted normals expected by the transformed shaders. A zero fallback could reach `normalize` in the shader pack. Native staged block/text producers now append a normal using the same stable layout selected during native shader compilation and early warmup. Block vertices preserve supplied face normals; glyph quads compute their transformed face normal. The Sodium bulk-copy path preserves existing attributes and computes missing normals. See-through text retains its original lack of UV2; original vanilla pipeline layouts and direct sky/weather buffers remain unchanged.

The shared entity-shadow submission hook now applies to both graphics backends. Native Vulkan suppresses vanilla blob-shadow quads when the current pack uses shadow maps and its shadow renderer is installed. This decision is available before deferred entity submission; it no longer depends on whether the current frame's shadow pass has already run. Packs without shadow maps retain vanilla entity shadows.

## Validation status

- Nametag material and active MRT alpha-blending regression passes against the actual Complementary Unbound r5.9.1 material map and Minecraft 26.2 submission/pipeline classes.
- The shared entity-shadow hook and native lifecycle pass 16 checks, including retaining vanilla shadows for the actual no-shadow Mineek pack.
- The immutable alpha6 JAR reproduces both black nametag bars and black falling sand/gravel in all six focused views. Placed comparison blocks remain textured. Disabling vanilla entity shadows does not remove the black falling-block faces. Exact JAR/probe provenance passes.
- `:fabric:build` passes, including the native logical-target regression. The inherited general Iris test suite remains disabled upstream.
- The exact alpha7 packaged JAR completes the same six Complementary Unbound r5.9.1 High views. All twelve before/after images were reviewed: black bars become visible glyphs with nonopaque backgrounds; falling sand/gravel regain textured top, side and bottom faces, including after five physics ticks. Placed comparison blocks remain textured. Camera poses and entity states match; text material changes from 0 to 50112. Both runs record 40 solid-block and 80 text draws per view.
- Both artifacts record zero vanilla entity-shadow draws in this particular scene. The visual result validates the block/text fixes, while the separate CPU lifecycle checks validate the shadow-submission policy.
- Alpha7 also completes six Mineek views with visible nametags and textured falling blocks. This checks the no-shadow/no-final pack path after the vertex changes.
- Both candidate runs pass packaged-JAR and separate-probe provenance, with 24 recorded class resources and no unexpected source locations. The probe SHA-256 is `638ba3b0bc64132c0927452e56ea704fc2b868af44dec365ed024ca5d5747c54`, identical to the alpha6 comparison. It is not included in the production JAR.
- The [normal regression](../tests/native-normals/README.md) passes 1,416 checks for transformed glyphs, attribute copies, buffer reallocation and draw eligibility, plus 731 checks using Minecraft's actual six-face baked-block producer. The test invokes Sodium's serializer factory directly to reproduce its missing-Normal rejection before checking the managed copy path. Sodium beta 3 and 0.9.2 have byte-identical relevant upload/glyph classes; the GPU tests use 0.9.2.

Runtime evidence is under `build/release-validation/alpha6-entity-faces-high`, `alpha7-entity-faces-high` and `alpha7-entity-faces-mineek`. The corresponding [fixture instructions](../tests/entity-faces/README.md) reproduce the scene. Checks used Minecraft 26.2, Sodium 0.9.2, Java 25.0.3, Windows 11 and RTX 5070 / NVIDIA 616.92. This is a focused visual correction, not a measurement of full OpenGL lighting parity or FPS improvement.

Previous alpha records apply to their own binary hashes. These changes do not establish full shader-pack, mod or device compatibility. Apple Silicon requires a separate runtime retest.
