# Complementary Ultra source audits and kernel fixtures

## Current Minecraft 26.3 / Complementary r5.9.1 kernel fixtures

`tests/ultra-compute/export-kernels.py` exports fresh CPU-only fixtures from an explicitly named isolated instance. It never starts Minecraft, creates a graphics device, or changes the source instance. It refuses an existing output directory. The test-only Iris facade supplies logging and a debug-disabled configuration; it must never be packaged in the mod.

The current fixture is `build/ultra-kernel-fixtures/comp-r5.9.1-256-mc26.3-02` in this port. It records the exact ZIP, saved shader settings, options and frozen Alpha11 JAR from `benchmark-alpha11-ultra-compute-01`. The target environment is explicitly reconstructed from the configured Windows/NVIDIA Vulkan26.3 context and installed Continuity, with current Iris version11105 and `MAX_COLOR_BUFFERS=32`; this is an offline source export, not a live ProgramSet capture. The resolved GLSL and property environments are recorded separately because production advertises all usable features for properties but only requested optional features for shader sources.

The selected `COLORED_LIGHTING=256` volume is256×128×256. Each dimension gets these exact-coverage variants:

| Local size | Dispatch groups | Invocations |
| --- | --- | --- |
| 8×8×8 (baseline) | 32×16×32 | 8,388,608 |
| 8×4×4 | 32×32×64 | 8,388,608 |
| 8×8×4 | 32×16×64 | 8,388,608 |
| 4×4×4 | 64×32×64 | 8,388,608 |
| 32×2×2 | 8×64×128 | 8,388,608 |

Only the local-size layout and unused `workGroups` declaration change; full shader expressions and global coverage are checked against the baseline. Each variant passes through the production Alpha11 native compute preparation and shaderc performance compiler. SPIRV-Cross verifies all288 bytes of the23-field native UBO layout. The optimized modules contain `GlobalInvocationId` and the compiler's unused `WorkgroupSize` constant, but no executable local/group/subgroup dependence, shared-memory variables, barriers or atomic operations. The pack's optional shared-memory code stays disabled. These are experiment inputs, not an automatic production retile policy.

`manifest.json` records input/output hashes, environment, material IDs, image/sampler aliasing, exact noise PNG, and fixture requirements. `kernels.json` records descriptors, std140 fields, image formats/dimensions, per-dimension modules, groups and builtin audits. `negative-skip-x0` is a deliberate mismatch control that skips the first output plane; it is labelled `expectedMismatch=true` and is never an optimization candidate.

GPU validation remains separate. It must test both `framemod2` parities, distinct old/new flood-fill contents, multiple camera orientations and positive/negative integer camera shifts. The direct previous-position fetches can go out of bounds near volume edges; the game does not enable robust image access, so shifted equality must explicitly exclude only undefined direct-read outputs and report those counts. Clamped-neighbor outputs remain defined. Stationary full-volume equality, a detected negative control, and meaningful GPU timing are required before considering a production change.

Run from this port directory, using a new output name each time:

```powershell
python tests/ultra-compute/export-kernels.py --jdk 'C:/Program Files/Java/jdk-25.0.3' --classpath-json '../../build/optimization-26.3/uniform-tests/classpath.json' --instance '../../build/port-26.3/benchmark-alpha11-ultra-compute-01' --output 'build/ultra-kernel-fixtures/comp-r5.9.1-256-mc26.3-03'
```

The `01` fixture is retained for traceability and has identical shader binaries, but used the older auditor's incorrect `MAX_COLOR_BUFFERS=16` provenance. Use `02` for the current experiment. The historical r5.8.1/512-volume evidence below is not interchangeable with this fixture.

## Historical Minecraft 26.2 / Complementary r5.8.1 audit

Ultra now executes actual native storage and compute work. On the RTX 5070, the isolated storage and 12-scene lifecycle checks pass, including High → Ultra → reload. A fresh 96-frame movement run also passes camera/GPU checks and shows aligned water reflections and a textured held sword. Full pack parity and performance improvement remain unverified.

## CPU source audit

