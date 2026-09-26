# Shared opaque depth, independent later values

This candidate keeps the Alpha9 single-output depth conversion and solid-hand shaders unchanged. Three physical owners are allocated initially; a fourth is created only if a later merge needs another independent output. Physical owner arrays never contain aliases and are the only objects closed during resize/teardown.

`IrisVulkanDepthSlotPlan` maps three logical sampler slots to those owners. Before translucency, one full conversion is recorded and all three logical views reference its result. A later full write selects an owner that no other logical slot references and that the pass does not sample. Only after the command is recorded does the updated logical slot change. Each operation replaces its whole destination, so splitting an alias does not copy old contents.

For the ordinary repeated-hand sequence, owner indices evolve as follows:

| Boundary | Logical0 | Logical1 | Logical2 |
|---|---:|---:|---:|
| Frame reset |0|1|2|
| Opaque conversion |1|1|1|
| Before hand |1|1|0|
| First solid-hand merge |1|2|0|
| Second solid-hand merge |1|3|0|
| Final depth0 refresh |1|3|0|

The final row writes new contents to owner1. Without a hand merge, final depth0 instead splits into a free owner so the opaque values in depth1/depth2 survive. The planner supports repeated merges and other operation orders; callers must use its returned owners rather than assuming the table's physical indices.

## Tests

Run `python tests/depth-slots/run.py` for the pure reference-model comparison. It covers every trace of length0–7 over opaque/pre-hand/hand-merge/final/frame-reset operations, plus longer repeated-hand and multi-frame cases. Every logical pixel is compared bitwise with the old independent-image implementation after every operation.

After compiling `:common:compileJava`, run `python tests/depth-slots/verify_integration.py`. It executes the actual compiled gbuffer depth methods with only engine/GPU operations replaced by recording image objects. It covers96 frame traces,12 resize/close generations, no-hand/third-person paths, repeated merge use of owner3, independent source/destination references, logical sampler publication, and exactly-once close of every owned texture and view. The engine's native depth is never closed. The actual compiled frame-callback regression also verifies Sildur's pre-deferred equality and later hand lifetimes.

Actual GPU equality and performance are separate gates in the headless Vulkan harness under `tools/port-26.3/headless-depth` in the workspace. The rejected MRT and conversion-plus-two-copy experiments do not establish a benefit for this candidate. These tests open no game or display window.
