# Alpha6 changes and validation

Version: `1.11.3-vulkan-alpha.6+mc26.2`. Tested JAR SHA-256: `52a40d1ad74b1798696707c4b93d3a642f94593ab91ef564d571104f13783063`.

Requirements remain Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and exactly one of Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. Replace any earlier Iris JAR; this unofficial fork retains mod ID `iris`.

## World shaders without post-processing

`mineekshader.zip` contains gbuffers world programs but no final, composite, deferred, prepare, or shadow programs. Alpha5 rejects its empty screen-pass graph with `Native Vulkan screen graph could not be initialized`. The pack's world shaders can compile; the failure occurs when initializing the native frame renderer.

Alpha6 retains the world-frame renderer when the post-processing graph is empty and lets the executor reach presentation. Whenever the pack has no final shader, it copies the current logical `colortex0` to the main target, including frames where deferred passes already ran before translucents. A declared final shader continues to execute normally. No synthetic pack shader or gamma transform is added.

The direct copy requires matching source/destination format and dimensions. Mineek's default RGBA8 target meets this requirement; format conversion or scaling for other packs is outside this correction.

## Validation status

| Check | Alpha6 status |
| --- | --- |
| Empty graph, no-final presentation and no-shadow lifecycle | [21 CPU checks pass](../tests/gbuffers-only/README.md). They use the actual pack/parser/planner and compiled production control flow, including a previous-release negative control for all three presentation gates. GPU endpoints are recording fixtures. [Result](../build/gbuffers-only-tests/result.txt). |
| Actual Mineek world shaders | [12 shader stages pass preprocessing, transformation and native compilation](../build/mineek-contract-tests/result.txt), covering six gbuffers vertex/fragment pairs and the water material input contract. [Test command](../tests/native-world/verify-mineek.ps1). |
| Alpha5 runtime negative control | [The isolated Vulkan run reproduces the empty-graph exception](../build/release-validation/alpha5-mineek-repro/launcher.log). |
| Alpha6 Vulkan / Mineek | [All 10 scenarios completed](../build/release-validation/alpha6-mineek/native-probe-result.txt), including dimension changes, resize and reload. Screenshots show the pack's sky/water and were reviewed. [Provenance](../build/release-validation/alpha6-mineek/diagnostic-runtime-provenance.json) verifies the exact JAR and separate probe, with no unexpected sources. Logs confirm colortex0 RGBA8 identity presentation. |
| Alpha6 Vulkan / Complementary High | [All 10 scenarios completed](../build/release-validation/alpha6-complementary-high/native-probe-result.txt), with shadow readback checks and reviewed restored screenshot. [Provenance](../build/release-validation/alpha6-complementary-high/diagnostic-runtime-provenance.json) verifies the same exact JAR. The pack's own final pass remains active. |

The tested Mineek ZIP has SHA-256 `a343ed97a2e98ea87c0a3a0fe528c1987269becdb63c56513aaf31972ca04809`. CPU results establish the presentation decisions and shader contracts; they do not verify texture-copy pixels.

Runtime checks used Minecraft 26.2, Sodium 0.9.2, Windows 11, RTX 5070 / NVIDIA 616.92 and Java 25.0.3. The original shader ZIP was unchanged. These checks establish the tested startup/render/reload behavior, not full OpenGL visual parity or compatibility with every device and mod combination.

[Alpha5](ALPHA5.md) records the earlier End Portal and gateway validation for its own artifact. This release makes no FPS improvement or broad pack/device compatibility claim. Pair each new binary with its exact source/checksum bundle using [the release procedure](RELEASING.md); earlier release bundles remain unchanged.
