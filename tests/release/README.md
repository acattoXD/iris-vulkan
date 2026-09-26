# Packaged Fabric validation

This harness validates and prepares the actual distributable JAR in a fresh local
Minecraft directory. It never starts Minecraft, downloads artifacts, changes an
existing instance, or distributes Minecraft, Sodium, or shader packs.

```powershell
python tests/release/packaged_run.py prepare `
  --jar build/libs/iris-vulkan-experimental-<version>.jar `
  --jdk 'C:/Program Files/Java/jdk-25.0.3' `
  --run-dir build/release-validation/vulkan
```

Use `--backend opengl` for the other backend. Optionally provide `--shader-pack`
and `--ultra` for an already obtained local Complementary pack. `--world-source`
copies an explicitly selected **closed test world** into the fresh directory;
it does not alter that world or use a running manual instance.
For a separately verified compatibility trial, `--sodium-jar` accepts an explicit
local publisher artifact. Omitting it retains beta3 for matched optimization
benchmarks. Preparation checks the selected Sodium metadata's Iris exclusion
range before copying the files; it does not override Fabric dependency rules.

Preparation checks the official client JAR and pinned dependency hashes, cached
assets, declared resource/mixin/nested-JAR closure, mandatory mod presence, and
Iris-owned class references. The only top-level installed mods are the packaged
Iris JAR and the selected Sodium JAR. Minecraft comes from the byte-for-byte
official client download, not Loom's merged or access-widened development JAR.
Fabric Loader's production installer metadata supplies its libraries; development
libraries are excluded. There are no Iris JVM switches, dev-launch injector,
Loom class/resource directories, or disposable probe mod.

After the root task has serialized other game launches, run the generated
`launch.ps1`. The game window is visible and all console output goes to
`launcher.log`. This is a local test identity, not an authenticated multiplayer
launcher. Ordinary JVM class-load logging records the code source of every Iris
class without installing a verification mod or modifying runtime behavior.

```powershell
python tests/release/packaged_run.py inspect --run-dir build/release-validation/vulkan
```

`packaged-runtime-provenance.json` verifies that loaded Iris classes came from
the exact installed JAR whose SHA-256 was recorded before launch. This proves
packaged provenance; separately inspect the backend log, world rendering,
configuration warning, and screenshots. The harness does not label a title-screen
launch as a rendering or shader-compatibility pass.
