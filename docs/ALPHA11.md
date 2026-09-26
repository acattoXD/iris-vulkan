# Alpha11 candidate: reduce compute dispatch overhead

Version `1.11.5-vulkan-alpha.11+mc26.3`; candidate JAR SHA256 `4713024dfd18da5a438d6d44cf1b191d5887bdaae64fa5f0518d927639742f42`.

**The full Ultra game comparison is pending. Alpha10 remains the installed build.** These changes target compute binding/allocation work; no additional Minecraft FPS gain is claimed yet.

## Changes

- Upload fresh compute uniforms into the engine's submission-owned transient memory. Bind the exact slice offset and length; do not close or retain borrowed slices across submissions.
- Cache physical-device compute limits and immutable descriptor counts when each program is compiled, instead of querying/rebuilding them on every dispatch.
- Use compute push descriptors when the enabled extension and device descriptor limit permit it. Keep allocated descriptor sets as the fallback. Empty descriptor lists issue neither an invalid zero-write push nor an unnecessary pool allocation.
- Remove the extra shadow-stage storage barrier only when an actual compute dispatch completed and already recorded its pre/post barriers. Keep the stage barrier when no dispatch ran. Storage clears, layouts, dispatch order, direct/indirect behavior and shader quality are unchanged.

The Vulkan specification defines push descriptors as command-buffer-managed bindings and permits changing the offset of a non-dynamic buffer descriptor for each update. This implementation also checks the device's maximum push-descriptor count. [Vulkan command reference](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPushDescriptorSet.html), [device-limit reference](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDevicePushDescriptorProperties.html).

## Verification

- Full Fabric build passes.
- 31 descriptor/slice/limit checks and 13 compiled-executor ownership checks pass, alongside actual shaderc/SPIR-V contracts. Both dispatch barriers remain; fallback pools retire after submission, while borrowed uniform buffers are never closed.
- The updated26.3 transient-upload regression verifies66 fresh aligned uploads, exact bytes/padding, same-frame changing values, stack-poison lifetime, large blocks and constrained stacks. Actual engine bytecode confirms synchronous CPU copying and submission-based retirement.
- 1,345 recorded checks against the compiled shadow callback cover absent/invalid programs, first/late/multiple dispatches, zero-work dispatches and failure before prepare.
- The independent raw-Vulkan test checks50,724,864 SSBO/image values and1,032 fences confirmed pending before host release. It exercises nonzero aligned uniform ranges, several in-flight submissions, sampled and writable images in GENERAL layouts, direct/indirect dispatch, allocated sets and push descriptors. Its deliberately wrong valid-slice control fails as expected. The exact numeric helper bytecode matches the built JAR.

## Isolated measurements

On RTX5070,80 alternating measured rounds produced these median times per synthetic dispatch:

| Binding path | CPU recording | GPU | CPU retirement |
|---|---:|---:|---:|
| Dedicated VMA uniform buffer + allocated set |2.551us|4.801us|0.389us|
| Transient slice + allocated set |2.177us|4.111us|0.296us|
| Transient slice + pushed descriptors |1.170us|4.116us|0.002us|

Push descriptors reduce host overhead in this fixture; they do not show a GPU execution win over slices with ordinary sets. The dedicated path includes batched staging copies before compute, matching the renderer's ordering. The fixture preserves Ultra's seven-binding topology and adds an eighth SSBO for exact validation; it does not run Complementary's lighting algorithm. Validation layers were unavailable. This is not a whole-game FPS comparison or cross-device validation.

The real r5.9.1 Ultra pack allocates six images and one SSBO (~345.58MiB), but clears only four images totaling ~24.08MiB per frame. Both flood histories and the SSBO persist. Their clear/init semantics were preserved. Its shadowcomp dispatch covers8,388,608 invocations, so the binding savings must not be represented as a large reduction in that entire shader workload.

## Runtime gate and remaining issues

Matching, separate Alpha10/Alpha11 Ultra profiles are prepared for a user-launched comparison with the passive focus gate. No game or desktop window was opened for this candidate's development. Alpha10's successful High result remains specific to its documented scene; it does not validate Alpha11 Ultra. The reported glass motion flicker is still unresolved.

Evidence: `build/alpha11-compute-contract`, `build/transient-uniform-26.3-verified`, and `tests/shadowcomp-barrier` in this port; independent GPU data under the parent workspace's `tools/port-26.3/headless-compute/results/rtx5070-08-final-classes`.
