# Alpha3 changes and validation

Version: `1.11.3-vulkan-alpha.3+mc26.2`. Tested production JAR SHA-256: `97a7782c59d14d52c319ab8f8dd17144f545ae8cef88776513b13b78d6fe0463`.

Requirements remain Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and exactly one of Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. Replace any earlier Iris JAR; this unofficial fork retains mod ID `iris`.

## Startup binding correction

The supplied alpha2 log from an Apple M2 Pro first fails with `Missing Vulkan render pass uniform binding for Globals` in `SpriteContents.AnimationState.drawToAtlas`. The failure occurs while vanilla animates texture-atlas sprites during startup. Iris's binding hook was checking a vanilla pipeline's declared layout even though the compiled vanilla shader did not require that uniform.

Alpha3 tracks the exact compiled Vulkan pipeline objects successfully created by Iris. Only those objects receive Iris resource binding and strict validation; cache eviction removes their registration. Vanilla atlas/UI passes and other mods retain their own binding behavior. Iris-owned pipelines still require their real resources; the change does not manufacture a dummy `Globals` buffer.

After the initial exception, the log shows Not Enough Crashes calling OpenGL `glEnable` from its reset handler while Vulkan is active, followed by a native abort because no OpenGL context is current. That is a secondary crash-recovery failure. This startup log does not reach the previously reported Sodium shadow push-constant/Metal binding case, so it does not validate or disprove alpha2's separate layout correction.

Disable Not Enough Crashes for the Vulkan retest so its OpenGL recovery path cannot obscure a remaining renderer error. Alpha3 does not modify that mod.

## Validation status

| Check | Alpha3 status |
| --- | --- |
| Compiled-pipeline registration and strict Iris-owned binding checks | [Production-binder CPU regressions](../tests/renderpass-scope/README.md) pass: distinct value-equal pipeline identities, registration/removal, vanilla no-op behavior, strict missing-resource failures, custom/storage binding, and refreshed per-draw aliases. Removing the ownership guard reproduces the missing-`Globals` failure. |
| Actual vanilla animated-sprite blit and interpolation with `Globals` unavailable | The matched [alpha2 GPU run](../build/release-validation/alpha2-atlas-smoke-final/evidence/atlas-animation-report.json) reproduces the exact missing-`Globals` exception. With the same probe, [alpha3 passes](../build/release-validation/alpha3-atlas-smoke-final/evidence/atlas-animation-report.json) red blit, green blit and half interpolation: all 768 fence-read-back pixels match within one channel value. No dummy Globals is created, and the previous global buffer is restored after each draw. |
| MakeUp UltraFast 9.5e `medium` / Windows, Sodium 0.9.2 | [Packaged run](../build/release-validation/alpha3/alpha3-makeup-medium-final/native-probe-result.txt) completes all 10 scenarios. [Provenance](../build/release-validation/alpha3/alpha3-makeup-medium-final/diagnostic-runtime-provenance.json) verifies the exact alpha3 JAR and separate probe, with no unexpected class sources. The [restored capture](../build/release-validation/alpha3/alpha3-makeup-medium-final/evidence/restored.png) was visually reviewed. |
| Complementary Unbound r5.9.1 Ultra / Windows, Sodium 0.9.2 | [Packaged run](../build/release-validation/alpha3/alpha3-r591-enchanted-final/native-probe-result.txt) completes all 10 scenarios with enchanted-item checks. [Provenance](../build/release-validation/alpha3/alpha3-r591-enchanted-final/diagnostic-runtime-provenance.json) verifies the exact alpha3 JAR and separate probe, with no unexpected class sources. The [restored capture](../build/release-validation/alpha3/alpha3-r591-enchanted-final/evidence/restored.png) was visually reviewed. |
| Apple M2 Pro / MoltenVK | Requires a new hardware retest, including startup and shader/world rendering. |

Both fresh Windows suites cover day, night, rain, underwater, Nether, End, Overworld return, resize, shader reload, and window restoration while preserving the selected pack profile. They use the packaged production JAR with a separate diagnostic probe and no production renderer-development flags. Scenario completion and reviewed captures do not establish full OpenGL visual parity.

The atlas fixture uses actual `SpriteContents` animation states and stock shaders, with Sodium's normal visible-sprite tracking explicitly activated for the standalone test sprite. It runs at the title screen with `Globals` temporarily unavailable and restores the original buffer in `finally`. Prepare a fresh profile with `tests/release/prepare_atlas_smoke.py`; the test helper is excluded from the installable JAR. The earlier stopped fixture run is retained separately and is not the final verdict.

The [alpha2 validation record](ALPHA2.md) belongs to its documented JAR hash. The fresh alpha3 results above stand separately; they do not establish Mac hardware compatibility or an FPS improvement.

For new diagnostic profiles, `tests/release/prepare_probe_profiles.py --name-prefix alpha3 ...` creates distinct alpha3 run directories; its default prefix remains `alpha2` for reproducibility. The helper prepares files only. Runtime and source bundles are paired by exact hashes using [the release procedure](RELEASING.md); existing alpha1/alpha2 bundles remain unchanged.