This CPU audit uses production `IncludeGraph`, `ShaderPackOptions`, `PropertiesPreprocessor`, and `JcppProcessor`. The isolated `stubs/Iris.java` supplies only logging and disabled debug-file output; it is **not mod code** and must never enter a production classpath. No game, graphics context, Gradle task, or live process is launched. The explicit environment is Windows/Vulkan with Iris custom-image and SSBO support. Every one of the 64 generated High stages matches the existing runtime ProgramSet dump after comments and whitespace are removed.

Generated evidence is in `build/ultra-audit/requirements.json`, `HIGH`, and `ULTRA`. Source pack was `ComplementaryUnbound_r5.8.1 (1).zip`; selected Ultra options are exactly `build/evidence/ultra-crash/rejected-ultra-options.properties`. The saved options agree with the pack's Ultra profile. This evidence proves source/resource contracts, not successful GPU execution.

## Active Ultra schedule

Each of `world0`, `world-1`, and `world1` has just one active compute shader: `shadowcomp.csh`. There are no setup/begin/prepare/deferred/composite/final/shadow computes in this preset. `shadowcomp` runs after the complete shadow geometry pass and before prepare/main world rendering, as in `ShadowRenderer`'s call to its composite renderer. It is compute-only: 64×32×64 workgroups with 8×8×8 local size, exactly covering 512×256×512 voxels.

1. At initial allocation zero all five images and SSBO 0.
2. At the start of every frame zero only `voxel_img`, `wsr_img`, and `wsr_lod_img`.
3. Shadow vertex execution populates those three images and SSBO 0.
4. Make vertex storage writes visible to compute image/sampler reads and subsequent graphics reads.
5. Dispatch `shadowcomp`, reading the prior floodfill volume and `voxel_sampler`, writing the opposite floodfill volume.
6. Make compute image writes visible to subsequent texture fetches in world/screen shaders.
7. Preserve floodfill images and SSBO 0 until a pack/world lifetime reset. Never clear them every frame.

`framemod2=0`: sample `floodfill_sampler`, write `floodfill_img_copy`. `framemod2=1`: sample `floodfill_sampler_copy`, write `floodfill_img`. Parity and camera/previous-camera uniforms must agree with the main shaders. The compute shader has a camera-dependent half-volume update optimization, so visible lighting requires multiple frames of history.

## Allocations and alias contracts

| Image | Sampler alias, same physical image | Format | Extent | MiB | Clear each frame |
| --- | --- | --- | --- | ---: | --- |
| voxel_img | voxel_sampler | R16UI | 512×256×512 | 128 | yes |
| floodfill_img | floodfill_sampler | RGBA16F | 512×256×512 | 512 | no |
| floodfill_img_copy | floodfill_sampler_copy | RGBA16F | 512×256×512 | 512 | no |
| wsr_img | wsr_sampler | R16UI | 512×64×512 | 32 | yes |
| wsr_lod_img | wsr_lod_sampler | R8UI | 128×16×128 | 0.25 | yes |

All image formats require matching storage-image qualifiers. Integer images use nearest sampling; float images use linear sampling. Clamp to edge on all axes, a single mip level, and no comparison sampler match the GL implementation. Sampled and storage descriptors must alias the same allocation; similarly named independent textures are incorrect.

SSBO binding **0** is **810,549,248 bytes (773 MiB)**. Its exact interface is `layout(std430, binding=0) buffer blockDataBuffer { uvec4 data[]; } blockDataSSBO;`, readonly in `composite.fsh` and read/write in `shadow.vsh`. Array stride is 16 bytes, for `(513*64*512 + 65*512*512 + 513*512*64)` unique voxel faces. The vertex shader reads previous face lightmap data, so this buffer must persist. GL initializes this declared no-content buffer to zero once. No packed C/Java struct reconstruction is needed or appropriate.

Total advanced storage is **2,052,325,376 bytes = 1,957.25 MiB = 1.911377 GiB**, excluding allocator overhead and all standard screen, shadow, terrain, atlas, and game allocations. High allocates none of these resources. The GPU probes below verified these allocations; total process VRAM alone is not proof of feature correctness. Both these High and Ultra configurations still declare a 2048² shadow map; the Ultra difference is not simply a larger shadow texture.

