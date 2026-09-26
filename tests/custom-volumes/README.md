# Static custom volume CPU regression

From the workspace root, run:

```powershell
& ./ports/iris-vulkan-26.3/tests/custom-volumes/verify.ps1
```

The runner uses Java 25 and the core/shaderc LWJGL 3.4.3 jars and Windows native jars recorded in the existing CPU-test runtime manifest. All paths can be supplied explicitly with `-JdkPath`, `-ShaderPack`, `-RuntimeClassPath`, and `-OutputDirectory`. Relative paths resolve from the workspace root. It compiles the production `IrisVulkanStaticVolume` source directly with the test. A separate integration compile checks real `IrisVulkanCustomTextures` against the cached native API and tests its alias routing with real `CustomTextureData` and metadata-only Iris/ShaderPack startup facades.

Artifacts go to `build/custom-volumes`: compiler arguments, class files, test results, and the generated GLSL and SPIR-V. The runner does not use Gradle, start Minecraft, create a graphics device, or access live game state.

Coverage includes all four raw 3D declarations in the actual Photon v1.3b archive, metadata filtering/address modes, exact archive byte lengths, every RGB half-float component and added alpha, every normalized R8 storage byte, RGBA32F bit preservation, invalid dimensions, integer overflow, and short/long payloads. Generated nearest/linear and clamp/repeat helpers compile in both vertex and fragment shaders, with two volume samplers present to catch generated-name collisions. Alias tests cover stage/type keys, already mapped names, global fallback, stage precedence, and unsupported format/payload rejection.

Sampling checks use a synthetic 2x2x2 volume with red value `x + 2*y + 4*z`: 44 center, edge, negative-coordinate, axis-seam and nearest-Z cases run through an independent CPU reference and are saved in `shaders/sampling-goldens.csv`. Structural contracts check the generated GLSL uses all eight neighbors for linear filtering, one floored texel for nearest, per-axis clamp/modulo, texel-center weights, and the correct atlas mapping. Shader compilation validates syntax and Vulkan legality. These checks do not execute GLSL or establish GPU sample values; the saved golden cases are also suitable for a later GPU sampling fixture.
