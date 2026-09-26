# Alpha22: material textures for native clouds

Version `1.11.5-vulkan-alpha.22+mc26.3`. Requirements remain Minecraft26.3, Java25, Fabric Loader0.19.5+ and Sodium0.9.2+mc26.3.

The BSL10.1.8 follow-up log passed Alpha21's CloudInfo binding but crashed in the same cloud draw because `gtexture` was missing. Minecraft's cloud mesh supplies colors and geometry through CloudInfo/CloudFaces and has no bound albedo texture. Iris's OpenGL `addLevelSamplers` provides white for the absent material inputs; the native path instead looked for a retained skin or terrain texture, or failed if none existed.

Cloud draws now use an explicit opaque-white1x1 texture for their absent albedo/lightmap/overlay inputs, matching the established OpenGL material contract. The texture uploads during pack preparation, before opening a render pass, and retires through normal engine texture ownership at pack teardown. The cloud texture-size source also uses white. Shader-pack custom texture overrides keep priority. Other textured producers retain their existing binding rules, and unsupported resources still fail explicitly.

## Validation

The regression invokes both actual uniform and texture binders using Minecraft26.3 flat/fancy cloud layouts and recording resources. Alpha21 reproduces both the missing-texture error and stale-skin selection (six control checks). The patch passes154 checks for complete resource binding with empty/stale maps, CloudInfo/CloudFaces preservation, both cloud shader keys, other textured producers, custom texture precedence, white RGBA construction and upload/teardown locations. It includes Alpha21's buffer alias.

These are CPU replay and bytecode checks, not rendered-pixel validation. The full Fabric build and packaging tests run before distribution. No new Minecraft window is launched and no normal instance is changed as part of this fix. Full BSL visual parity, Rethinking geometry support and GPU performance profiling remain separate work.
