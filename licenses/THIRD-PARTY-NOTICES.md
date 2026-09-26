# Third-party components and license provenance

Prepared September 13, 2026 for the unofficial experimental native Vulkan fork. Upstream copyright and source-file license headers remain intact. The root `LICENSE` is unchanged. This file records component licenses; it does not grant permission on behalf of another author or imply upstream endorsement.

| Component | Version/source | License and retained notice |
| --- | --- | --- |
| Iris and the iris4vulkan base | Base `fangbm/iris4vulkan` commit `424e96796d21d0810aadbc609db698c3c3a8b342`; see project README | LGPL-3.0-only in root `LICENSE` and `LGPL-3.0.txt`; `GPL-3.0.txt` supplies the incorporated GPL terms. Preserve upstream contributors and notices. |
| glsl-transformer | `io.github.douira:glsl-transformer:3.0.0-pre3` | AGPL-3.0 in `AGPL-3.0.txt`; author douira. The [exact-tag licensing statement](https://github.com/IrisShaders/glsl-transformer/blob/v3.0.0-pre3/README.md#licensing) describes AGPL licensing and separately granted alternative permissions. No fork-wide alternative permission is asserted here. |
| Apache Commons Collections subset inside glsl-transformer | `org/apache/commons/collections4` in glsl-transformer 3.0.0-pre3 sources | Apache-2.0; exact bundled source-tree license retained as `GLSL-Transformer-Commons-Collections-Apache-2.0.txt`. Source headers attribute the Apache Software Foundation and contributors. |
| ANTLR runtime | `org.antlr:antlr4-runtime:4.13.1` | BSD-3-Clause in `ANTLR-4.13.1-BSD-3-Clause.txt`, including the ANTLR Project copyright and disclaimer. |
| JCPP | `org.anarres:jcpp:1.4.14` | Apache-2.0 in `Apache-2.0.txt`. Exact source archive `Preprocessor.java` carries: Copyright (c) 2007-2015, Shevek; Anarres C Preprocessor. Preserve individual source headers for their full year ranges. |
| Ithaka graph classes | Vendored `common/src/vendored/java/de/odysseus/ithaka` | Apache-2.0 in `Apache-2.0.txt`. Copyright 2012 Odysseus Software GmbH, retained in every included source file. |
| Embedded Fabric API modules | Exact versions in the built JAR's nested `fabric.mod.json` files | Apache-2.0. Preserve each embedded module's own license and attribution files; the final artifact inventory identifies which modules were bundled. |
| Distant Horizons API compile prerequisite | Local `DHApi.jar`; embedded `ModInfo.java` identifies `2.1.3-a-dev`, API 3.0.0 | Embedded source headers specify LGPLv3, Copyright (C) 2020-2023 James Seibel. Accompany any source-bundle copy with its embedded Java sources and `LGPL-3.0.txt` / `GPL-3.0.txt`. Compile-only: not bundled in the installable mod. SHA-256: `d87c8d0a92cddb4dd9142a037e83826abe5ea81efb93eb4fa2876017ea7a6dc4`. |

Sodium, Fabric Loader, Minecraft, and user-selected shader packs are installed separately. Their inclusion in a development runtime is not permission to redistribute their binaries or assets inside this fork. In particular, Sildur's test ZIP carries an All Rights Reserved notice and is not a release asset.

## Exact text sources

The following files were copied without editing their text:

- `LGPL-3.0.txt`: this checkout's existing root `LICENSE`.
- `GPL-3.0.txt`: [GCC's upstream copy of GNU GPLv3](https://raw.githubusercontent.com/gcc-mirror/gcc/master/COPYING3), retrieved September 13, 2026.
- `AGPL-3.0.txt`: [glsl-transformer v3.0.0-pre3 LICENSE](https://raw.githubusercontent.com/IrisShaders/glsl-transformer/v3.0.0-pre3/LICENSE).
- `GLSL-Transformer-Commons-Collections-Apache-2.0.txt`: [license retained in that exact glsl-transformer source tree](https://raw.githubusercontent.com/IrisShaders/glsl-transformer/v3.0.0-pre3/glsl-transformer/src/main/java/org/apache/commons/collections4/LICENSE.txt).
- `ANTLR-4.13.1-BSD-3-Clause.txt`: [ANTLR 4.13.1 LICENSE](https://raw.githubusercontent.com/antlr/antlr4/4.13.1/LICENSE.txt).
- `Apache-2.0.txt`: [Apache Software Foundation license text](https://www.apache.org/licenses/LICENSE-2.0.txt). JCPP's [upstream license](https://github.com/shevek/jcpp/blob/master/LICENSE) and the exact 1.4.14 source-file headers identify Apache-2.0.

## Distribution terms

The component-license array in mod metadata is an inventory, not a choice that lets recipients ignore another bundled component's obligations. Retaining LGPL notices does not by itself settle the AGPL-linked distribution. The GNU [license FAQ](https://www.gnu.org/licenses/gpl-faq.html.en#AllCompatibility) explains LGPLv3/GPLv3/AGPLv3 compatibility: LGPLv3's additional permissions can be set aside, and GPLv3 permits a GPLv3/AGPLv3 combination under section 13. Preserve the original notices and satisfy the corresponding-source and combined-work terms applicable to the distributed combination. No additional licensing permission needs to be invented or presumed.

For source delivery and release checks, see `docs/RELEASING.md` in the corresponding source archive.
