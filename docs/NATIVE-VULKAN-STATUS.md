# Native Vulkan work record — 2026-09-13

Final Windows-tested alpha2: `1.11.3-vulkan-alpha.2+mc26.2`, SHA-256 `8dd670828ed25b66de747a7876c83be35810a7e3fad6a958aeaa8f22f753222a`, targeting Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. The [alpha2 change/status sheet](ALPHA2.md) records final 0.9.2 ten-scenario runs for r5.9.1 Ultra and MakeUp 9.5e `medium`, plus the final clean beta.3 launch. It covers the puddle, glint, arena-stride and push-constant fixes, transient uploads and warmup. Puddle allocation/dispatch evidence is not a texel readback. The Metal collision is reproduced offline, but the M2 runtime correction remains unverified.

The historical GPU evidence below targets Complementary Unbound **r5.8.1** unless another version is named. Its High/Ultra storage and lifecycle results do not automatically establish newer pack, preset, driver or device compatibility.

## Implementation status

The fork began from `fangbm/iris4vulkan` commit `424e96796d21d0810aadbc609db698c3c3a8b342`, an existing native screen-pass experiment. The early final-only fixture and the initial report of 19 unsupported world routes describe that starting point, not the current implementation. Official Iris branch `26.2` was inspected at `4a9c8d1c661459143d91b8c8057905e5a7962285` for the corresponding OpenGL behavior.

The current development path executes terrain, entities, held items, sky/weather/particles, shadow geometry, and begin/prepare/deferred/composite/final stages through Minecraft's Vulkan device. It handles sparse logical color targets 0–31, MRT routing with an eight-attachment limit, shadow textures, material/vertex inputs, custom uniforms, and texture aliases. Shader-pack depth reads use forward R32 snapshots while the main Vulkan renderer retains reverse-Z; shadow maps use forward depth.

Ultra adds real storage-image and SSBO descriptors to graphics stages, compute pipelines and dispatches, synchronization between shadow writes, compute, and later reads, and persistent volume history. `textureAtlas` borrows Minecraft's live stitched atlas and sampler instead of loading a static pack PNG.

Device creation enables supported `vertexPipelineStoresAndAtomics`, `fragmentStoresAndAtomics`, `shaderStorageImageExtendedFormats`, and `robustBufferAccess` features. The verified RTX 5070 run logged all four as enabled. Physical support checks and descriptor validation remain necessary; changing a feature label alone would not enable the Vulkan feature.

## Actual Ultra GPU evidence

The tested preset is Complementary Unbound r5.8.1's exact Ultra profile, with `RAIN_PUDDLES=0` and `WORLD_SPACE_PLAYER_REF=-1`. Optional puddle/player-reflection settings are outside this completed test contract.

| Resource | Format / extent | Allocation |
| --- | --- | ---: |
| `voxel_img` | R16UI, 512×256×512 | 128 MiB |
| `floodfill_img` | RGBA16F, 512×256×512 | 512 MiB |
| `floodfill_img_copy` | RGBA16F, 512×256×512 | 512 MiB |
| `wsr_img` | R16UI, 512×64×512 | 32 MiB |
| `wsr_lod_img` | R8UI, 128×16×128 | 0.25 MiB |
| SSBO 0 | 810,549,248 bytes, 16-byte face records | 773 MiB |

Total advanced storage is **2,052,325,376 bytes / 1,957.25 MiB**, excluding allocator overhead, ordinary render targets, shadows, terrain, atlases, and other game allocations. High uses none of this advanced storage.

The sole active compute program is `shadowcomp`: **64×32×64 workgroups** with **8×8×8 local size**, after shadow voxelization and before prepare/main world rendering. The two floodfill images alternate reads/writes across frames. Frame-cleared occupancy images and persistent floodfill/SSBO resources follow the pack's declarations.

Both current isolated runs completed with `IRIS_ULTRA_STORAGE_ACTIVITY_PASS`:

