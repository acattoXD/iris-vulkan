# Visible name-tag and falling-block fixture

This opt-in probe creates a fresh `iris_native_probe_*` world in the selected
disposable directory. It requires a visible Vulkan window and never opens or
copies an existing world. All fixture code lives in the separate probe JAR.

The six captures show named mobs against sky and water, suspended sand/gravel
`FallingBlockEntity` objects beside ordinary placed sand/gravel, elevated and
opposite lower camera angles covering all six entity faces, the elevated view
with vanilla entity shadows disabled, and the entities after five real falling
physics ticks. Server ticking is frozen for repeatable static views. The final
view advances exactly five requested ticks after gravity is restored. HUD is
visible because hiding it also suppresses name tags.

Each view waits for client entity/block/position updates, discards 120 frames,
then measures 40 frames. The optional draw mixin counts successful main-view
`PreparedRenderType.drawFromBuffer` returns, grouped as `TEXT`, `TEXT_BG`,
`TERRAIN_SOLID`, and `ENTITY_SHADOW`. GUI text and the pack's shadow passes are
excluded. Name views require text coverage; falling-block views
require solid block draws. The two named mobs and two falling entities must be
present on the client. Reports include positions, pipeline identities, input
formats and captured material IDs.

Vanilla name-tag backgrounds can use the font atlas's white glyph in the same
`TEXT` batch as the letters. `TEXT_BG` is recorded when present but is not a
required name-tag draw. Index counts/materials are recorded; screenshot review
must establish the background's actual appearance.

`ENTITY_SHADOW` identifies vanilla shadow quads, not block faces or shader-pack
shadow maps. Its count may already be zero with the option enabled when Iris
suppresses these quads for a shadow-mapped pack. Turning the option off must
produce zero such draws. Compare the matching `entity-faces-upper.png` and
`entity-faces-upper-no-blob.png` when investigating a black shape.

Prepare the same fixture against the immutable alpha6 JAR and the candidate,
using the same newly built probe, pack, profile, Sodium and window dimensions:

```powershell
./gradlew.bat :fabric:probeJar -PirisProbe '-Pbuild.release=true'
python tests/release/prepare_entity_faces.py --jar build/release/friends-alpha6/iris-vulkan-experimental-1.11.3-vulkan-alpha.6+mc26.2.jar --jdk 'C:/Program Files/Java/jdk-25.0.3' --run-dir build/release-validation/alpha6-entity-faces-high --shader-pack 'C:/path/to/ComplementaryUnbound_r5.9.1.zip' --profile HIGH
./build/release-validation/alpha6-entity-faces-high/launch.ps1
python tests/release/inspect_probe_profile.py --run-dir build/release-validation/alpha6-entity-faces-high
```

For the candidate, change `--jar` and use a new run directory such as
`alpha7-entity-faces-high`. For Mineek, supply its ZIP, omit `--profile`, and use
distinct `*-entity-faces-mineek` directories. The preparer refuses existing
directories, records exact production/probe/pack hashes, acknowledges the test
warning in that directory only, and adds diagnostic `iris.vulkan.probe.*` flags.
It does not launch the game. The launch command opens a visible game window;
it has no hidden-window default.

Results are `native-probe-result.txt` (`IRIS_ENTITY_FACES_CAPTURED` on completed
coverage), six `evidence/entity-*.png` files, per-view state JSON and
`evidence/entity-faces-report.json`. `evidence/loaded-build.json` includes the
new probe and optional draw mixin. The inspector verifies production/probe
provenance separately. Successful compilation/counters are not a visual verdict;
review name-tag opacity and every visible falling-block face in both artifacts.
