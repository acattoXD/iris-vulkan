# Alpha18 candidate: Photon rendering corrections

Version `1.11.5-vulkan-alpha.18+mc26.3`. Minecraft26.3, Java25, Fabric Loader0.19.5+, Sodium0.9.2+mc26.3. Vulkan shader support remains experimental. This candidate requires live visual verification before treating Photon as supported.

Photon's blend-off water pass exposed a native pipeline identity bug. After applying the pack's blend rules, Iris reclassified Sodium's translucent material as cutout terrain. Those programs write different data and different color targets: Photon's water writes refraction/color to3/13, while terrain writes packed material data to1. Adapted pipelines now retain their selected shader key through format copies. Original descriptors remain phase-dependent for world and shadow rendering; pack teardown clears both variant objects and their retained keys.

Opaque held items are scheduled before deferred lighting. Photon writes their material data into a gbuffer and lights it in deferred; drawing them afterward produced only the separately composited enchantment layer. The native path uses a separate early-hand feature dispatcher so world feature submissions remain intact, preserves native hand transforms and eligibility checks, retains the depth before the hand separately, and leaves translucent hand features for the later stage.

The candidate also includes the Alpha17 compiler corrections: rename legal legacy sampler parameters that collide with Vulkan's reserved `sampler` token within their function scopes, preserve quoted diagnostic strings during comment masking, and remove unused vertex inputs left behind by core transforms. These changes preserve actual producer attributes, interface/resource names and live color data. Alpha16 static-volume and compute color-image support remains included. Graphics compiler optimization settings are unchanged.

## Validation scope

Runtime JAR SHA256: `805b2bd6934b9695d192922e3ae07b36d28f17b8ae74b88ed956debd3000ba83`. The full offline Fabric release build and ten package checks pass. The post-build Alpha18 moving test loaded the exact JAR in a copied world and restored normal input after focus loss.

- The shader-key regression exercises real RenderPearl descriptors and production adaptation/routing: both blend-misclassification directions, compatible copies, cache reuse/teardown, and original world/shadow transitions. The existing world/shadow warmup suite also passes.
- Sampler compatibility passes129 checks and49 shaderc invocations, including the actual Photon failure dumps and original-fails controls.
- Vertex-input correction passes44 checks and four shaderc invocations against the captured Photon line shader and actual Minecraft26.3 line producer, including an original-fails control.
- Hand scheduling regressions execute compiled lifecycle and stage/depth branches, normal and exceptional transform cleanup, and101 actual Minecraft26.3 mixin bytecode contracts. They verify the calls and ordering without a graphics device; live visibility and image correctness remain unverified.
- Alpha17's previous visible run successfully loaded Photon v1.3b, its four static volumes and actual deferred4_a compute, then completed a50-second moving/underwater capture. It visibly failed glass/water and held-item rendering; those images motivated this candidate.
- Alpha18's follow-up captured17 moving frames before the copied-game probe stopped on focus loss at31.322273 blocks and restored input. Independent review found colored transparent glass/panes, green slime, and a visible cyan enchanted axe base beneath its glint, with no obvious object loss in the captured approach/right orbit. The run had zero underwater frames. The user then manually confirmed that the pool water looked normal. This is partial visual evidence, not complete motion, OpenGL-parity, or device coverage.

Live checks still need broader device/pack coverage, underwater captures, shader reloads, and backend switching. This candidate does not establish an FPS improvement, Apple Silicon compatibility, or a resolution of the separate Hypixel white-TNT report. CPU compiler and scheduling checks do not establish final visual correctness.