- [fabric/run-vulkan-ultra-storage](../fabric/run-vulkan-ultra-storage), log [build/ultra-storage-live.txt](../build/ultra-storage-live.txt).
- [fabric/run-vulkan-ultra-scenarios](../fabric/run-vulkan-ultra-scenarios), log [build/ultra-scenarios-live.txt](../build/ultra-scenarios-live.txt).

Their `evidence/first-ultra-storage-report.json` and `second-ultra-storage-report.json` contain actual fence-completed copies of five image crops and 30 SSBO face records. Checks cover native handles, storage/sampler alias identity, exact allocations, nonzero occupancy/face data, expected light IDs, finite colored floodfill samples, and advancing dispatch counts. This establishes sampled GPU activity, not complete image correctness or off-screen reflection parity.

An earlier 12-scene capture set exposed a solid-color regression despite passing GPU activity checks. An A/B test isolated new unused-fragment-input pruning as the cause while retaining the uniform-layout cache. The corrected pruning preserves fragment inputs matched by vertex outputs, keeping Mojang's SPIR-V interface locations aligned. The replacement Ultra captures from September 13 at 15:34–15:35 in `run-vulkan-ultra-scenarios` were reviewed and show the scene again across the scenario cycle. This resolves the solid-color regression; the GPU readbacks and runtime checks retain their narrower scope.

## Runtime scenarios

The Ultra scenario run saved all **12** scenarios: day, night, rain, underwater, Nether, End, Overworld return, resized window, High quality, Ultra quality, shader reload, and restored window. Each has a screenshot and `*-state.json` under its evidence directory.

The quality changes use `Iris.queueShaderPackOptionsFromProperties` and `Iris.reload`, the same path used by the profile UI. At capture:

| State | Profile | Pack replaced | Advanced storage | Current-pipeline compute dispatches |
| --- | --- | --- | --- | ---: |
| `quality-high` | HIGH, zero changed options | yes | inactive; no declared images/buffers | 0 |
| `quality-ultra` | ULTRA, zero changed options | yes | five images and SSBO 0 live | 119 |
| `shader-reload` | ULTRA, zero changed options | yes | active | 119 |

The counts are observations from this run, not fixed expected totals. Assertions require zero compute/storage on High and live resources plus positive compute activity on the recreated Ultra pipeline. Dimension transitions, resizing, and reload completed without a probe failure. The corrected capture set shows scene rendering after those transitions; complete matching-scene OpenGL parity remains unverified.

## Current Ultra movement and held-item results

The fresh [run-vulkan-ultra-current](../fabric/run-vulkan-ultra-current/evidence) repeated the GPU activity and 12-scene lifecycle checks with the current camera and depth fixes, including High → Ultra and shader reload. Reviewed scene and night captures show the diamond sword's texture and colors.

The fixed-Ultra [movement report](../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-report.json) records **96 frames**, **48 with nonzero bob**, and **16 sequential screenshots**. All camera invariants pass: pure-projection deviation is zero and the maximum projection/model-view product error is 5.9604645e-8. Its original two GPU readbacks and three additional movement readbacks pass; the additional samples record advancing dispatch counts of **621 → 669 → 693** before movement, after crossing integer camera boundaries and turning, and on returning.

Reviewed [water-before frame 24](../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-water-before-024.png), [walking frame 48](../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-boundary-walk-048.png), [turned frame 72](../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-lights-offscreen-072.png), and [return frame 96](../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-water-return-096.png) show aligned water/reflections and a textured blade, without the previously observed severe noise or ghosting. The patterned wall remains partly visible in the turned view, so this does not establish fully off-screen WSR parity.

The black held-item path contained an uninitialized `lightVolume` accumulator in Complementary's `GetComplexLightVolume`. The narrow compatibility patch supplies `vec4(0.0)`. The [fresh live Ultra hand fragment](../fabric/run-vulkan-ultra-motion/iris-vulkan-dumps/iris_hand_cutout_diffuse_minecraft_pipeline_item_cutout.frag.glsl) contains that initializer at line 863, confirming the correction reached the transformed shader. The [current-class SPIR-V regression](../build/ultra-current-audit/hand-spirv.txt) verifies the zero store before the first accumulator load; [frame-history checks](../build/ultra-current-audit/history.txt) also pass. Full pack parity and a performance improvement remain unverified.

