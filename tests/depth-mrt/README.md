# Rejected opaque depth MRT experiment

This experiment is **not active production code**. Two independent raw-Vulkan runs on RTX5070 found the MRT variant approximately 10–12% slower for this depth operation, despite identical pixels and fewer draw calls. The second run reused the same destination images for both variants. The three-pass implementation was restored; the experiment's source is frozen in `fixtures/` for reproduction.

Compile current supporting classes with `:common:compileJava`, then run `python tests/depth-mrt/run.py`. The test compiles the frozen experimental files first on its private classpath. This opens no Minecraft, SDL, GLFW or other window and does not change any installed profile.

The experimental `beforeTranslucents` callback replaces three identical conversions with `snapshotOpaqueDepths`, which invokes `IrisVulkanDepthCopy.copyOpaque` once with three separate R32_FLOAT destinations. The fragment fetches one native depth and stores the same `1.0 - nativeDepth` result to locations0/1/2. It does not alias images or change their later lifetimes.

The tests execute the actual compiled copy methods with only GPU resource providers replaced by recorders. They verify three old passes/draws versus one MRT pass/draw; attachment ordering, formats, sampler, full render area, absence of an attached source/depth image, independent output textures, error rejection before starting a pass, and pass closure. Shaderc compiles the registered production GLSL and SPIR-V inspection checks one image fetch, one subtraction and three stores of the identical result.

The existing actual-compiled-FramePasses Sildur test now covers the combined opaque snapshot, equality before deferred, independent pre-hand refresh of depth2, solid-hand merge into depth1, and final refresh of depth0. This retains the distinction that previously caused Sildur's false6.5× hand-shadow scaling.

These CPU/compiler/recording checks do not constitute GPU performance measurements. The independent raw-Vulkan GPU readback/timing harness lives under `tools/port-26.3/headless-depth` in the workspace. Runtime gameplay and pack-wide visual/performance claims require their own evidence.
