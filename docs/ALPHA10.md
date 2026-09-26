# Alpha10: shared depth and direct uniform serialization

Version `1.11.5-vulkan-alpha.10+mc26.3`, tested JAR SHA256 `daf7494dcadada8ae93799ede0f92e0e3e991afa42e9df66bbfbe97c9e03ed06`.

**The controlled Minecraft comparison passed.** The exact JAR above completed a real Complementary High world test and60-degree camera turn with matching baseline inputs and focus. It also passes the CPU integration/serialization checks and headless Vulkan pixel/timing tests below. The measured gain applies to this scene and hardware; full pack/device compatibility and the reported glass motion flicker remain unresolved.

## Changes

- Serialize already-updated custom uniform values directly into the owned std140 upload buffer. Native matrix/frame/entity/hand/atlas/fog values keep their existing priority. Public `lookup()` still returns independent snapshots. vec3/ivec3 write exactly12 bytes, preserving a following scalar.
- Ignore simple unused sampler declarations when planning feedback snapshots. Function uses and metadata queries still count as reads; unknown, array and multiple-declaration forms remain conservative. The shader compiler receives the original source unchanged.
- Share the identical opaque depth result among logical depth0/1/2 until a later write needs a different value. Stable physical owners are separate from logical views. Later writes never overwrite another live logical value or sample their own output. Maximum allocation remains three owners plus one optional merge scratch image. Initial frame clears and the original single-output/hand-merge shaders are unchanged.

## Verification

- Full Fabric release build passes.
- Direct serialization:768 captures across43 fields and84 supplier-count entries match the frozen Alpha9 JAR byte-for-byte. Tests include all18 native matrices, frame values, entity/hand state, atlas/fog values, scalar/vector/matrix custom types and provider replacement.29 public API/type/cursor/padding tests pass.900,000 isolated field writes allocate103.2MB before versus zero after; this is a CPU microbenchmark, not FPS.
- Feedback plan:165 alias, source-stage, unknown-contract, custom-texture, unused-declaration and boundary checks pass.
- Depth slots:97,656 exhaustive operation traces and38,348 checks against actual compiled gbuffer depth methods pass, covering no hand, repeated merges, frame reset,12 resize generations and exactly-once cleanup. The actual compiled Sildur/deferred/hand callback regression passes.
- Independent raw Vulkan: the actual compiled slot planner and exact production shader constants match an independent CPU/GPU reference across29 stages and641,433,600 checked float pixels. The test includes no-hand, empty-hand, repeated hand merges and a fresh frame. It creates no surface, swapchain, SDL/GLFW or desktop window.

At2560x1440 on RTX5070/NVIDIA616.92,24 alternating GPU timing rounds measured the opaque depth boundary at median **0.05832675ms for three conversions versus0.018354ms for one shared conversion** (approximately0.040ms saved for this operation). Both variants use the same first output allocation; three initial frame clears remain outside this measured boundary. This is not whole-game FPS or complete renderer-integration proof. The Khronos validation layer was unavailable; pixel equality and successful calls do not substitute for validation-layer coverage.

A three-output MRT alternative was rejected after two GPU tests showed approximately10–12% slower execution. One conversion plus two image copies was also rejected at approximately24% slower. Their source/tests remain as explicitly rejected fixtures, outside production rendering.

## Controlled Minecraft result

The user manually completed `benchmark-controlled-alpha8-high-1440p-03` and `benchmark-controlled-alpha10-high-1440p-03` on 2026-09-15. Both used Minecraft 26.3, Java 25.0.3, RTX 5070 / NVIDIA 616.92, the same 20 companion mods, Complementary Unbound r5.9.1 default HIGH preset, 2560x1440, render/simulation distances of 12 chunks, FOV 70 and a 260 FPS cap. The fixture contains glass/panes, named entities, textured falling blocks and an enchanted held item. The shader ZIP/options, copied world, non-Iris artifacts, settings and JVM inputs match.

Each run completed three 20-second measurement windows after warmup, entirely focused with zero observed focus changes. The limiter remained 260:NONE and GPU timing coverage was 100%. The strict comparison reports `matchedInputs=true`, `exploratory=false`; no subsetting or relaxed guard was used. The separate probe 1.4.0 performs no OS input or focus control.

| Median of per-window metrics | Alpha8 | Alpha10 | Reduction |
|---|---:|---:|---:|
| Mean frame interval |4.4204ms|3.8535ms|12.8%|
| Mean GPU world time |4.2644ms|3.7129ms|12.9%|
| Mean render-thread CPU time |2.8832ms|2.1221ms|26.4%|
| Mean world submission time |2.2493ms|1.5764ms|29.9%|
| Frame interval p99 |5.1535ms|4.5692ms|11.3%|

The mean intervals correspond to approximately226.2→259.5FPS (14.7% higher), near the fixed260 cap. Both after/turn screenshot pairs were reviewed and retain the scene's glass, blocks, labels and glint without an obvious new visual regression. Static captures do not prove continuous-motion stability.

The raw sample sets contain13,568 baseline frames and15,585 candidate frames. Overall worst interval was29.6035ms versus17.4166ms; intervals above8ms were12 versus3; above16ms were1 versus2; neither run exceeded33ms. The comparison's `.max` field is the median of three window maxima, not the overall maximum, and must not be used as a general worst-hitch claim. Windows per-frame CPU times are quantized; only their whole-window means support the reported CPU comparison.

This is one process pair with three windows each, one fixed scene and one GPU. Reverse-order/repeated launches, other scenes and devices can strengthen confidence. It does not prove uniformly reduced stutter or a universal15% FPS increase. Alpha9's Ultra/Mineek and other lifecycle checks remain evidence for Alpha9; they were not all repeated on Alpha10.

The earlier mismatched-focus runs and the initial launch/focus-wait failures remain preserved and were not used for the speedup claim. The successful comparison is `build/optimization-26.3/controlled-alpha8-vs-alpha10.json` in the parent workspace.

The reported camera-dependent glass flicker remains unresolved; these changes are not claimed to fix it. Pack/device compatibility and full OpenGL parity remain experimental. The gold/red experimental Vulkan shader warning stays enabled.

Development evidence is under the parent workspace's `build/alpha10-serialization` inside the port, `build/depth-slot-integration-tests` inside the port, and `tools/port-26.3/headless-depth/results/rtx5070-1440p-cow-05` outside the port. Exact source/class hashes are recorded with those tests.