## Sildur scene rendering and camera regression

Sildur's Vibrant Shaders **v2.01 High** passes the native compile/launch barriers, including live fog distances and its unused fragment declarations. The reviewed `first.png` and `second.png` captures in [fabric/run-vulkan-sildurs-high/evidence](../fabric/run-vulkan-sildurs-high/evidence), saved September 13 at 15:32, show the world after the same pruning correction. The previous solid-cyan image is no longer the current result. The folder was later reused for an interactive launch, which replaced `loaded-build.json`; that later provenance file should not be attributed to the 15:32 captures. The isolated OpenGL reference remains in [fabric/run-opengl-sildurs-reference](../fabric/run-opengl-sildurs-reference).

The user's subsequent walking/turning recording exposed a separate camera-matrix bug. `IrisMixinPlugin` excluded `MixinModelViewBobbing` on Vulkan, and the hook's quick shader-active check recognized only the OpenGL pipeline. Consequently, walking, hurt, and nausea effects stayed in the projection matrix. Sildur reconstructs positions from selected inverse-projection entries and expects those effects in model-view, as provided by the OpenGL path. The fix enables the shared camera mixin for Vulkan and uses the public shader-pack-active predicate in that hook. The projection/model-view product is preserved.

The actual baseline and corrected runs each record **160 rendered frames**, including **80 with nonzero view bob**, and 24 screenshots:

| Run | Matrix invariants | Maximum pure-projection deviation | Maximum projection/model-view product error |
| --- | --- | ---: | ---: |
| [Before](../fabric/run-vulkan-camera-before/evidence/camera-motion-report.json), record-only baseline | failed | 0.17129356 | 1.1920929e-7 |
| [After](../fabric/run-vulkan-camera-after/evidence/camera-motion-report.json), current fix | passed | 0 | 5.9604645e-8 |

The baseline reproduces the incorrect matrix split even though the combined clip transform remains correct. Both runs save their own matching `loaded-build.json` provenance. Before/after inspection of `camera-walk-100.png` shows the old misplaced sky, reflections, and doubled chest corrected to aligned geometry and reflections. Corrected sequential `camera-walk-turn-139.png` and `camera-walk-turn-140.png` show continuous sky without the rectangular blocks. This validates the reproduced Sildur walking/turning defect; broader reflection behavior and full matching-scene OpenGL parity remain outside that result.

CPU regressions cover the production backend filter, camera hook/API gate, reverse-depth conversion, sparse Sildur reconstruction, and installed Minecraft/Sodium hook targets. A separate audit compiled all 27 available Sildur prepared shader pairs through Mojang's SPIR-V frontend and checked 212 fragment-input locations after rebinding: zero mismatches or absent inputs. See [tests/projection/README.md](../tests/projection/README.md) and [build/camera-shake/interface-audit.txt](../build/camera-shake/interface-audit.txt).

The camera-change `:fabric:build` passed; its log is [build/camera-shake/final-build.txt](../build/camera-shake/final-build.txt).

## Sildur player-shadow depth timing

The giant rectangular ground shadow came from `depthtex2` remaining at its clear value of 1 during deferred shading. Sildur tests `depthtex2 > depthtex0` to identify hand pixels and multiplies their reconstructed shadow positions by **6.5**. Native hand rendering occurs later, so ordinary floor pixels incorrectly took that hand-shadow path. The native frame callback now snapshots opaque depth into `depthtex2` alongside `depthtex0` and `depthtex1` before deferred, while keeping the later pre-hand refresh.

