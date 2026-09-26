# Experimental distribution checklist

This checklist applies to the **1.11.5-vulkan-alpha.22+mc26.3** candidate. It describes local preparation only; it does not publish a project, imply approval from Iris/Sodium/Mojang, or certify Vulkan compatibility. See [Alpha22 validation](ALPHA22.md) for the current scope; earlier artifact observations do not automatically validate a new build.

## Create the local bundle

Build the final Fabric JAR with JDK 25. In PowerShell, keep the dotted property quoted:

```powershell
.\gradlew.bat :fabric:build '-Pbuild.release=true'
```

After completing the final artifact's runtime checks, run:

```powershell
python tests/release/package_release.py --jar build/libs/<final-mod-jar>.jar
```

The script reads the JAR's actual version and nested dependencies, creates an immutable-version folder under `build/release`, and includes the runtime JAR, deterministic source ZIP, install notes, source/dependency inventory, and SHA-256 checksums. Use `--output` for another empty destination. It does not run Gradle, start Minecraft, upload files, or invent a public source URL. `--offline` works after all dependency source downloads are cached.

Sources are selected from explicit project directories. The archive retains original regression shader fixtures but rejects downloaded shader-pack archives, build/run directories, worlds, logs, symlinks, unexpected binary inputs, and recognized credential patterns. It includes the compile-only DH helper and exact dependency source JARs/POMs; official tagged glsl-transformer, ANTLR, and Fabric source archives supply additional build and grammar inputs. The generated manifest records that this packaging tool itself does not verify a clean rebuild. Test its archive guards with `python -m unittest discover -s tests/release -p test_package_release.py`.

## What recipients need

Distribute the final Fabric mod JAR, an exact matching source archive, release notes, and a SHA-256 manifest. Use one immutable version identifier for that set. A development/common JAR, probe mod, local Gradle cache, Minecraft installation, test world, recording, or third-party shader ZIP is not the installable release.

The installation instructions must name Minecraft 26.3, Java 25, Fabric Loader 0.19.5+, and Sodium 0.9.2+mc26.3. Install one Iris JAR only; the fork retains mod ID `iris`. Acquire shader packs separately, start with High, and use a separate test profile. Ordinary installation must work without probe mods, Gradle, or developer JVM switches.

Record the final-JAR smoke test separately from source-classpath tests. At minimum inspect world rendering, shader reload, High/Ultra change, walking/turning with bobbing, player shadows, and quit/reopen. Include the GPU, driver, OS, pack versions/options, artifact hash, and actual loaded-JAR source in evidence. Existing results on an RTX 5070 are not evidence for every GPU or a universal performance gain.

## Corresponding source and notices

