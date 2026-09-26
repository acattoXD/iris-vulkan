# Alpha21: native cloud uniform binding

Version `1.11.5-vulkan-alpha.21+mc26.3`. Requirements remain Minecraft26.3, Java25, Fabric Loader0.19.5+ and Sodium0.9.2+mc26.3.

BSL10.1.8 compiled its cloud shader on Alpha20, then crashed when drawing `minecraft:pipeline/flat_clouds`: `iris_CloudInfo` had no buffer binding. Minecraft's CloudRenderer supplies the buffer under `CloudInfo`, while Iris's cloud shader transform names the block `iris_CloudInfo`. The native buffer-alias map omitted that pair.

The fix adds the missing alias to the existing binder. It borrows the exact engine buffer slice and refreshes it from the current producer before each draw. It does not synthesize cloud data, change cloud settings, disable clouds, or alter buffer ownership. Both flat and fancy cloud pipelines use this path.

## Validation

- A CPU regression executes the actual binding method against real Minecraft26.3 flat/fancy pipeline layouts, a recording RenderPass and borrowed buffer slices.
- The original Alpha20 artifact reproduces the missing `iris_CloudInfo` exception in both modes (seven control checks).
- The patched source passes23 checks including alias identity, refreshed buffers, preserved CloudFaces, missing-source rejection and borrowed ownership.
- A bytecode check verifies that the actual26.3 CloudRenderer supplies `CloudInfo` through the engine's buffer-uniform API.

The full Fabric build and packaging checks are run before distribution. This is a fix for the reported binding failure, not proof of all BSL effects or rendered-image parity. No in-game/GPU visual test is claimed for this build. Rethinking Voxels geometry support and the pending performance profile are unchanged.
