# Render-target planning regressions

`./gradlew :common:verifyNativeTargetIndices` checks the declaration/reference scanner, 32 logical indices, aliases and the independent eight-attachment limit. This task does not create a GPU device.

After compiling production, run the parser and allocation replay from the workspace root:

```powershell
python ports/iris-vulkan-26.3/tests/target-indices/verify-liveness.py --output ports/iris-vulkan-26.3/build/new-liveness-evidence
```

The runner refuses an existing output directory and uses frozen source inventories plus an Alpha12 baseline JAR. It loads the actual ProgramSet/property parsers and actual old/new target-model bytecode. GPU device calls, live fog and the native-mode gate are replaced with recording facades; the model's allocation, clear, reuse and destruction loops execute unchanged. These facades are test-only and never enter the mod JAR.

`build/alpha13-liveness-final` passed2,203 checks against the final Gradle classes. High drops only target8 in all three dimensions, saving58,982,400 nominal texture bytes at2560x1440 and one steady-frame clear. Ultra retains target8 and all other targets. Retained specifications and clear commands/values match the baseline. Synthetic cases cover all graphics/compute stages and independent requirements; lifecycle cases cover preset changes, resize, reuse and no-pack fallback.

This is CPU/recorded-command evidence. It does not execute shaders, query driver VRAM use or measure FPS. Existing Alpha12 depth GPU proof applies to its unchanged depth code; game validation of the combined Alpha13 build remains separate.
