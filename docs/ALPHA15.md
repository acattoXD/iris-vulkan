# Alpha15 candidate: isolate terrain textures from retained feature bindings

Version `1.11.5-vulkan-alpha.15+mc26.3`; Minecraft26.3, Fabric Loader0.19.5+, Java25, Sodium0.9.2+mc26.3. This is an experimental candidate pending movement-based rendering checks.

JAR SHA256: `0d661bd59149fe929264d48a201b3d3bb94326e99f820943da017fe9fc0ba062`. Full Fabric release build and the ten existing packaging checks pass. The binding regression class hash matches the packaged class. Compared with Alpha14, changes are limited to version metadata, `IrisVulkanRenderPassBindings` (including its nested records) and `IrisVulkanPipelineWarmup`.

## Texture binding correction

Minecraft reuses its world render pass for entity/feature draws and translucent terrain. The frontend retains named textures when pipelines change, and Iris replays them when reopening a pass for different attachments. The old shader-pack albedo resolver always preferred vanilla `Sampler0`, even during a Sodium draw that supplied the current terrain atlas as `u_BlockTex`. A skin, font or particle texture retained from an earlier draw could therefore replace the block atlas. Transparent texels can erase glass/slime faces; opaque texels can make them dark. The choice depends on earlier visible draws, so a stationary scene cannot establish that it is fixed during movement.

Alpha15 selects albedo, primary texture dimensions and lightmap from the active shader key's producer. All six Sodium terrain/shadow keys use `u_BlockTex` and `u_LightTex`; vanilla feature keys use their own names. Neither producer falls back to the other producer's stale binding. Shader-pack custom textures and the dedicated glint path retain their existing precedence. A missing required Sodium atlas produces an explicit error instead of silently borrowing a feature texture.

CPU regression uses the actual compiled binding methods and fake view/sampler objects:1,280 checks cover mixed retained/replayed maps, all six Sodium keys, return to entity/font/null-key routes, view and sampler identity, dimensions, missing producer sources, custom texture precedence and glint. It does not execute the render pass or validate GPU pixels.

## Compilation hitches

The user's live log records armor-glint and cutout-block variants compiling on the render thread for approximately1.27 seconds per original/adapted pair. Alpha15 adds those two world variants to the existing one-time pipeline warmup. They are not added to the shadow list. This shifts their first compilation earlier; it does not reduce GPU shader cost, eliminate every first-use hitch, or promise a whole-game FPS gain. Original and attachment-adapted pipelines remain separate because their render states differ.

The updated RenderPearl CPU harness passes:21 unique world requests, exact shader-key mappings for both added variants, unchanged11 shadow requests (10 physical after decorative-glint exclusion), LOAD-only descriptors at four viewport sizes,1,000-frame descriptor-identity reuse with a fake compiler, and one-time success/failure/skip handling. It does not call the real Vulkan compiler or prove runtime cache hits.

## Remaining verification

Retest by walking, strafing, turning and changing which entities/particles are visible near panes, stained glass and slime. Include underwater views and both primed-TNT and falling-block-TNT models. The user's persistent-white TNT report follows a different feature path and has no proven fix yet. Do not claim this texture correction fixes every underwater or TNT symptom before that test.

Alpha14's translated menu labels/colors are retained. Graphics compiler optimization remains at zero; the separate CPU compiler experiment is not enabled here. The timing probe and scripted movement helpers are separate diagnostic mods, not part of this JAR. Install only one Iris JAR after closing the instance; nothing is replaced in a running game.
