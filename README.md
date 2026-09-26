# Iris Vulkan

An unofficial Iris fork with native Vulkan shader-pack rendering for Minecraft.

**Experimental Vulkan shaders:** selecting Vulkan enables a rendering path that may render incorrectly or crash with some shader packs, features, GPUs, or drivers. Compatibility and validation vary by build. Check the notes for the exact version you install.

## Downloads and compatibility

Use the [project releases](https://github.com/acattoXD/iris-vulkan/releases) to find published builds and their requirements. If no matching build has been published, source branches are available for development and testing.

Each build targets specific Minecraft, mod-loader, Java, and Sodium versions. Match all of them to that build's release notes and dependency metadata. Support in one version does not imply support in another, and Vulkan support does not imply complete OpenGL parity.

For a source checkout, the requirements are recorded in [build.gradle.kts](build.gradle.kts), the module build files, and [Fabric mod metadata](common/src/main/resources/fabric.mod.json). The generated mod JAR contains the resolved version and dependencies.

Version-specific changes and test results live in [docs](docs/) and published release notes. Historical notes apply only to the artifacts they describe. Some historical tests reference separately retained local evidence or shader packs; those inputs are not included in this repository.

## Release channels

| Branch | Purpose |
| --- | --- |
| [`alpha`](https://github.com/acattoXD/iris-vulkan/tree/alpha) | Active development and experimental builds. |
| [`beta`](https://github.com/acattoXD/iris-vulkan/tree/beta) | Release-candidate testing and stabilization. |
| [`release`](https://github.com/acattoXD/iris-vulkan/tree/release) | Source selected for stable releases after validation. |

Branch names describe the intended promotion flow. They do not turn an existing alpha build into a beta or stable release. The artifact's version and release notes determine its status. See [the branch workflow](docs/BRANCHES.md).

## Installation

1. Choose a build that matches your Minecraft version and loader, then install its required Java and Sodium versions.
2. Close Minecraft and place the mod JAR in that instance's `mods` folder. Keep only one Iris JAR: this fork retains the mod ID `iris` and replaces official Iris.
3. Download shader packs separately into the instance's `shaderpacks` folder.
4. Select Vulkan in Minecraft's video settings and restart when requested, then select the shader pack in Iris.

Use a separate test instance and preserve your worlds and settings when trying experimental builds. Shader quality presets and supported features depend on the pack and release; performance is not guaranteed to improve on every system.

If a selected pack prevents startup, close Minecraft, set `enableShaders=false` in that instance's `config/iris.properties`, and restart. If Vulkan itself cannot initialize, return to a supported graphics backend through Minecraft's renderer settings.

## Building from source

Install the JDK required by the checked-out branch's Gradle toolchain. Use the included Gradle wrapper so the Gradle version matches the project.

For the Fabric target on Windows:

```powershell
.\gradlew.bat :fabric:build
```

On Linux or macOS:

```sh
./gradlew :fabric:build
```

Use `-Pbuild.release=true` when a release-version filename is needed. This flag controls the version format; it does not certify compatibility, promote a channel, or publish anything.

Compiled artifacts are written under the project's `build` directories. Use the installable Fabric mod JAR, not a common/development or diagnostic-probe artifact. Loader/platform support must be checked against the release notes, even when a corresponding source module exists.

## Testing and contributing

Targeted checks and their instructions are in [tests](tests/). A successful build or CPU shader compilation does not establish correct GPU output. Validate the exact artifact with the relevant shader pack, settings, Minecraft version, GPU, and driver.

Submit changes against `alpha` unless they are a focused fix for an existing release candidate. When reporting a problem in [Issues](https://github.com/acattoXD/iris-vulkan/issues), include the mod/Minecraft/loader/Sodium versions, graphics backend, GPU and driver, shader pack and preset, reproduction steps, and a relevant log excerpt with private information removed.

## Attribution and licensing

This project builds on [Iris](https://github.com/IrisShaders/Iris) and the [iris4vulkan base](https://github.com/fangbm/iris4vulkan). It is not an official Iris release and does not imply upstream endorsement. Original credits are retained in [UPSTREAM-README.md](docs/UPSTREAM-README.md) and source headers.

Preserve [LICENSE](LICENSE), [LICENSE-DEPENDENCIES](LICENSE-DEPENDENCIES), and [third-party notices](licenses/THIRD-PARTY-NOTICES.md). The `third-party-source` directory contains versioned dependency source material and provenance. `DHApi.jar` is a documented compile-only prerequisite with embedded sources; it is not a runtime mod.

When distributing a binary, provide its corresponding source and notices. Follow the [release checklist](docs/RELEASING.md); shader packs, Minecraft binaries, local worlds, credentials, and runtime logs do not belong in a source release.
