# Native camera regressions

After compiling the project and generating the isolated runtime classpath, run:

```powershell
./tests/projection/verify.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3'
```

The tests run on the CPU without launching Minecraft or creating GPU resources:

- Compile and execute the production mixin plugin filter for both graphics backends.
- Execute the production camera hook and public API gate against real native, OpenGL, vanilla, and null pipeline types. Test-only allocation skips constructors that would create GPU resources.
- Check reverse-depth conversion and unchanged clip positions with walking, hurt, and nausea transforms. Reproduce Sildur's sparse inverse-projection reconstruction and confirm the old projection/model-view split fails it.
- Check the shared camera hook and native world hooks against the installed Minecraft/Sodium bytecode.

The two small stubs supply platform startup and the current pipeline; the filter, camera hook, API predicate, matrix conversion, and pipeline classes remain production code. They are compiled only into the dedicated test output directory.

The regression had two gates: the native backend filter excluded `MixinModelViewBobbing`, and that hook checked an OpenGL-only quick shader-active predicate. Enabling the shared hook and using the public API predicate moves walking, hurt, and nausea effects into model-view while keeping the projection/model-view product unchanged. Sildur's sparse inverse-projection reconstruction requires this matrix split.

For the visible in-game camera probe, supply a separately obtained Sildur v2.01 High ZIP:

```powershell
.\gradlew.bat :fabric:runClient -I tests/native-world/isolated-launch.init.gradle -PirisProbe -PirisProbeWorld -PirisProbeCamera -PirisProbeRunDirectory=run-vulkan-camera-after '-PirisProbePack=C:/path/to/Sildurs-Vibrant-v2.01-High.zip' --console=plain --no-daemon
```

The probe records still, turning, simulated walking with vanilla view bob, and walking while turning. `camera-motion-report.json` includes per-frame matrices and projection/model-view invariants, and sequential screenshots permit visual review. Camera mode requires a visible dedicated run directory and cannot be combined with `irisProbeHidden`, interactive, or other motion/scenario probes.

The September 13 baseline in `fabric/run-vulkan-camera-before/evidence` recorded 160 frames, 80 with nonzero bob. Its maximum projection deviation was 0.17129356 while the combined projection/model-view product remained within 1.1920929e-7. The baseline deliberately used record-only mode and reported failed matrix invariants.

The corrected `fabric/run-vulkan-camera-after/evidence/camera-motion-report.json` records the same 160 frames and 80 bob frames, all matrix invariants passing, zero pure-projection deviation, and maximum combined-product error 5.9604645e-8. Both runs save 24 screenshots and matching build provenance. Reviewed before/after `camera-walk-100.png` captures show the misplaced sky, reflections, and doubled chest corrected; corrected sequential walk-turn frames 139 and 140 show continuous sky without rectangular blocks. These results cover the reproduced Sildur camera defect. CPU/matrix checks alone do not establish visual correctness, and broader pack parity, reflection behavior, and performance remain unverified.
