# Alpha4 changes and validation

Version: `1.11.3-vulkan-alpha.4+mc26.2`. Tested JAR SHA-256: `7f5215062041476e486af9002727fced0e212f3a17a229c2828798a70f723fdf`.

Requirements remain Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and exactly one of Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. Replace any earlier Iris JAR; this unofficial fork retains mod ID `iris`.

## Sodium region-manager correction

The alpha3 report includes an earlier login failure: the `iris$forceClear` redirect in `MixinRenderRegionManager` matched zero invocation targets. Its selected `uploadResults(RenderRegion, Collection, UniformBufferManager)` overload **still exists** in both supported Sodium versions. What changed in Sodium 0.9.2 is the removal of the `clearAllCachedBatches()` invocation inside that overload, leaving the redirect without a target.

Alpha4 removes only that redundant mixin and its configuration entry. The retained `MixinRenderRegion` HEAD hooks continue to clear active, saved regular, and saved shadow batches when `clearAllCachedBatches()` runs, and invalidate saved batches when `clearCachedBatchFor()` runs. Sodium still clears the selected active batch. The real clear operation is idempotent, so retaining the HEAD hooks and vanilla methods preserves invalidation on both versions.

The later player-null exception in the supplied report follows the earlier login/mixin failure. The report does not state the actual selected graphics backend. A separate controlled Windows OpenGL run reproduced the Iris redirect error, but that does not identify the user's selected API. Alpha4 makes no backend-selection change.

## Validation status

| Check | Alpha4 status |
| --- | --- |
| Retained region invalidation on Sodium beta.3 and 0.9.2 | [Targeted tests](../tests/sodium-batch-cache/README.md) pass against both publisher JARs. They execute actual region/batch methods with the retained hooks across regular/shadow swaps, all-batch clearing, per-pass clearing, and repeated clearing. |
| Registered OpenGL Sodium mixins | [Beta.3 audit](../build/sodium-0.9.2-audit/opengl-beta3-mixin-report.json) passes 116 checks; [0.9.2 audit](../build/sodium-0.9.2-audit/opengl-mixin-report.json) passes 119 checks. Both report zero required failures; optional misses are recorded separately. [Audit implementation](../tests/native-world/IrisSodiumGlMixinAudit.java). |
| Alpha3 OpenGL / Sodium 0.9.2 reproduction | [Controlled run log](../build/release-validation/alpha3-opengl-makeup-repro/launcher.log) identifies OpenGL and reproduces the exact `iris$forceClear` injection failure, `(0/1) succeeded`. |
| Alpha4 OpenGL / MakeUp 9.5e `medium`, Sodium 0.9.2 | [All 10 scenarios completed](../build/release-validation/alpha4-opengl-makeup-final/native-probe-result.txt), including dimension changes, resize and shader reload. [Provenance](../build/release-validation/alpha4-opengl-makeup-final/diagnostic-runtime-provenance.json) verifies 501 production classes from the exact JAR. The restored scene was visually reviewed. |
| Alpha4 Vulkan / MakeUp 9.5e `medium`, Sodium 0.9.2 | [All 10 scenarios completed](../build/release-validation/alpha4-vulkan-makeup-final/native-probe-result.txt). [Provenance](../build/release-validation/alpha4-vulkan-makeup-final/diagnostic-runtime-provenance.json) verifies 439 production classes from the exact JAR. Shadow depth readback checks passed and the restored scene was visually reviewed. |
| Mac hardware | Requires a new test with the actual selected backend recorded. |

The bytecode tests confirm that beta.3's upload overload contains one all-batches clear and four per-pass clears; 0.9.2 contains zero and three respectively. Runtime checks ran on Windows 11 / RTX 5070 / driver 610.62 / Java 25.0.3, using the packaged JAR with a separate diagnostic probe. They do not establish Mac hardware compatibility or full shader parity.

[Alpha3's atlas and Windows Vulkan results](ALPHA3.md) remain evidence for its documented artifact. No new performance or broad device-compatibility claim is made here. Keep earlier release bundles unchanged and pair each new binary with its exact source/checksum bundle using [the release procedure](RELEASING.md).
