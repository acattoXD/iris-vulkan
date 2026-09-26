# Alpha12 candidate: share the initial depth clear

Version `1.11.5-vulkan-alpha.12+mc26.3`; JAR SHA256 `51abdc9e47012869ae8882cbc01ff9a9f9f54d70f1e67f3b3e3c74dcad1953d3`.

**This candidate has not been tested in Minecraft. Alpha10 remains installed.** Alpha11's separate user-run Ultra comparison is also pending. No additional whole-game FPS gain is claimed here.

## Change

All three logical depth snapshots begin each frame at 1.0. Clear one physical texture, then publish three views of that value. Existing full-image writes choose an independent destination whenever logical values diverge or an input is being sampled. Publish the shared clear only after the encoder call succeeds.

The original three initial allocations, optional fourth owner, conversion/hand-merge shader code, later capture boundaries, and destruction rules remain. The change removes two clear commands and their two engine post-clear barriers per frame. It does not lower resolution, shader quality, or lighting volume size. Alpha11's compute allocation changes are inherited unchanged.

## Verification

- Full Fabric build passes.
- Pure ownership tests pass 55,987 traces.
- Tests against the final Gradle classes pass 68,740 integration checks: 180 single clears, 12 failed-clear cases, 384 conversions, 228 hand merges, 12 resize generations and 180 frame traces. They execute the compiled clear helper, check its real frame-start call site, and verify publication order, pre-opaque reads, all first-write indices, repeated merges and unique closes.
- The existing compiled deferred-depth regression passes.
- Independent headless Vulkan readback passes 60 stages and 1,045,094,400 float comparisons against separate reference images and CPU depth histories. Coverage includes initial clear, writes before opaque capture, hand rendering without opaque capture, repeated merges, next-frame resets and recreation at a smaller resolution. The actual final planner bytecode and unchanged depth shader strings are used.

The headless fixture does not create a window, surface, swapchain or Minecraft instance. Validation layers were unavailable; numerical readback and successful Vulkan calls do not substitute for validation-layer or cross-device testing.

## Isolated GPU measurement

At 2560x1440 on RTX5070, 24 alternating paired rounds measured the clear operation:

| Path | Median GPU time |
|---|---:|
| Three independent clears | 0.010935 ms |
| One shared clear | 0.003911 ms |

Median paired saving: **0.007034 ms**, faster in 22 of 24 pairs. Each measured iteration first dirties the same three allocations using the actual depth-copy shader outside the timestamp interval. Timed clears use Minecraft's GENERAL layout and exact per-clear synchronization masks. This measures a small operation-level saving, not a 64% FPS increase. No normal game process was observed before, during or after the headless run; continuous process monitoring was not used.

The separate experiment that changed Complementary's compute workgroup shape is **not included**. Its numerical output matched, but its small timing benefit was inconsistent across cases.

Evidence in this port: `build/optimization-alpha12-build.log`, `build/alpha12-initial-depth-plan-tests`, `build/alpha12-final-gradle-integration`. Parent-workspace GPU evidence: `tools/port-26.3/headless-depth-clear/results/prepared-01`. The final planner class hash is `0484b7827d414045a4f719b9c7136d432247e1caab9378bddcf2f75ebca549c8`; GbufferTargets is `3762ebc7c333b28dc1b01521da8bb1c62a6b4b85fb5cab6948efd1a48de759fb`.

## Remaining checks

Actual game/preset/lifecycle comparison for this JAR remains pending. Alpha10's measured High improvement applies to its own recorded artifact and scene. Camera-dependent glass flicker remains unresolved; static screenshots and depth readback cannot establish a motion fix.
