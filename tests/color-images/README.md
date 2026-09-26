# Built-in compute color images

Run from any working directory after the current 26.3 project has been compiled:

```powershell
& ./ports/iris-vulkan-26.3/tests/color-images/verify.ps1
```

The runner defaults to JDK 25.0.3, the existing `build/optimization-26.3/uniform-tests/classpath.json`, and the cached jcpp 1.4.14 dependency. It compiles current `IrisVulkanColorImages`, `IrisVulkanTargetModel`, `IrisVulkanTargetSpec`, `IrisVulkanComputeCompiler`, and `IrisVulkanShaderPruning` into isolated output, then runs two CPU tests. It does not invoke Gradle or interact with Minecraft.

`ColorImageContract` executes real parser, planner, target allocation, and lifecycle methods. A child class loader replaces only live GPU and gbuffer providers with recording fakes. A small Iris startup facade and a synthetic ShaderPack object prevent Fabric/client initialization; the real ProgramSet/property parsers still run. Checks include:

- Canonical target names and bounds, source comments and unused declarations, multiple opaque declarators, opaque-array rejection, eight compute stage families, graphics exclusion, and custom-image precedence. Photon-style `//*`/`//*/` switches must preserve active functions and declarations while true block comments remain disabled.
- Scoped storage allocation flags, nested true/false scopes, exceptional cleanup, and isolation between threads.
- Exact image type/format matching, unsupported formats, compiler format metadata, and explicit/allocation mismatch rejection.
- Rethinking Voxels prepare-style graphics declarations (`colorimg3`/`colorimg8` RGBA16F and unsigned `colorimg9` R32UI) are discovered for storage allocation while the compute-only planner remains unchanged; the latter is an original-failure control.
- Both physical target sides receive storage usage while other targets keep their original usage; configuration equality accounts for storage capability.
- Borrowed image views track the current side through swap/select and expose only mip zero while the sampler can expose a mip chain.
- Repeated configuration, capability changes, fixed and relative resize, destruction, missing/closed target rejection, and no ownership close through borrowing.
- Simulated storage allocation failure cleans up existing allocations, does not retry a different format, and permits subsequent recovery.

`ColorImageBytecodeContract` checks the actual compiled executor, mixin, and cached Minecraft 26.3 classes. Its control-flow assertions verify required mixin registration and method signatures, allocation conversion, the engine's GENERAL layout assumptions, custom-image precedence, dynamic alias resolution, the two unconditional dispatch barriers, ordered engine submission, and borrowed ownership.

Results and SHA-256 provenance are written to `build/color-images/result.txt`, `bytecode-result.txt`, and `provenance.json`. The bytecode test depends on the current compiled integration classes, and provenance records their hashes separately from the five recompiled production source files.

These tests simulate allocation and resource providers; they do not allocate GPU objects, call a Vulkan driver, launch a transformed Fabric mixin environment, or validate rendered Photon output. Engine ABI/layout checks are specific to the recorded Minecraft jar. The source fixture has Photon's 192×108 RGBA16F target shape but is synthetic; an actual preprocessed Photon shader compilation is separate evidence.

Supply `-PhotonComputeSource <exported-deferred4_a.csh>` to additionally run `PhotonColorImageCompile`. It compiles that exact preprocessed source through production preparation and CPU shaderc, verifies both color aliases, the rgba16f storage descriptor, 256×1×1 local size, and actual SPIR-V `OpImageWrite` instructions. Prepared source, binary, descriptor metadata, hashes, and limits are saved under `build/color-images/photon`. This still does not execute GPU dispatches.
