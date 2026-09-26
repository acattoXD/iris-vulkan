# Alpha8: Minecraft 26.3

Version: `1.11.5-vulkan-alpha.8+mc26.3`. Tested release JAR SHA-256: `44d688e5dc6961122a1cc5d65c89dd95e8e8a10a97f0fd66dbfaa31c8d2141f2`.

## Requirements and scope

Minecraft 26.3 final, Java 25, Fabric Loader 0.19.5+ and Sodium 0.9.2+mc26.3. This is an unofficial Fabric fork retaining mod ID `iris`; replace other Iris JARs rather than installing both. Shader packs remain separate downloads. The gold/red experimental Vulkan warning remains enabled.

This release moves the native renderer to RenderPearl's frontend/backend pipeline API, reflected descriptor resources and immutable render-pass descriptions. Native shadow/main terrain batches are prepared separately, with the player's batches restored after shadows. Depth snapshots and deferred stages execute outside active render passes. Texture uploads are prepared before drawing. Shader compilation supports large source text without using LWJGL's small temporary stack.

The port preserves name-tag materials and geometry normals, handles 26.3's UV3 and combined glint formats, uses Sodium's real section-timing texel buffer, and retains the 20-byte terrain push-constant ABI. For legacy packs, combined enchanted-item draws replay the pack's own ArmorGlint program using the correct foil texture and original geometry. Packs consuming the new glint API avoid this extra pass.

Early backend selection follows the actual launch argument, option migration and failed-start fallback. Minecraft's older-options data fix can reset Vulkan to the default renderer; select Vulkan again after migrating old options if necessary.

## Current validation

- Full Fabric build and logical-target regression pass.
- Actual shaderc/SPIR-V, feature-structure and texel-buffer contracts: 152 checks pass. Additional push-constant, early backend selection, glint routing/overlay and shadow-batch checks pass.
- Complementary Unbound r5.9.1 High and Ultra render a real 26.3 world and complete a 90-degree camera turn. Reviewed images contain terrain, water, clouds, hand and shadows. Ultra reports six writable images, one SSBO, 345 MiB requested and a real shadowcomp dispatch. These are startup/rendering observations, not full storage-content or visual-parity proofs.
- The selected 20-mod subset plus this fork completes four High feature captures: readable named mobs, textured falling sand/gravel, enchanted held items and third-person rendering. Actual `/enchant @s minecraft:wind_burst 3` succeeds and the held mace reports level 3. Iris reload creates a new native pipeline and rendering resumes.
- The legacy glint candidate completes the same feature sequence, including reload, and records 607 successful overlay submissions. Reviewed mace captures show the purple effect absent from the earlier candidate. No arbitrary replacement glint color was introduced.
- The exact release JAR completes the same feature/reload sequence with Mineek and with Complementary Ultra in the selected mod set. Mineek records 912 legacy-glint replays; Ultra recreates its six writable images/one SSBO and resumes shadowcomp dispatch after reload. Reviewed captures retain textured blocks, readable labels and held-item effects.
- The release JAR renders an active nine-block End portal, transitions to the Nether and back with the expected native pipeline changes, and resizes the actual SDL window/framebuffer/target. All four captures were reviewed. This checks portal rendering and lifecycle transitions, not every End-dimension scene.
- A separate OpenGL basic test completes two captures and a 90-degree camera turn with the actual `IrisRenderingPipeline`. This establishes that the standard backend still launches and renders; it is not full GL/Vulkan visual parity or a claim that native-only legacy-glint replay also applies to GL.

Evidence remains under the parent workspace's `build/port-26.3/custom-vulkan-10`, `custom-vulkan-11-ultra`, `custom-vulkan-12-modpack-features` and `custom-vulkan-13-glint-features`. The High/Ultra smoke runs used precursor JAR `33afdf75f7e1c8b0bcd0f620a67d447297f87b3ac141fa32f2d6088612eafea6`; the glint feature run used the candidate hash above. Do not attribute older checks to a later binary without rerunning or recording their limited scope.

Release-JAR follow-ups are `custom-vulkan-14-mineek`, `custom-vulkan-15-ultra-features`, `custom-vulkan-16-lifecycle` and `custom-vulkan-17-opengl`. Their input manifests and active-build reports identify the production JAR, separate test-probe JARs and shader ZIPs. None of the diagnostic probes belong in the installed modpack.

Tests used Windows 11, RTX 5070 / NVIDIA 616.92, Java 25.0.3 and visible windows. The diagnostic test mod is separate and must not ship in the runtime JAR. Tests do not establish an FPS increase, all third-party mod features, every pack or Apple Silicon compatibility.

## Modpack migration

The user's original instance contains 48 enabled top-level mods and disabled Controlify. The separate working subset includes 20 original-mod candidates plus this Iris fork. Unsupported mods are recorded explicitly; their metadata was not changed to force a launch. The complete original instance remains intact.

At the latest publisher refresh, five projects explicitly list final 26.3, seven have compatible-range snapshot/RC candidates, and the remaining original mods have no declared final 26.3 support or need this custom port. World entry and shader tests are separate evidence from publisher tags. In particular, Lithium, ImmediatelyFast, EntityCulling, LambDynamicLights, CWB and No Chat Reports are not silently carried into the new active mod set.

The default-JVM 26.3 control crashed natively during resource loading. The same control with `-Xss4m -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysPreTouchStacks` entered the world, and the shader tests used that observed workaround. This does not prove the upstream cause or require those flags on every computer.
