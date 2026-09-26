# End portal / gateway Vulkan fixture

This optional development probe creates a new `iris_native_probe_*` flat world in
an explicitly selected test directory. It never opens an existing world. Its
classes and mixins are in the separate `iris-native-probe.jar`, excluded from the
published Iris jar. It requires a visible Vulkan window and an explicitly
selected Complementary Unbound r5.9.1 `HIGH` or `ULTRA` profile.

The six screenshots are: an eleven-eye inactive ring; the same ring activated by
calling the actual Ender Eye item's `useOn` with a server-player `UseOnContext`;
an End Gateway by itself with the portal interior removed; both surfaces removed; the ring activated again and
gateway recreated; and the recreated scene after `Iris.reload()`.

Each scene discards 120 settling frames after the client receives the expected
block states, then resets every draw counter and measures a separate 60 stable
frames. A block-state or camera-position mismatch restarts this process.
This keeps deferred draws extracted from the previous scene out of the strict
zero-count checks for inactive/removed surfaces. The first-person camera stands on an invisible support barrier at
`(0.5, 67.0, 8.5)`, yaw 180, pitch 35, FOV 70, giving a clear elevated view of the
portal surface and gateway faces without relying on creative flight.
The probe verifies nine actual portal blocks after item activation,
separate main-view portal/gateway renderer submissions, and completed native
draws carrying the pack's portal material ID 5025. At those completed draws it
checks Position/Color/UV0/UV1/UV2/Normal input elements and that Sampler0 refers to
the actual end_portal texture. The gateway-only view requires completed surface
draws without any portal submissions, proving the gateway cube independently.
The two exact vanilla beacon-beam pipelines also carry material 5025 but use a
different contract: their Position/Color/UV0/UV2 inputs and end_gateway_beam
Sampler0 are checked and counted separately. Beam draws cannot satisfy the
surface requirement. Other pipelines, including END_PORTAL/END_GATEWAY, remain
subject to the full portal surface contract. Counters must disappear in inactive/removed scenes
and return after recreation/reload. Reload must construct a new shader-pack
instance. These assertions verify draw execution and inputs; screenshot review
is still required for portal appearance and lighting.

Prepare the sidecar from the real pack's profile rather than using the older
`irisProbeUltra` fixture (which has different pack-version assumptions):

```powershell
$pack = 'C:/path/to/ComplementaryUnbound_r5.9.1.zip'
python tests/storage-2d/extract_profile.py --pack $pack --profile HIGH --output fabric/run-vulkan-portal-high/shaderpacks/ComplementaryUnbound_r5.9.1.zip.txt
./gradlew.bat :fabric:runClient '-PirisProbe' '-PirisProbeWorld' '-PirisProbePortal' '-PirisProbeStorage' '-PirisProbeExpectedProfile=HIGH' '-PirisProbeRunDirectory=run-vulkan-portal-high' "-PirisProbePack=$pack"
```

Use a separate `run-vulkan-portal-ultra` directory and change both profile arguments
to `ULTRA` for the second run. `prepareNativeProbe` copies the selected pack and
preserves the supplied sidecar. `NativePackResourceEvidence` compares every
profile option to the live pack and records custom-image allocations/sampler
associations and compute activity where present; it performs no puddle texel
readback. The automated test explicitly acknowledges the experimental warning
in its own config. It is not a normal-install or warning-UI test.

Results are in the chosen run directory:

- `native-probe-result.txt`: `IRIS_PORTAL_CAPTURED` only after all six stages pass.
- `evidence/portal-report.json`: counters and contracts for all stages.
- `evidence/portal-*-state.json`, `portal-*-pack-resources.json`, and `portal-*.png`:
  exact pack hash/profile, camera, submitted/completed draw evidence, resources,
  and screenshots.
- `evidence/loaded-build.json`: class-resource hashes including the portal probe
  and its three optional mixins.

Preparation validation: all probe sources compile using the local Java 25
compiler and current 26.2 classpath; the probe mixin JSON parses. Actual GPU
execution is a separate required check. No GPU pass or visual verdict is implied
by the compilation check.