The [isolated baseline](../fabric/run-vulkan-player-shadow-isolated/evidence/player-shadow-report.json) reproduced the rectangle on a bare floor after removing the original structures and nonplayer entities. The [corrected run](../fabric/run-vulkan-player-shadow-fixed/evidence/player-shadow-report.json) completed four third-person captures and four GPU shadow readbacks: south, east, player moved three blocks east, and the moved view with sunlight changed from time 4000 to 1000. Reviewed images show the rectangle gone and a normal body-and-sword shadow that follows the player and lengthens at the earlier sun angle. Direct comparisons are available in the [baseline east image](../fabric/run-vulkan-player-shadow-isolated/evidence/player-shadow-east.png), [corrected east image](../fabric/run-vulkan-player-shadow-fixed/evidence/player-shadow-east.png), and [corrected early-sun image](../fabric/run-vulkan-player-shadow-fixed/evidence/player-shadow-east-moved-early-sun.png).

The focused [deferred-depth regression](../tests/native-world/IrisVulkanDeferredDepthRegression.java) executes the compiled production callbacks with GPU side effects replaced by a depth-buffer fixture. It reproduces the failure against the pre-fix JAR and passes against the corrected classes, checking both deferred classification and the later final hand/terrain/sky mask. Evidence is in [baseline.txt](../build/deferred-depth-tests/baseline.txt) and [current.txt](../build/deferred-depth-tests/current.txt). The final `:fabric:build` passed in [build/deferred-depth-tests/final-build.txt](../build/deferred-depth-tests/final-build.txt). These captures verify the reported rectangular player-shadow defect; broader pack parity and performance remain unverified.

## Launch provenance correction

An earlier local isolation init script relocated JAR outputs while Loom retained a previously resolved path to the old common JAR. Some test runs therefore executed stale code despite successful recompilation. Their results must not be used to establish the behavior of later source edits.

The canonical [tests/native-world/isolated-launch.init.gradle](../tests/native-world/isolated-launch.init.gradle) now updates both JAR destinations and `runClient`'s actual runtime classpath. It writes `build/isolated-runtime-classpath.txt`. Probes also record loaded class source locations and class-byte SHA-256 hashes in `evidence/loaded-build.json`. The Ultra results above use the corrected launch path and have these records. Re-check provenance when comparing older captures or reusing a run folder.

## Remaining visual and performance work

- Extend motion/reflection comparisons beyond the verified Sildur camera and Ultra water probes, including a reflector whose source geometry is fully off-screen. Earlier investigations include captures affected by the stale-JAR problem and require confirmation with current build hashes.
- Shadow/near-surface darkening, held-item transparency, particles, and fire have received implementation fixes, but complete scene-by-scene OpenGL parity has not been established.
- Sildur's static scene rendering is restored after pruning correction; full pack compatibility and matching-scene OpenGL parity remain unverified.
- No general FPS uplift is claimed. The alpha1/alpha2 measurements used identical beta.3 inputs, but inactivity throttling and a separate active game confounded the runtime conditions. The measured alpha2 hash begins `d502151a`; the final arena-corrected JAR begins `8dd67082` and was not benchmarked. Tentatively lower CPU world-submission wall time does not establish a causal FPS/GPU gain; see [performance findings](../build/benchmark/PERFORMANCE-FINDINGS.md). Broader scenes, drivers and hardware still need controlled evaluation. A larger heap alone is not evidence of a renderer improvement.
- Distant Horizons and shader features/settings outside the audited resource contract remain unsupported or unverified.

## Running the development path

Use JDK 25 and the canonical isolation init script for development probes. A release build uses `.\gradlew.bat :fabric:build '-Pbuild.release=true'` in PowerShell. [tests/ultra-audit/README.md](../tests/ultra-audit/README.md) contains the r5.8.1 automated Ultra command and evidence contracts.

Normal installable-alpha Vulkan launches enable native world and storage rendering without developer JVM flags. `-PirisProbeWorld` selects the isolated world test. `-PirisProbeUltra` force-selects the exact older r5.8.1 Ultra options and cannot be mixed with the interactive/manual probe. For r5.9.1, use the [actual-profile extraction and generic resource/scenario probe](../tests/storage-2d/README.md), without the older preset flag. `-PirisProbeStorage` retains the chosen profile in an isolated interactive test.

Third-party shader ZIPs are separate local test inputs. Do not include them in mod releases; retain the fork's upstream and dependency licenses and obtain shader packs through their own authorized distributions.
