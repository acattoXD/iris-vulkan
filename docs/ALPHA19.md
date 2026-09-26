# Alpha19: BSL and Derivative compatibility fixes

Version `1.11.5-vulkan-alpha.19+mc26.3`. Requires Minecraft 26.3, Java 25, Fabric Loader 0.19.5+ and Sodium 0.9.2+mc26.3. Vulkan shader support remains experimental.

JAR SHA256: `1f9479df2697110760e1dee33f98773a58d88690ba2e831b41f8d669d4c732ea`.

## Changes

- Derivative 25.1.0 requests the integer `bossBattle` uniform. Native Vulkan now obtains it from the current boss-bar state, using the established no-boss/custom/dragon/wither/raid values. The previous unsupported-uniform failure prevented its world and shadow pipelines from compiling.
- The properties preprocessor now recognizes CR/LF line endings without interpreting byte0x85 inside non-English comments as a new line. This preserves Derivative's66 item mappings instead of truncating the map midway through a comment. Property encoding, conditionals and continuations are preserved.
- BSL10.1.5 passes its shadow sampler through an identity helper named `texture2DShadow`. The native adapter now routes this verified identity wrapper through its existing software depth-comparison functions after lowering the sampler type. Helpers with additional custom behavior are left unchanged. Shadows are not disabled.

These are compatibility changes. No shader quality settings, graphics compiler optimization settings or normal-profile configuration are changed.

## Validation and limits

The full offline Fabric release build and11 packaging tests pass. The properties regression reproduces the previous truncation, checks19 line-ending/comment/macro conditions, and verifies all66 mappings in the actual pack. The BSL regression compiles eight shadow filtering/mipmap variants and preserves non-identity helper behavior. The exact failing BSL fragment reproduces its previous error; the patched fragment and matching vertex then compile with the production shaderc options. Twelve boss-state checks cover actual26.3 boss-overlay events, integer mapping, field/type acceptance and std140 layout. These checks run without a game or graphics device.

CPU tests and successful compilation do not establish complete rendered-image parity. In-game BSL/Derivative behavior, all dimensions, shader reloads and cross-device behavior still require verification on the new JAR. Existing Alpha18 visual observations apply to that older artifact.

## Rethinking Voxels r0.1-beta9

This build does not add Rethinking Voxels support. Its shadow geometry shader emits triangles and writes voxel storage using image atomics/SSBOs. Its prepare fragment passes also write built-in color images, including integer `colorimg9`. Native geometry stages and graphics-stage built-in color-image bindings are not implemented. Compute-only color-image support is insufficient. Low quality disables some prepare passes but retains the geometry stage; the unsupported-feature check remains enabled.

The existing release/source archives remain separate. This release does not claim an FPS improvement or new Apple Silicon support.
