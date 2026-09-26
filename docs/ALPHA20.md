# Alpha20: graphics color-image plumbing for Rethinking Voxels

Version `1.11.5-vulkan-alpha.20+mc26.3`. Requires Minecraft 26.3, Java 25, Fabric Loader 0.19.5+ and Sodium 0.9.2+mc26.3. Vulkan shader support remains experimental.

Alpha20 extends the existing native storage-image bridge to graphics stages. Built-in aliases such as `colorimg3`, `colorimg8`, and `colorimg9` are discovered in graphics vertex/fragment sources, receive storage-capable target allocation, are reflected as storage descriptors, and bind to the current target view. Integer image formats remain type-checked; compute-only aliases and custom-image precedence remain intact. The actual Rethinking Voxels declarations are covered by shaderc/SPIR-V descriptor tests.

Rethinking Voxels is **not fully supported yet**. Its shadow program includes a geometry stage that Minecraft 26.3's bundled RenderPearl API cannot represent: RenderPearl exposes only vertex and fragment `ShaderType` values, its pipeline builder/linker assumes a vertex-to-fragment chain, and its Vulkan stage mapping has no geometry entry. Supporting that portion requires coordinated RenderPearl/Minecraft backend changes, including stage plumbing, reflection, Vulkan stage flags, and pipeline creation. Alpha20 therefore improves the image half while keeping the geometry capability gate honest.

## Validation

- Full offline Fabric build passes after the graphics-image changes.
- Color-image contracts pass502 CPU allocation/parser/lifecycle checks and86 compiled integration invariants, including Rethinking's colorimg3/8/9 declarations.
- RenderPearl graphics contracts pass162 real shaderc/SPIR-V/reflection checks, including three Rethinking-style graphics storage-image bindings and descriptor rebinding.
- No live Rethinking Voxels game run, GPU image writes, geometry-stage run, or pixel/FPS claim is included. Geometry remains the explicit blocker.
