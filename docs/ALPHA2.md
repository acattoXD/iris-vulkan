# Alpha2 changes and validation

Version: `1.11.3-vulkan-alpha.2+mc26.2`. Final tested JAR SHA-256: `8dd670828ed25b66de747a7876c83be35810a7e3fad6a958aeaa8f22f753222a`.

Requirements are Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and exactly one of Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. This remains an unofficial experimental Iris fork. The compatibility version baseline also satisfies Sodium 0.9.2's Iris-version constraint.

## Changes

- **Complementary Unbound r5.9.1 Ultra:** its `puddle_sampler` is an unsigned 2D sampler for the custom `puddle_img` image. Alpha1 incorrectly also requested it through ordinary texture bindings, causing a missing-texture failure. Alpha2 keeps it in the storage descriptor path while retaining normal albedo bindings. The regression parses the actual pack declaration and checks native SPIR-V reflection. [Test and profile instructions](../tests/storage-2d/README.md).
- **Enchanted held items:** glint now follows the hand's projection and compressed depth when drawn in the hand pass. The shared glint pipeline keeps ordinary world-item behavior outside that pass. A brightness comparison must remain separate from whole-image compatibility claims.
- **Sodium shadow pipeline layout:** Iris's renamed shadow pipelines now retain Sodium's 20-byte push-constant range. Both supported Sodium versions previously added that range only to a `sodium` namespace. The omission reproduced the MakeUp 9.5e `medium` Metal error where `PC` and `IrisUniforms` both occupied buffer 0. Alpha2 declares the range before native pipeline creation; Vulkan UBO bindings are unchanged. [Exact layout and MSL regression](../tests/pipeline-layout/README.md). An Apple M2 Pro/MoltenVK rerun is still required.
- **Uniform uploads:** world draws use Minecraft's transient uniform memory, with aligned per-draw slices, in place of repeated dedicated GPU-buffer creation. Small uploads use bounded temporary storage. This reduces allocation and lifetime-management work without reusing a draw's mutable uniform bytes.
- **Pipeline warmup:** common world, shadow, entity and hand variants are compiled once through the real pipeline path before first use. Warmup passes have an explicit render area and perform no draw. This moves work away from later first-use stalls; it does not make compilation free or guarantee a higher steady FPS.
- **Sodium compatibility:** version-specific terrain hook targets cover the beta.3 compile baseline and 0.9.2. The new 0.9.2 arena aggregator now registers Iris's active geometry format instead of only Sodium's compact 20-byte format, correcting `Unsupported stride: 36`. Index allocations keep their separate 4-byte format, and existing geometry allocations retain their stride until renderer reconstruction. Both Sodium versions are accepted explicitly in the mod manifest; install only one.

## Validation status

| Check | Current evidence |
| --- | --- |
| r5.9.1 Ultra with final JAR / Sodium 0.9.2 | [Final run](../build/release-validation/arena-fixed/alpha2-r591-enchanted-final/native-probe-result.txt) completed 10 scenarios with `IRIS_WORLD_CAPTURED`; [packaged provenance](../build/release-validation/arena-fixed/alpha2-r591-enchanted-final/diagnostic-runtime-provenance.json) passed for the exact final JAR. [Rain resource evidence](../build/release-validation/arena-fixed/alpha2-r591-enchanted-final/evidence/rain-pack-resources.json) records six live images, including the 128×128 R8UI puddle image and sampler, plus advancing compute. Puddle texel contents were not read back. |
| Enchanted held/dropped items | The final r5.9.1 run records Wind Burst III on the held mace and an enchanted dropped mace. [Dedicated held/world capture](../build/release-validation/arena-fixed/alpha2-r591-enchanted-final/evidence/enchanted-held-world-b-enchanted-items.json) records both glint draw paths and `nativeHandDraw=1` for held glint; [reload evidence](../build/release-validation/arena-fixed/alpha2-r591-enchanted-final/evidence/shader-reload-enchanted-items.json) retains those paths. No impact attack was simulated. |
| Sodium push constants | Actual handler/struct tests pass with Fabric beta.3 and 0.9.2; 86 Minecraft/Sodium hook contracts pass. |
| MakeUp 9.5e `medium` with final JAR / Sodium 0.9.2 | [Final run](../build/release-validation/arena-fixed/alpha2-makeup-medium-final/native-probe-result.txt) completed 10 scenarios, with [439 named Iris classes and 18 resource origins verified](../build/release-validation/arena-fixed/alpha2-makeup-medium-final/diagnostic-runtime-provenance.json). Day/restored captures were reviewed without an obvious blank or corrupt scene. The actual shader also reproduces the reported MSL collision offline and passes the corrected assignment test. **M2/MoltenVK runtime remains unverified.** |
| Sodium 0.9.2 arena allocation | [Actual allocator regression](../tests/native-world/IrisSodiumArenaAllocatorTest.java) reproduces the original 36-byte failure and passes all 16 Iris format combinations, index/invalid-stride checks, and immutable allocation lifetime across reconstruction. The shader on/off toggle and destruction/reconstruction chain audit are CPU/bytecode checks; a live toggle was not separately exercised. |
| Final JAR / Sodium beta.3 without test mod | [Clean packaged run](../build/release-validation/alpha2-clean-beta3/packaged-runtime-provenance.json) verified 474 named Iris classes from the exact final JAR, with no probe classes or rendering-development JVM flags. Actual r5.9.1 Ultra world, shadow and hand rendering completed without a renderer failure. This test pre-acknowledged the disclosure in its isolated configuration; it was not a new UI test. |
| Fixed-scene performance | [Frozen alpha1 report](../build/benchmark/benchmark-baseline-alpha1/evidence/benchmark-report.json) and [earlier alpha2 candidate report](../build/benchmark/benchmark-candidate-alpha2/evidence/benchmark-report.json) used matching beta.3 inputs, but inactivity throttling and another active game confounded the runtime conditions. CPU world-submission wall time was tentatively lower; these runs establish no causal FPS/GPU improvement or consistently reduced stutter. Read the [performance findings](../build/benchmark/PERFORMANCE-FINDINGS.md), not just the [comparison output](../build/benchmark/alpha1-vs-alpha2-comparison.json). |

Both final 0.9.2 scenario runs cover day, night, rain, underwater, Nether, End, Overworld return, resize, shader reload and window restoration while preserving the selected profile. Completion and resource evidence do not establish full OpenGL visual parity.

The benchmark measured alpha2 JAR `d502151a4ce3ea6cbe6d3c534b8137bfc07e124f3906578b59138294397b3075`, before the final arena correction. It did **not** measure the final `8dd67082…` JAR identified above. Earlier beta.3 full-profile suites also used that earlier build; the final no-probe beta.3 run is recorded separately.

The earlier [work record](NATIVE-VULKAN-STATUS.md) preserves the r5.8.1 and Sildur GPU/camera/shadow evidence. Reports under `build/` and `fabric/run-*` are local validation artifacts; they are excluded from the corresponding source archive. Test instructions remain in that archive.

For r5.9.1, `ULTRA` uses `COLORED_LIGHTING=256`, `shadowDistance=224.0` and `RAIN_PUDDLES=2`. Its higher `COMPLEMENTARY` profile uses 512 lighting. Do not treat the r5.8.1 Ultra allocation figures or readback fixture as the exact r5.9.1 Ultra contract.

## Build

```powershell
.\gradlew.bat :fabric:build '-Pbuild.release=true'
```

The quotes keep the dotted Gradle property intact in PowerShell. Packaging and final artifact checks are described in [RELEASING.md](RELEASING.md). Preserve the final JAR hash when pairing it with source, checksums and install notes.
