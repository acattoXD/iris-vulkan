# Native final-pass check

This standard Iris shaderpack is a backend test fixture, licensed under LGPL-3.0-only like this fork. It contains only `shaders/final.vsh` and `shaders/final.fsh`; it does not implement shadows, world materials, water, entities, or a BSL-style multipass renderer.

Copy this directory into an isolated instance's `shaderpacks` directory. Enable `native-final-check` in Iris with Minecraft's native Vulkan renderer selected. Launch the experimental source fork with:

```text
-Diris.vulkan.screenPassMode=final
-Diris.vulkan.screenPassDrawMode=shaderpack
```

Do not use diagnostic copy/constant modes or dummy texture bindings for this check. Expected behavior:

- Both halves show the same complete live world. The left half retains scene color; the right half applies a cyan tone. Looking around must update both halves, proving the shader samples actual scene color from `colortex0`.
- A four-pixel magenta line remains centered after resizing. Its position depends on the live `viewWidth` uniform.
- A yellow/dark band occupies 3.5% of the image height. Its extent depends on `viewHeight`, and its yellow portion sweeps across over eight seconds using `frameTimeCounter`.
- Disabling the pack restores ordinary full-width rendering. Reloading and changing dimensions must not leave stale scene data or GPU resources.

Expected native log evidence includes `Staged actual scene color into colortex0`, successful compilation of `final/input_colortex0` and the pack's final program, and `Rendered native Vulkan shaderpack final directly to the main color view`. Confirm Minecraft's actual device reports Vulkan separately; these messages alone are not a rendering test.

For repeatable visual checking, hide the HUD, use an even framebuffer width, and capture the client framebuffer after the final pass. `verify_capture.py` checks the mirrored scene/tone relation and marker geometry. Give it a second capture taken roughly one second later to check the animated uniform. These checks require actual captures; creating expected images does not establish backend support.

Final-only mode stages vanilla scene color into `colortex0` at the full scene resolution. Its supported texture inputs are `colortex0` and the standard `gcolor` alias. Other texture resources, including shadow textures, are rejected with an explicit log reason instead of accepting a cleared or fake texture. Loose uniforms must have an implemented native snapshot source. This mode does not execute any shaderpack gbuffer, shadow, composite, or compute program. The `all` mode retains the community fork's existing target routing and remains incomplete.