The defaults `RAIN_PUDDLES=0` and `WORLD_SPACE_PLAYER_REF=-1` mean there is no puddle image, playerAtlas image, or SSBO 3 in this selected Ultra preset. Supporting optional future settings is a separate contract.

## Vertex ABI and resources

Only `shadow.vsh` writes storage images in the selected preset. Its reachable `main` calls the voxel functions only when `gl_VertexID % 4 == 0`. It needs faithful quad vertex IDs, `mc_Entity`, `at_midBlock` (scaled by 1/64), `mc_midTexCoord`, position/normal/UV/color/lightmap, atlas size, shadow/inverse matrices, and camera history. Do not remove vertex side effects when color/depth writes are culled. Device creation must enable `vertexPipelineStoresAndAtomics` and the required extended storage image formats. Physical format support checks alone do not enable these Vulkan features.

The native feature hook now enables supported `vertexPipelineStoresAndAtomics`, `fragmentStoresAndAtomics`, `shaderStorageImageExtendedFormats`, and `robustBufferAccess` at device creation. The successful GPU runs logged all four as enabled (`vertexStores=true, fragmentStores=true, extendedImageFormats=true, robustBuffers=true`).

Colored-light voxelization accepts render stages 8 (solid), 9 (cutout mipped), 10 (cutout), and 17 (translucent). WSR accepts 8/9/10. The current shaders exclude water and many non-solid materials from occupancy while retaining selected emitting materials. `shadow.culling=reversed` requests a voxelization-safe caster set; native distance-based caster enumeration must cover loaded geometry throughout the relevant volume, including geometry outside the screen frustum.

There are no active fragment-stage image writes in the exact saved preset. `composite.fsh` does read SSBO 0 and WSR images for world-space reflections.

`customTexture.textureAtlas = minecraft:textures/atlas/blocks.png` is `ResourceData`, not a PNG bundled by the pack. MC 26.2's `AtlasManager` registers its stitched `TextureAtlas` with `TextureManager` under this exact identifier. Re-query `Minecraft.getInstance().getTextureManager().getTexture(identifier)` when binding, borrow its `getTextureView()` and `getSampler()`, and preserve its mip chain. The shader uses both `textureSize(textureAtlas,0)` and `texture2DLod(textureAtlas,...,lod)`. Never own/close the atlas as pack storage, and do not keep a stale view across a resource reload.

GL's `ComputeProgram.dispatch` barriers before dispatch (concurrent compute defaults false), `CompositeRenderer` barriers after compute, and `Program.use` barriers before graphics all include image-access, texture-fetch, and shader-storage visibility. A Vulkan implementation needs equivalent dependencies across vertex, compute, fragment, transfer, and frame reuse. Storage-image descriptors in GENERAL and a full memory/execution barrier outside render passes are a conservative correct starting point.

## Concrete validation scene

Use an isolated probe world with a roofed 21×21 white-concrete room, fixed camera/time/weather, and no daylight. Place a verdant froglight and pearlescent froglight the same distance from two neutral wall patches; both have comparable vanilla light intensity but different pack light colors. Add a soul lantern/torch as a cyan source and a redstone torch as a red source. Keep each source within 8 blocks of its wall patch, initially in front of the camera. Warm up at least 120 rendered frames to allow the half-volume ping-pong floodfill to settle, then capture the same camera under High and Ultra.

Validate nonzero voxel occupancy and expected light IDs (verdant=8, pearlescent=9, soul torch=28/soul lantern=29, redstone torch=35), then nonzero RGB floodfill values at source and nearby air voxels, with frame-to-frame alternating output. A screenshot should show green/pink/cyan/red spill on neutral surfaces that High cannot reproduce. Turn the camera so sources leave the screen while their illuminated surfaces remain visible; world-space illumination should survive. Walk across an integer camera-position boundary to exercise reprojection.

