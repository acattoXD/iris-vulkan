The CPU test checks the coordinate contract shared by shadow casting and sampling:

- Legacy pack projection maps near/far to -1/+1. The native vertex epilogue converts that final (possibly distorted) clip position to Vulkan 0/1 exactly once.
- Forward depth with LESS_OR_EQUAL retains the nearer caster; screen-space reversed-Z inversion must not apply to shadow textures.
- Projection inverses, perspective depth and early-return shader mains agree with that contract.
- Caster bounds include off-camera terrain and handle negative world coordinates.

Compile `IrisVulkanShadowMath.java` and `IrisVulkanShadowMathTest.java` against the Minecraft JOML dependency, then run the test with assertions enabled. No Minecraft, OpenGL, or Vulkan context is needed.

Runtime verification remains necessary: inspect shadowtex0 versus shadowtex1 around water/stained glass, shadows from off-camera terrain, sun/moon movement, entities/block entities, reload and dimension changes. CPU success alone is not proof of a working shader pack.
