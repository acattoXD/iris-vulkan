# Sodium push constants and Metal resource assignment

Sodium 0.9.1-beta.3 and 0.9.2 add a 20-byte push-constant range only when a pipeline's namespace contains `sodium`. Iris shadow aliases use `iris:native_shadow/...`; without a separate layout hook, their shaders still contain Sodium's `PC` block but their native pipeline layout has no matching range.

The production correction tracks Iris-prepared Sodium pipelines by their actual shader patch family and supplies the missing range immediately before `vkCreatePipelineLayout`. It runs after Sodium's own hook, retains an existing compatible range, and leaves other shader families alone. The range uses offset 0, size 20 and `VK_SHADER_STAGE_ALL`, matching Sodium's actual `vkCmdPushConstants` calls. Both ordinary and storage graphics pipelines use this native compilation boundary.

Run the CPU/native-library tests after compiling the project:

```powershell
./tests/pipeline-layout/verify.ps1 -JdkPath <jdk25> -MakeUpPack <MakeUp-UltraFast-9.5e.zip> -SodiumJars @(<sodium-beta3.jar>, <sodium-0.9.2.jar>)
```

`IrisVulkanPushConstantLayoutTest` invokes each installed Sodium mixin handler and the actual Iris handler using real `VkPipelineLayoutCreateInfo` structs. A stand-in native call checks the range before creation, without allocating a GPU device. It reproduces the namespace miss, confirms the ABI, and checks that existing/non-Sodium layouts are unchanged. The general native mixin bytecode test verifies the new injection point against Minecraft 26.2.

`MakeUpPushConstantMslTest` reads the supplied pack's lowercase `medium` profile, processes its actual shadow includes/options through Iris's preprocessor and Sodium transformer, and compiles the native vertex shader with Mojang's shaderc frontend. It then reproduces the reported Metal assignment collision: explicit ordinary UBO slots plus an undeclared push range cause both `IrisUniforms` and `PC` to use `[[buffer(0)]]`. The test models [MoltenVK 1.4.2's allocation order](https://github.com/KhronosGroup/MoltenVK/blob/v1.4.2/MoltenVK/MoltenVK/GPUObjects/MVKPipeline.mm#L329): a declared range reserves Metal buffer 0 for `PC`, and ordinary descriptors follow at Metal buffers 1/2. Vulkan descriptor binding numbers remain 0/1; the production fix does not rewrite UBO bindings.

The offline MSL test models resource assignments; it does not create a Metal pipeline, run Apple's compiler, or establish Mac compatibility. Generated GLSL/MSL remains under `build/` and the user's shader ZIP is not redistributed. A real M2/MoltenVK rerun is needed to confirm the observed crash is resolved.

Verified September 13, 2026: the actual layout handlers passed with Fabric Sodium 0.9.1-beta.3 and 0.9.2; 86 Minecraft/Sodium hook contracts passed; the supplied MakeUp 9.5e `medium` shader reproduced the duplicate Metal buffer-0 assignment and passed the corrected PC-first mapping check. These results establish the layout/translation regression, not Apple GPU execution.