Preserve root `LICENSE`, `LICENSE-DEPENDENCIES`, `licenses/`, upstream credits, and source-file headers. Include prominent dated fork-change notices and build instructions. The exact glsl-transformer [licensing statement](https://github.com/IrisShaders/glsl-transformer/blob/v3.0.0-pre3/README.md#licensing) describes AGPLv3 and separately granted alternatives. Do not assume a permission granted to another project also covers this fork.

A component-license list is not a complete distribution plan. LGPLv3 incorporates GPLv3 with additional permissions; GPLv3 section 13 permits combining with AGPLv3. The [GNU compatibility FAQ](https://www.gnu.org/licenses/gpl-faq.html.en#AllCompatibility) explains this route. Preserve component notices and meet the combined distribution's obligations instead of representing the bundled result as LGPL-only. No upstream license file is rewritten by this checklist.

The source download must correspond to the exact binary, including changes, build scripts, wrapper, resources, generated-source inputs, and required non-system source components. A link to unmodified upstream or a moving branch is insufficient. Offer the source beside the binary, or place clear instructions there for equivalent access on another server, and keep it available. See the [GNU source-distribution FAQ](https://www.gnu.org/licenses/gpl-faq.html.en#SourceAndBinaryOnDifferentSites) and [AGPLv3 section 6](https://raw.githubusercontent.com/IrisShaders/glsl-transformer/v3.0.0-pre3/LICENSE).

Before uploading, verify these concrete items:

- The source archive rebuilds from a clean directory using documented prerequisites. Both build scripts refer to compile-only `DHApi.jar`. Its embedded sources identify LGPLv3, Copyright 2020–2023 James Seibel, Distant Horizons `2.1.3-a-dev` / API 3.0.0; SHA-256 is `d87c8d0a92cddb4dd9142a037e83826abe5ea81efb93eb4fa2876017ea7a6dc4`. Include this helper with its embedded sources and LGPL/GPL notices in the corresponding source distribution, or document an equivalent reproducible replacement. It is not a runtime mod and must not enter the installable mod JAR. Verify its rebuild inputs before presenting the source archive as self-contained.
- Include or provide exact source retrieval instructions for glsl-transformer 3.0.0-pre3, ANTLR runtime 4.13.1, JCPP 1.4.14, and embedded Fabric modules. Include generator/grammar inputs where needed to rebuild generated parser classes.
- Dependency source reference downloads: [glsl-transformer sources](https://repo.maven.apache.org/maven2/io/github/douira/glsl-transformer/3.0.0-pre3/glsl-transformer-3.0.0-pre3-sources.jar), [ANTLR runtime sources](https://repo.maven.apache.org/maven2/org/antlr/antlr4-runtime/4.13.1/antlr4-runtime-4.13.1-sources.jar), and [JCPP sources](https://repo.maven.apache.org/maven2/org/anarres/jcpp/1.4.14/jcpp-1.4.14-sources.jar). A source JAR may omit build/generator inputs: verify completeness rather than assuming it is the whole corresponding source.
- Inspect every nested dependency and vendored package for required copyright, license and NOTICE files. See [third-party provenance](../licenses/THIRD-PARTY-NOTICES.md).
- Exclude shader ZIPs, transformed shader dumps, recordings, worlds, logs, personal paths/configuration, tokens, and unrelated parent-workspace files from the public source archive.

## Public page and release channel

Use an alpha channel, client-only environment, Fabric loader, and Minecraft 26.3. Supply exact required dependency versions and choose the runtime mod JAR as the primary file. Modrinth supports alpha/beta/release channels and dedicated source-file types; these are publishing fields, not compatibility certification. See [Modrinth version metadata](https://docs.modrinth.com/api/operations/createversion/).

Identify the project as an unofficial experimental fork, credit Iris and iris4vulkan, describe verified packs and limitations, and use this fork's real source/support URLs. Omit unknown contact URLs rather than routing users to official Iris support. The [Fabric manifest specification](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec) defines these optional contact fields. Disable the inherited official Iris updater until this fork has an appropriate update source.

Modrinth's [content rules](https://modrinth.com/legal/rules), last modified August 13, 2026, require accurate claims, dependency metadata, original-source credit and distribution rights. They permit license-abiding forks that substantially diverge. They also require applicable AI-content disclosures and prohibit projects primarily or entirely produced by generative AI. Review the actual project's composition and disclosures before choosing that host; this checklist does not claim acceptance or rejection.

## Known limits and recovery

Mark driver/pack coverage as experimental. Complementary **r5.8.1** Ultra's tested storage allocation alone is about 1.96 GiB before normal game resources; other versions/profiles differ, and High is the more modest starting profile. Apple Silicon/MoltenVK coverage for Alpha18 remains unverified. Do not promise automatic recovery: native shader construction and lazy draw/composite errors can still escape to a client failure, unlike some OpenGL startup paths.

Document recovery in release notes: close Minecraft, set `enableShaders=false` in the instance's `config/iris.properties`, and restart. Keep any user's selected pack and options intact. If Vulkan itself cannot initialize, use Minecraft's supported renderer selection or an isolated profile. Report failures to the fork maintainer with the version, GPU/driver, pack/options, and relevant log excerpt after removing personal information.