For WSR, place a shallow water rectangle and an off-screen wall patterned in bright contrasting blocks. Aim at the water so the wall is outside the main view but should be reflected. Check nonzero `wsr_img`, `wsr_lod_img`, and face records, correct stitched-atlas descriptor, and visible off-screen reflection. Test resource reload and dimension return to exclude stale atlas bindings or cross-dimension history. This is a proposed validation procedure; no such visual validation was performed by the offline audit.

## Re-run in this workspace

The cached classpath is retained in `build/ultra-audit/compile.args`, `run.args`, and `high-run.args`. Run JDK 25 `javac @build/ultra-audit/compile.args`, then `java @build/ultra-audit/run.args` and `java @build/ultra-audit/high-run.args`, followed by `python tests/ultra-audit/summarize.py`. The helper classes remain in `build/ultra-audit/classes` only. Do not use that classpath for Minecraft.

## Isolated native Ultra GPU probe

The probe harness accepts `-PirisProbeUltra` together with `-PirisProbeWorld`, an explicit `-PirisProbePack`, and a dedicated `-PirisProbeRunDirectory` containing `ultra`. It writes the exact eight saved Ultra overrides into that isolated run's pack sidecar **before the initial pack load** and sets `iris.vulkan.storageDevelopment=true`. It rejects mixing this mode with interactive/manual, motion, or the old rejection-only `-PirisProbeQualityChange` probe. It can run the complete scenario sequence with `-PirisProbeScenarios`.

For an existing interactive probe invocation, add **`-PirisProbeStorage`**, not `-PirisProbeUltra`. The storage flag enables the implementation without forcing the Ultra preset or replacing the selected shader settings. The interactive launch must still supply `-PirisProbeOpenWorld` for its existing world.

Example for a visible, isolated game run:

```powershell
.\gradlew.bat :fabric:runClient -I tests/native-world/isolated-launch.init.gradle -PirisProbe -PirisProbeWorld -PirisProbeUltra -PirisProbeRunDirectory=run-vulkan-ultra-storage '-PirisProbePack=C:/path/to/ComplementaryUnbound_r5.8.1.zip' --console=plain --no-daemon
```

For the 12-scene run, add `-PirisProbeScenarios` and use `-PirisProbeRunDirectory=run-vulkan-ultra-scenarios`. Its sequence is day, night, rain, underwater, Nether, End, Overworld return, resize, High, Ultra, shader reload, and restored window. High/Ultra changes use the actual queued-options reload path, then assert the profile, new pack identity, storage lifetime, and current-pipeline compute dispatches after settling. Every scenario saves a screenshot and `*-state.json` with profile and dispatch information.

For the separate visible water/held-item movement probe, use `-PirisProbeUltraMotion` with `-PirisProbeUltra` and a dedicated `-PirisProbeRunDirectory=run-vulkan-ultra-motion`, without `-PirisProbeScenarios`. It keeps the audited Ultra options active, walks across integer camera boundaries with view bobbing, turns, and returns to the original water view. It saves per-frame matrices, 16 screenshots, and three additional GPU storage readbacks.

Use the checked-in `tests/native-world/isolated-launch.init.gradle` for serialized tests alongside an open manual game. It relocates both JAR outputs and Loom's actual runtime classpath. Earlier local init scripts moved the JAR outputs alone and could leave the launch using a stale common JAR. `build/isolated-runtime-classpath.txt` records the selected paths, and each probe saves loaded class source paths and class-byte SHA-256 hashes in `evidence/loaded-build.json`. Old captures without verified matching provenance do not validate later source edits.

The disposable scene adds white receiving surfaces, verdant/pearlescent froglights, a soul lantern, and a redstone torch, then warms up for 250 frames. At two captures, `NativeUltraReadback` verifies the eight options, exact image/SSBO contracts, live storage/sampler alias identity, physical Vulkan handles, current pipeline dispatch count, and the latest successful `shadowcomp` dispatch dimensions. It copies centred 48×24×48 crops from each large image, a 12×6×12 WSR LOD crop, and thirty 16-byte SSBO face records into approximately 1.1 MiB of staging memory. Copies run outside every Vulkan render pass and complete through a submitted GPU fence, polled with zero timeout. No pack image or SSBO is modified by readback.

