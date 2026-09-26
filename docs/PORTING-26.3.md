# Minecraft 26.3 migration record

This is a separate port of the unofficial Iris Vulkan fork. The working 26.2 source and the user's original Prism instance remain intact. Version `1.11.5-vulkan-alpha.8+mc26.3` has the focused validation recorded in [ALPHA8.md](ALPHA8.md). The full original modpack is not compatible yet; the new instance contains the tested subset.

## Inputs

- Minecraft 26.3 final, official client manifest SHA-1 verified.
- Java 25, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3.
- Sodium 0.9.2+mc26.3, publisher-verified JAR SHA-256 `87a5bb39f7e6e41106e77f122f82fc93f9d3bc0b6ebcb746b314172a446a287a`.
- Official Iris 1.11.5 is used only for the separate third-party mod control. Its successful launch does not validate this fork.

## Implemented migration

RenderPearl replaces many former Blaze3D GPU classes. The port now uses frontend compiled pipelines, unified texture/buffer descriptors and immutable render-pass descriptors. Iris's graphics compiler translates actual SPIR-V reflection to the new backend creation API while retaining shader-pack resources, storage images, SSBOs, push constants and named varying linkage.

The engine now shares render passes between solid/translucent stages and hand draws. The native facade closes the active backend pass around depth snapshots/deferred work, then reopens load-only attachments and restores buffers, uniforms, textures and push constants. Native shader-pack rendering retains its own transparency contract without changing the user's saved vanilla OIT setting.

Text submissions, glyph/falling-block normals, the inserted UV3 attribute, integrated glint keys, sky/camera methods, shadow extraction and Sodium terrain calls have been adapted. These source changes require runtime validation; compilation alone does not prove visual parity. In particular, older packs without the new glint sampling API need a visible enchantment check.

## Evidence and remaining work

- Full Fabric build passes after the API changes.
- 137 actual shaderc/SPIR-V/optional Vulkan-feature checks pass; 87 integrated hand/glint-coordinate checks pass.
- Registered native accessor audit checks 34 exact field/method descriptors against the final Minecraft and Sodium classes, with no remaining mismatches after the frontend uniform map correction.
- First custom runtime stopped on the frontend uniform accessor's exact `HashMap` type; corrected.
- Second custom runtime exposed early backend selection ignoring the explicit Vulkan launch argument when the saved option is `default`; corrected and verified through actual later launches.
- Native High/Ultra world rendering, camera turns, real shader reload, legacy glint, nametags, falling blocks, the Wind Burst III command, portal rendering, dimension changes and resize pass the documented focused tests. A separate OpenGL smoke test also passes. These do not establish every mod feature, pack or GPU's compatibility.

The `tools/port-26.3` directory in the parent workspace contains publisher metadata, source migration records, audit scripts and the separate smoke probe. Disposable runs and per-mod results are in the parent workspace's `build/port-26.3`. Keep each trial's logs, input hashes and screenshots associated with its actual candidate JAR.

## Modpack status

The original pack contains 48 enabled top-level mods and disabled Controlify. Five publisher releases explicitly listed 26.3 at the latest refresh. Some RC candidates also allow final 26.3. Broad version ranges did not establish binary compatibility: six further failures were reproduced at startup.

A separate 21-mod control (20 selected original-mod candidates plus official Iris) first established singleplayer startup with native Vulkan and shaders disabled. The same selected subset subsequently passed the custom fork's shader/feature tests. The whole original pack is not yet compatible. The complete per-mod inventory and runtime exclusions are in `build/port-26.3/modpack-audit/RUNTIME-AUDIT.md` in the parent workspace. No dependency constraints were falsified. Unsupported mods remain clearly identified.

The default JVM control crashed natively during resource loading; the same control with `-Xss4m -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysPreTouchStacks` passed that point and entered the world. This is a bounded workaround observation, not proof of the native crash's upstream cause.
