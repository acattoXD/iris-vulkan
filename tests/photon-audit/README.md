# Photon 1.3b CPU pack preflight

Run after production classes have been compiled:

```powershell
python ports/iris-vulkan-26.3/tests/photon-audit/run.py --jdk 'C:/Program Files/Java/jdk-25.0.3' --classpath-json build/optimization-26.3/uniform-tests/classpath.json --pack build/port-26.3/motion-alpha15-translucent-02/shaderpacks/photon_v1.3b.zip --output build/optimization-26.3/photon-preflight-01
```

This loads the ZIP through the real compiled `ShaderPack` constructor, including include traversal, option/profile parsing, `RawData3D` file reads, ID maps and actual `ProgramSet` preprocessing. It loads the default configuration and a second pack using the real parsed `high` profile, exports their active Overworld sources, then invokes the actual capability inspector. `deferred4_a.csh` is the actual post-include/post-option/Jcpp source for independent compute compilation tests.

Only three environment services are replaced in the separate test JVM: `Iris` supplies a logger, debug-disabled in-memory config and current-pack metadata; `StandardMacros` supplies explicit Windows/NVIDIA/Vulkan defines; `IrisRenderSystem` supplies true feature predicates for parsing. Production `ShaderPack`, `ProgramSet`, properties/texture parsing, `FeatureFlags`, typed texture alias routing, compute preparation and capability inspection are not replaced. Class origins are checked; no graphics device may exist. The offline biome map is empty; no live biome registry or device feature support is asserted. Classpath dependencies include real Minecraft 26.3, current compiled Iris output, launch libraries, JCPP 1.4.14, ANTLR 4.13.1 and glsl-transformer 3.0.0-pre3. `inputs.json` records exact classpath and input/class/source hashes.

The initial run resolves both default and high to `Profile: high (+0 options changed by user)`, with 105 exported Overworld sources each. Resource preflight reports no unsupported entries in this declared environment. Four raw 3D custom definitions and typed stage aliases are recorded; built-in compute color-image declarations are also recorded. The pack emits existing option-menu/GTAO and item-property warnings, retained in `audit.log`.

This is not a Photon runtime compatibility result. No game, GPU context, texture upload, hardware feature query, shader dispatch, scene rendering or FPS measurement occurs. The inspector passing does not prove that every graphics shader compiles or renders correctly. Nether/End, Ultra/colored lights, live resource reload and actual device feature constraints are outside this audit.

Add `--screens` for the bounded first-deferred screen attempt. The real screen-pass planner transforms `deferred` to READY and resolves its volume samplers to `customtex1`, `customtex2`, and `customtex0`. Its planned sources are exported under `screen-pass/`. The actual production custom-uniform initializer cannot finish without a live Minecraft instance: `IrisExclusiveUniforms.WorldInfoUniforms.addWorldInfoUniforms` reads the current level. `screen-pass/attempt.json` records that precise blocker. No uniform fields or values are invented to bypass it, and graphics preparation/shaderc compilation is therefore not claimed by this harness. The separate color-images test owns exact compute-kernel shaderc results.