`first-ultra-storage-report.json` and `second-ultra-storage-report.json` include counts, crop coordinates, sample addresses, hashes, camera, profile, and dispatch evidence. Pass criteria require nonzero values in every image and at least one face record; actual verdant/pearlescent voxel IDs 8/9; finite, colored light in both floodfill volumes; and advancing current-pipeline and process dispatch counts. `IRIS_ULTRA_STORAGE_ACTIVITY_PASS` only means these GPU activity checks passed; screenshots still require visual inspection. The window title uses the actual loaded pack for both automated and manual probes.

Direct JDK 25 compilation of all probe sources plus `NativeUltraReadbackTest` passed. `build/ultra-audit/probe-compile.args` and `probe-test.args` reproduce the check; CPU tests cover wrong/High options, missing/wrong dispatch, R16UI/colorwheel IDs, RGBA16F, NaN rejection, zero-storage rejection, and exact SSBO endpoint bounds.

## Recorded GPU and lifecycle results

Earlier evidence is in `fabric/run-vulkan-ultra-storage/evidence` and `fabric/run-vulkan-ultra-scenarios/evidence`, with logs `build/ultra-storage-live.txt` and `build/ultra-scenarios-live.txt`. Both runs used the corrected launch classpath and saved `loaded-build.json`. Both completed with `IRIS_ULTRA_STORAGE_ACTIVITY_PASS`, two successful cropped GPU readbacks, exact 1,957.25 MiB storage allocations, and actual `shadowcomp` dispatches of 64×32×64 workgroups with 8×8×8 local size.

The scenario run completed all 12 runtime states without a probe exception. `quality-high-state.json` records `Profile: HIGH (+0 options changed by user)`, replaced pack identity, no custom images/buffers, inactive native storage, and zero current-pipeline compute dispatches. `quality-ultra-state.json` records the exact Ultra profile, another replaced pack, five custom images and one buffer, active storage, and 119 compute dispatches. The subsequent `shader-reload-state.json` records a recreated Ultra pack and active storage/compute again. The count 119 is an observation, not a required fixed total.

An earlier 12-scene capture set exposed a solid-color rendering regression. An A/B run isolated new unused-fragment-input pruning while retaining the uniform-layout cache. The corrected implementation preserves inputs matched by vertex outputs so Mojang's SPIR-V rebinder keeps their locations aligned. The replacement September 13 15:34–15:35 captures in `run-vulkan-ultra-scenarios/evidence` were reviewed and show the scene throughout the scenario cycle.

The fresh `fabric/run-vulkan-ultra-current/evidence` repeats all 12 states and GPU activity checks with the current camera/depth fixes. The fixed-Ultra [motion report](../../fabric/run-vulkan-ultra-motion/evidence/ultra-motion-report.json) records 96 frames, 48 bob frames, 16 screenshots, passing camera invariants, and three additional passing readbacks at dispatch counts 621, 669, and 693. The initial two readbacks also pass. Reviewed water-before, walking, turned, and return captures show stable water/reflection alignment and a textured diamond sword, without the earlier severe ghosting/noise. Part of the wall remains visible while turned, so fully off-screen WSR parity still needs a stricter scene.

The [fresh transformed Ultra hand shader](../../fabric/run-vulkan-ultra-motion/iris-vulkan-dumps/iris_hand_cutout_diffuse_minecraft_pipeline_item_cutout.frag.glsl) contains `vec4 lightVolume = vec4(0.0)` in `GetComplexLightVolume` at line 863. This confirms the accumulator correction reached the live hand path; the [SPIR-V regression](../../build/ultra-current-audit/hand-spirv.txt) verifies zero initialization before the first load. No performance gain or full pack parity is claimed.

Use separately obtained shader-pack ZIPs as local test inputs. Do not include Complementary, Sildur's, or other third-party shader archives in released mod artifacts; their own distribution terms apply.
