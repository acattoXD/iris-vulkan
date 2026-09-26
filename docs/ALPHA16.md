# Alpha16 candidate: Photon resource paths

Version `1.11.5-vulkan-alpha.16+mc26.3`. Minecraft26.3, Java25, Fabric Loader0.19.5+, Sodium0.9.2+mc26.3. Experimental candidate: full Photon rendering remains unverified.

Runtime JAR SHA256: `0a30498ef016b1315436786de6f259ac3738e1e68b1276a48ce8ff9bf56fd0db`. Full Fabric release build and the ten existing package checks pass. Nine changed production classes were compared with the built JAR, and the storage-usage mixin is registered. No diagnostic probe is bundled.

Photon v1.3b High legitimately declares 3D atmospheric/noise textures using familiar names such as depthtex0 and colortex6. Iris remaps them to custom texture names. The old native preflight missed those typed aliases and supported only RGBA32F raw volumes. Alpha16 resolves the stage/type aliases and adds RGB16F half-float and normalized R8 volumes. RGB half values are preserved exactly while adding alpha1; no conversion to8-bit color is used. Flattened sampling now preserves nearest/linear filtering and clamp/repeat on all three axes, including slice seams.

Photon also writes spherical-harmonic lighting data into colorimg4, the writable alias of its current192×108 RGBA16F colortex4. Compute-only color-image aliases now borrow the current target's mip-zero view, following buffer flips. Both physical sides receive storage usage at allocation, and that capability participates in allocation equality. Normal targets retain their original usage. Ownership, resize and deferred destruction remain in the target model. Descriptors use the engine's existing GENERAL layout and the executor's two ordered compute memory barriers. Declared, allocated and shader image formats must agree; incompatible formats are rejected rather than retried with a lossy framebuffer format.

The compute source parser also reuses the comment masker so Photon's `//*` switches do not leave stray slash tokens. Graphics storage-image aliases, general sampler arrays, and every possible texture format are not claimed by this change. Shaderc graphics optimization remains disabled; Alpha15's terrain-binding correction and warmup additions remain included.

## Recorded checks

- Static-volume checks cover four actual Photon payloads, bit-preserving uploads,44 CPU sampling/source checks,40 shaderc compilations and14 typed-alias routing cases. No GPU sampling equality test has run.
- Color-image checks pass499 CPU allocation/lifecycle/parser contracts and86 compiled integration invariants. These use recorded resource providers, not a driver.
- Actual Photon High deferred4_a compiles to8,172bytes of SPIR-V, retaining two image-write instructions, an rgba16f storage image, the colortex4 sampler and256×1×1 workgroup size.
- Real ShaderPack/ProgramSet CPU loading of default and High exports105 sources each and passes resource preflight under an explicit feature environment. The first deferred graphics pass transforms to READY. Complete graphics preparation needs live world uniforms and was not faked.
- Existing target-planning regression passes2,203 checks across Complementary High/Ultra in three dimensions, including allocation and lifecycle behavior.

A visible, isolated runtime test must still verify mixin application, native image usage, actual compute writes, all graphics shader compilations, output and reload behavior. Passing preflight or shaderc alone is not proof that Photon renders correctly or faster. This build does not claim a fix for the separate server-specific white-TNT report.
