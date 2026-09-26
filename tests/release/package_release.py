"""Build an auditable local alpha/source bundle. Never builds, launches, or publishes.

Usage: python tests/release/package_release.py --jar build/libs/<final>.jar
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import urllib.request
import zipfile


ROOT_FILES = (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
    "gradlew.bat", ".editorconfig", ".gitignore", "jitpack.yml", "LICENSE",
    "LICENSE-DEPENDENCIES", "README.md", "DHApi.jar",
    "common/build.gradle.kts", "fabric/build.gradle.kts", "neoforge/build.gradle.kts",
)
SOURCE_DIRS = ("common/src", "fabric/src", "neoforge/src", "gradle", "docs", "licenses", "tests")
FORBIDDEN_PARTS = {".git", ".gradle", "__pycache__", "node_modules", "saves", "logs", "crash-reports", "iris-vulkan-dumps"}
SECRET_NAMES = {".env", "credentials", "credentials.json", "secrets.json", "options.txt", "usercache.json", "servers.dat", "launcher_accounts.json"}
SECRET_PATTERNS = (
    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b",
    rb"\bgithub_pat_[A-Za-z0-9_]{50,}\b",
    rb"\bAKIA[0-9A-Z]{16}\b",
    rb"\bxox[baprs]-[0-9A-Za-z-]{20,}\b",
)
PRIVATE_PATH_PATTERNS = (
    rb"[A-Za-z]:" + rb"\\Users\\",
    rb"/" + rb"Users/",
    rb"\." + rb"codex[\\/]",
)
MAX_DOWNLOAD = 128 * 1024 * 1024
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def safe_name(name: str) -> bool:
    path = PurePosixPath(name)
    return not path.is_absolute() and ".." not in path.parts and "\\" not in name and ":" not in name


def assert_public_path(name: str) -> None:
    if not safe_name(name):
        raise ValueError(f"Unsafe archive path: {name}")
    parts = PurePosixPath(name).parts
    if any(p.lower() in FORBIDDEN_PARTS or p.lower() in SECRET_NAMES for p in parts):
        raise ValueError(f"Private/runtime path rejected: {name}")
    if any(p.lower().startswith("run-") for p in parts) or any(p.lower() == "build" for p in parts):
        raise ValueError(f"Build/run output rejected: {name}")
    # These version-controlled regression fixtures are source code, not downloaded packs.
    shader_fixtures = ("tests/shaderpacks/native-final-check/", "common/src/disabledTest/resources/shaderpacks/")
    if "shaderpacks" in parts and not name.startswith(shader_fixtures):
        raise ValueError(f"Third-party shader-pack input rejected: {name}")
    if name.lower().endswith((".log", ".mp4", ".mkv", ".mca", ".mcr", ".dat", ".lock", ".pem", ".key", ".p12")):
        raise ValueError(f"Runtime/private artifact rejected: {name}")


def assert_no_credentials(name: str, data: bytes) -> None:
    if any(re.search(pattern, data) for pattern in SECRET_PATTERNS):
        raise ValueError(f"Credential-like content found in {name}; do not package it")


def assert_no_private_paths(name: str, data: bytes) -> None:
    """Reject workstation-specific paths that make a source archive non-portable."""
    if any(re.search(pattern, data, re.IGNORECASE) for pattern in PRIVATE_PATH_PATTERNS):
        raise ValueError(f"Workstation-specific path found in {name}; replace it with a placeholder")


def source_files(root: Path) -> dict[str, bytes]:
    files = {}
    for name in ROOT_FILES:
        path = root / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing or symlinked source prerequisite: {name}")
        files[name] = path.read_bytes()
    for directory in SOURCE_DIRS:
        for path in sorted((root / directory).rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Symlinks are not accepted in source bundles: {path.relative_to(root)}")
            if not path.is_file():
                continue
            name = path.relative_to(root).as_posix()
            if "__pycache__" in path.parts or path.suffix == ".pyc":
                continue
            assert_public_path(name)
            if path.suffix.lower() in {".zip", ".jar", ".class"} and name != "gradle/wrapper/gradle-wrapper.jar":
                raise ValueError(f"Unexpected binary/source input: {name}")
            files[name] = path.read_bytes()
    for name, data in files.items():
        assert_public_path(name)
        if not name.endswith(".jar"):
            assert_no_credentials(name, data)
            assert_no_private_paths(name, data)
    return files


def inspect_runtime(jar_data: bytes):
    nested = []
    with zipfile.ZipFile(io.BytesIO(jar_data)) as jar:
        for name in jar.namelist():
            if not safe_name(name):
                raise ValueError(f"Unsafe runtime JAR member: {name}")
            if name.startswith("net/irisshaders/iris/probe/") or name == "iris-native-probe.mixins.json":
                raise ValueError("Probe classes must not be included in the installable mod")
            if any(p in {"shaderpacks", "saves", "logs", "iris-vulkan-dumps"} for p in PurePosixPath(name).parts):
                raise ValueError(f"Runtime JAR contains test/user data: {name}")
            if any(p.lower() in SECRET_NAMES for p in PurePosixPath(name).parts):
                raise ValueError(f"Runtime JAR contains a private configuration file: {name}")
            if name.endswith((".json", ".properties", ".yaml", ".yml", ".txt", ".md")):
                assert_no_credentials(name, jar.read(name))
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata.get("id") != "iris" or metadata.get("environment") != "client":
            raise ValueError("Expected the client-only Iris fork JAR")
        if "${" in metadata.get("version", ""):
            raise ValueError("Runtime JAR has unexpanded version metadata")
        for item in metadata.get("jars", []):
            name = item["file"]
            blob = jar.read(name)
            nested.append({"file": name, "sha256": sha(blob), "coordinate": coordinate_for(name)})
        required = {"LICENSE", "LICENSE-DEPENDENCIES", "licenses/AGPL-3.0.txt", "licenses/GPL-3.0.txt", "licenses/THIRD-PARTY-NOTICES.md"}
        missing = required.difference(jar.namelist())
        if missing:
            raise ValueError(f"Runtime JAR is missing license materials: {sorted(missing)}")
    return metadata, nested


def coordinate_for(name: str) -> str:
    filename = PurePosixPath(name).name
    for artifact, group in (("antlr4-runtime", "org.antlr"), ("glsl-transformer", "io.github.douira"), ("jcpp", "org.anarres")):
        if filename.startswith(artifact + "-") and filename.endswith(".jar"):
            return f"{group}:{artifact}:{filename[len(artifact) + 1:-4]}"
    match = re.fullmatch(r"(fabric-.+)-(\d[^/]+)\.jar", filename)
    if match:
        return f"net.fabricmc.fabric-api:{match[1]}:{match[2]}"
    raise ValueError(f"Unknown nested dependency; add its verified source location before release: {name}")


def download(url: str, cache: Path, offline: bool) -> bytes:
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / sha(url.encode())
    if target.is_file():
        data = target.read_bytes()
    else:
        if offline:
            raise ValueError(f"Offline source missing: {url}")
        request = urllib.request.Request(url, headers={"User-Agent": "IrisVulkanSourceBundle/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(MAX_DOWNLOAD + 1)
        if len(data) > MAX_DOWNLOAD:
            raise ValueError(f"Source archive exceeds limit: {url}")
        target.write_bytes(data)
    return data


def dependency_sources(nested, root: Path, cache: Path, gradle_cache: Path, offline: bool):
    files, provenance = {}, []
    for item in nested:
        coordinate = item["coordinate"]
        group, artifact, version = coordinate.split(":")
        repository = "https://maven.fabricmc.net" if group == "net.fabricmc.fabric-api" else "https://repo.maven.apache.org/maven2"
        directory = f"{group.replace('.', '/')}/{artifact}/{version}"
        for suffix in ("-sources.jar", ".pom"):
            filename = f"{artifact}-{version}{suffix}"
            url = f"{repository}/{directory}/{filename}"
            cached = sorted((gradle_cache / group / artifact / version).glob(f"*/{filename}"))
            data = cached[0].read_bytes() if cached else download(url, cache, offline)
            if suffix.endswith(".jar"):
                validate_upstream_zip(data, filename)
            elif not data.lstrip().startswith(b"<") or b"<project" not in data[:2000]:
                raise ValueError(f"Invalid Maven metadata: {filename}")
            path = f"third-party-source/{artifact}-{version}/{filename}"
            files[path] = data
            provenance.append({"coordinate": coordinate, "file": path, "url": url, "sha256": sha(data)})

    # Maven source JARs often omit build scripts and grammar inputs. Include the
    # official tagged source distributions as well for these generated libraries.
    versions = {c["coordinate"].split(":")[1]: c["coordinate"].split(":")[2] for c in nested}
    archives = []
    if "glsl-transformer" in versions:
        v = versions["glsl-transformer"]
        archives.append((f"glsl-transformer-{v}-upstream.zip", f"https://codeload.github.com/IrisShaders/glsl-transformer/zip/refs/tags/v{v}"))
    if "antlr4-runtime" in versions:
        v = versions["antlr4-runtime"]
        archives.append((f"antlr4-{v}-upstream.zip", f"https://codeload.github.com/antlr/antlr4/zip/refs/tags/{v}"))
    fabric_version = re.search(r'val FABRIC_API_VERSION by extra \{ "([^"]+)"', (root / "build.gradle.kts").read_text())
    if any(c["coordinate"].startswith("net.fabricmc.fabric-api:") for c in nested):
        if not fabric_version:
            raise ValueError("Cannot identify Fabric API source tag from the build script")
        v = fabric_version[1]
        archives.append((f"fabric-api-{v}-upstream.zip", f"https://codeload.github.com/FabricMC/fabric/zip/refs/tags/{v}"))
    for filename, url in archives:
        data = download(url, cache, offline)
        validate_upstream_zip(data, filename)
        path = "third-party-source/" + filename
        files[path] = data
        provenance.append({"file": path, "url": url, "sha256": sha(data), "kind": "upstream-build-inputs"})
    return files, provenance


def validate_upstream_zip(data: bytes, label: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"Corrupt source archive: {label}")
        for item in archive.infolist():
            if not safe_name(item.filename):
                raise ValueError(f"Unsafe upstream source path in {label}: {item.filename}")


def validate_dh(data: bytes):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = set(archive.namelist())
        if any(not safe_name(name) for name in names):
            raise ValueError("Unsafe DH prerequisite archive path")
        sources = {name[:-5] for name in names if name.endswith(".java")}
        missing = [name for name in names if name.endswith(".class") and name[:-6].split("$")[0] not in sources]
        if missing:
            raise ValueError(f"DH compile prerequisite is missing source: {missing}")
        mod_info = archive.read("com/seibel/distanthorizons/coreapi/ModInfo.java").decode()
        if "GNU LGPL v3 License" not in mod_info:
            raise ValueError("DH compile prerequisite has unrecognized licensing")
        version = re.search(r'String VERSION = "([^"]+)"', mod_info)
    return {"file": "DHApi.jar", "sha256": sha(data), "sourceFiles": len(sources), "version": version[1] if version else "see embedded ModInfo.java", "purpose": "compile-only; Java sources embedded; LGPLv3/GPLv3 texts in licenses/"}


def write_zip(path: Path, files: dict[str, bytes], prefix: str) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            if not safe_name(name):
                raise ValueError(f"Unsafe source path: {name}")
            info = zipfile.ZipInfo(prefix + "/" + name, FIXED_TIME)
            info.create_system = 3
            info.external_attr = (0o100755 if name == "gradlew" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def install_notes(metadata, runtime_name: str, source_name: str) -> bytes:
    deps = metadata.get("depends", {})
    alpha = re.search(r"(?:^|-)alpha\.(\d+)(?:[+.\-]|$)", metadata["version"])
    release_doc = f"docs/ALPHA{alpha[1]}.md" if alpha and alpha[1] in {"2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "18", "19", "20", "21", "22"} else "docs/RELEASING.md"
    release_context = (
        "Alpha22 supplies explicit white material textures for untextured native cloud meshes, matching Iris's OpenGL "
        "contract and fixing the missing gtexture failure reported after Alpha21. White uploads before draw passes; "
        "custom pack overrides and textured producers retain their own bindings. The full cloud-resource CPU replay "
        "passes, including original-failure controls. In-game visual verification remains pending. See docs/ALPHA22.md."
        if alpha and alpha[1] == "22" else
        "Alpha21 fixes the missing iris_CloudInfo buffer binding reported when BSL10.1.8 renders native clouds. "
        "Both flat and fancy cloud pipelines now receive the engine's current CloudInfo slice through the existing alias binder. "
        "The Alpha20 failure is reproduced by the control regression and the patched binding checks pass. "
        "No in-game visual test or performance improvement is claimed. Rethinking Voxels geometry remains unsupported. "
        "See docs/ALPHA21.md for scope and verification limits."
        if alpha and alpha[1] == "21" else
        "Alpha20 adds graphics-stage built-in color-image bindings needed by Rethinking Voxels prepare passes. "
        "The exact image declarations pass CPU allocation and real shaderc/SPIR-V/reflection checks. "
        "Rethinking's shadow geometry stage remains unsupported because Minecraft26.3's bundled RenderPearl API exposes "
        "only vertex and fragment stages. No in-game Rethinking run or GPU/FPS claim is made. See docs/ALPHA20.md."
        if alpha and alpha[1] == "20" else
        "Alpha19 addresses BSL10.1.5 shadow-sampler helper compilation and Derivative25.1.0 boss-state uniforms "
        "and item-map parsing. Targeted CPU regressions reproduce the original failures and validate the corrections; "
        "full in-game rendering on this new JAR remains unverified. Rethinking Voxels still needs unsupported geometry "
        "and graphics-stage color-image features and remains blocked by preflight. Graphics compiler optimization "
        "and shader quality settings are unchanged. See docs/ALPHA19.md for scope and limitations."
        if alpha and alpha[1] == "19" else
        "Alpha18 is a Photon rendering-correction candidate. It preserves the selected material shader when pack blending "
        "changes, renders opaque held items before deferred lighting, and includes the Alpha17 sampler-parameter and unused "
        "vertex-input compiler corrections. A post-build copied-world run captured17 moving frames before focus loss and "
        "restored input; review found corrected glass/slime and held-item rendering, and the user manually confirmed normal "
        "pool water. Underwater coverage and broader device/pack visual verification remain incomplete. "
        "Graphics compiler optimization remains unchanged. See docs/ALPHA18.md for scope and validation limits."
        if alpha and alpha[1] == "18" else
        "Alpha16 is a Photon resource-support candidate. It adds typed custom-volume aliases, RGB16F/R8 raw volumes "
        "with correct filtering/wrapping, and compute-only writable color-buffer aliases with validated formats and allocation usage. "
        "A compute comment-parser bug is corrected. CPU preflight, allocation contracts and the actual Photon compute shader pass; "
        "full native graphics/compute rendering remains unverified. Graphics compiler optimization remains unchanged. "
        "See docs/ALPHA16.md for exact coverage and pending runtime checks."
        if alpha and alpha[1] == "16" else
        "Alpha15 fixes a reachable mixed-pass texture binding bug: Sodium terrain now takes its atlas/lightmap from "
        "Sodium's bindings instead of an earlier entity, font or particle texture. Custom texture and glint precedence remain intact. "
        "Two missing world variants are added to existing one-time warmup to move their first-use compilation hitches earlier. "
        "CPU binding checks pass; moving-camera visibility, underwater rendering and persistent-white TNT still need runtime verification. "
        "Graphics compiler optimization is unchanged. See docs/ALPHA15.md for exact scope and evidence limits."
        if alpha and alpha[1] == "15" else
        "Alpha14 restores shader-pack option names, translated values, descriptions and pack-defined color codes on Vulkan. "
        "It enables the existing backend-neutral language mixin that the Vulkan startup filter had skipped. "
        "Rendering and compiler options are unchanged from Alpha13. The full Fabric build passes; in-game menu verification "
        "on this exact JAR remains pending. Restart after replacing the previous Iris JAR; keep only one Iris JAR installed. "
        "See docs/ALPHA14.md for scope and validation limits."
        if alpha and alpha[1] == "14" else
        "Alpha13 is a test candidate that omits color targets declared only by unused simple samplers, "
        "while preserving compiler interfaces and independent output/storage/mipmap/flip requirements. "
        "The compiled allocation replay omits56.25MiB of nominal target payload in Complementary High at1440p; Ultra retains all targets. "
        "It inherits Alpha11/12 changes. Actual game validation is pending, no extra FPS gain is claimed, and Alpha10 remains installed. "
        "See docs/ALPHA13.md for exact artifact hashes and evidence limits."
        if alpha and alpha[1] == "13" else
        "Alpha12 is a test candidate that shares the identical frame-start depth clear, retaining independent later updates. "
        "It inherits Alpha11's compute changes. The full build, compiled ownership checks and independent headless GPU readback pass. "
        "The isolated clear operation saved about0.007ms on the tested RTX5070; no additional whole-game FPS gain is claimed. "
        "Minecraft validation remains pending and Alpha10 remains installed. See docs/ALPHA12.md for exact scope and hashes."
        if alpha and alpha[1] == "12" else
        "Alpha11 is a test candidate targeting compute uniform/descriptor allocation overhead. "
        "CPU contracts and independent headless Vulkan readback/timing checks pass; the full Ultra game comparison is pending. "
        "Its transient slices and capability-gated push descriptors retain allocated fallback behavior and storage ordering. "
        "The isolated host-binding savings are not a Minecraft FPS result. See docs/ALPHA11.md for exact scope and hashes."
        if alpha and alpha[1] == "11" else
        "Alpha10 completed a controlled Minecraft26.3 Complementary High comparison at2560x1440 on RTX5070. "
        "It adds direct cached-uniform serialization, a more precise feedback-copy plan and shared opaque depth with independent later updates. "
        "The matched test measured12.8% shorter mean frame intervals (about226 to260FPS at a260FPS cap), with12.9% less GPU world time. "
        "This is one fixed scene/process pair, not a universal gain or proof that all stutters are fixed. "
        "Consult docs/ALPHA10.md for exact hashes, completed checks and coverage limits. The reported glass flicker remains unresolved."
        if alpha and alpha[1] == "10" else
        "Alpha9 reduces repeated native renderer work without changing shader quality settings. "
        "It caches custom-uniform dependency order and immutable descriptor metadata, avoids temporary uniform writer objects, "
        "and skips color-buffer snapshots that no world program can need for read/write feedback. "
        "Consult docs/ALPHA9.md for exact benchmark inputs, validation and limits. Camera-dependent glass flicker reported on Alpha8 remains under investigation. "
        "Minecraft 26.3, Java25, Fabric Loader0.19.5+ and Sodium0.9.2+mc26.3 remain required."
        if alpha and alpha[1] == "9" else
        "Alpha8 ports this unofficial native Vulkan shader renderer to Minecraft 26.3 / RenderPearl and Sodium 0.9.2+mc26.3. "
        "It preserves shader resources, terrain and entity rendering, and restores the legacy ArmorGlint pass for older packs. "
        "Consult docs/ALPHA8.md for exact binary hashes, tested profiles and limitations. Many mods from 26.2 still need their own updates; do not force their version requirements. "
        "On the tested Windows system, native startup required -Xss4m -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysPreTouchStacks. This is a machine-specific observed workaround, not a universal requirement."
        if alpha and alpha[1] == "8" else
        "Alpha7 targets black nametags and falling-block surfaces on native Vulkan. "
        "It preserves the pack's name-tag material, supplies geometry normals for native block/text vertices, "
        "and suppresses vanilla entity shadow quads when the pack supplies shadow maps. "
        "Consult the alpha7 record for the exact validation results and remaining limitations."
        if alpha and alpha[1] == "7" else
        "Alpha6 supports world shader packs such as Mineek with no post-processing or shadow programs. "
        "When no final shader is present, it presents the current colortex0 even if deferred passes already ran earlier in the frame. "
        "This adds no synthetic pack shader or gamma transform. The direct copy requires matching format and dimensions, as in Mineek's default RGBA8 target. "
        "Consult the alpha6 record for completed CPU checks and the Mineek/Complementary High GPU validation status."
        if alpha and alpha[1] == "6" else
        "Alpha5 targets the missing iris_Color compilation failure when an End Portal becomes active. "
        "The existing portal/gateway hooks now provide colored, full-bright entity vertices, animated UVs, normals and the portal texture on Vulkan. "
        "The shader adapter supplies white vertex color only when that input is absent; supplied vertex colors remain unchanged. "
        "Consult the alpha5 record for the actual twelfth-eye activation, gateway, removal/recreation and High/Ultra reload validation status."
        if alpha and alpha[1] == "5" else
        "Alpha4 removes the redundant Sodium region-manager redirect that fails on OpenGL with Sodium 0.9.2. "
        "The uploadResults overload still exists; its clearAllCachedBatches invocation was removed in 0.9.2. "
        "Retained region HEAD hooks preserve regular/shadow batch invalidation on both supported Sodium versions. "
        "The supplied report does not identify the user's selected graphics API, and alpha4 makes no backend-selection change. "
        "Final runtime verification belongs to this exact artifact; Mac hardware still needs a retest."
        if alpha and alpha[1] == "4" else
        "Alpha3 limits Iris uniform/resource binding to the exact compiled pipelines created by Iris. "
        "It targets the reported missing-Globals exception during vanilla animated-atlas startup, while keeping strict checks for Iris-owned pipelines. "
        "The later OpenGL reset/native abort in the supplied Mac log was a secondary recovery failure. "
        "Disable Not Enough Crashes for the Vulkan retest; this fork does not change that mod's OpenGL recovery path. "
        "Apple M2 Pro/MoltenVK still needs a new hardware retest; that startup log did not reach the separate shadow push-constant case."
        if alpha and alpha[1] == "3" else
        "Earlier development coverage includes Sildur's Vibrant v2.01 High and Complementary Unbound r5.8.1 High/Ultra on an RTX 5070. "
        "Alpha2 introduced r5.9.1 puddle-sampler routing, held-item glint corrections, Sodium 0.9.2 arena support and a shadow push-constant correction. "
        "Consult the version-specific record for completed checks and remaining platform limitations."
    )
    return f"""# {metadata['name']} {metadata['version']}

Unofficial experimental fork. This folder has not been published anywhere.

1. Create a Minecraft {deps.get('minecraft', '26.2')} Fabric client profile using Java {deps.get('java', '>=25')} and Fabric Loader {deps.get('fabricloader', 'see fabric.mod.json')}.
2. Install exactly one Sodium build allowed by this JAR: `{json.dumps(deps.get('sodium'))}`. These are alternatives, not a list to install together; follow this artifact's manifest.
3. Put `{runtime_name}` in that profile's `mods` folder. Remove any other Iris JAR first; this fork also uses mod ID `iris`.
4. Runtime Fabric modules are nested in the JAR. Do not install the separate probe mod or use developer JVM flags.
5. Select Vulkan in Minecraft's renderer settings and restart as requested. Download a shader pack separately into `shaderpacks`, then select it through the shader menu. Start with High before trying Ultra.

{release_context}

See `{release_doc}` inside the paired source bundle for this version's completed and pending tests. Validation applies to the exact documented JAR hash. Windows results from earlier alpha2 artifacts do not automatically validate a newer alpha or a different GPU.

Transient uniform uploads reduce repeated dedicated-buffer allocation, and pipeline warmup moves common compilation work before first use. Performance results apply only to the exact build, inputs and hardware documented in the version-specific evidence; they do not establish a universal FPS gain or uniformly reduced stutter. Full pack/device compatibility and OpenGL visual parity remain incomplete. The approximately 1.96 GiB storage figure applies specifically to r5.8.1 Ultra before other game allocations; newer profiles differ.

If a pack prevents startup, close the game, set `enableShaders=false` in `config/iris.properties`, and restart. Native failures do not yet guarantee automatic fallback. Keep a separate test profile and preserve your existing configuration/worlds.

## Source and licensing

`{source_name}` contains this exact source snapshot, build scripts, tests, licenses, the LGPL-licensed compile-only DH API with embedded sources, and bundled dependency source material. It excludes local worlds, logs, shader-pack ZIPs and runtime directories. Consult `docs/RELEASING.md` and `licenses/THIRD-PARTY-NOTICES.md` inside it.

Extract it, use JDK 25, and run `./gradlew :fabric:build -Pbuild.release=true` for a release-version build. In PowerShell use `.\\gradlew.bat :fabric:build '-Pbuild.release=true'`; omit the release property for a snapshot. Gradle downloads separately installed build tools/Minecraft and declared dependencies from their configured repositories. This is not an offline toolchain bundle. See `third-party-source/SOURCES.json` for exact dependency sources and hashes; upstream ZIPs include grammar/build inputs beyond Maven source JARs.

Source and binary belong together when sharing this folder. Preserve license/attribution files and provide equivalent source access beside any future download. The metadata license list identifies components, not a choice to ignore bundled AGPL obligations. No public source/support URL or upstream endorsement is implied.
""".encode()


def package(args):
    root, jar = args.root.resolve(), args.jar.resolve()
    jar_data = jar.read_bytes()
    metadata, nested = inspect_runtime(jar_data)
    version = metadata["version"]
    if not re.fullmatch(r"[A-Za-z0-9_.+\-]+", version):
        raise ValueError("Version cannot be used as a safe release filename")
    output = args.output.resolve() if args.output else root / "build" / "release" / version
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"Release destination must be empty: {output}")
    files = source_files(root)
    prerequisites = [validate_dh(files["DHApi.jar"])]
    extra, provenance = dependency_sources(nested, root, args.download_cache.resolve(), args.gradle_cache.resolve(), args.offline)
    files.update(extra)
    files["third-party-source/SOURCES.json"] = json_bytes({"sources": provenance, "compilePrerequisites": prerequisites})
    file_inventory = [{"path": name, "sha256": sha(data), "bytes": len(data)} for name, data in sorted(files.items())]
    source_digest = sha(json_bytes(file_inventory))
    runtime_name = f"iris-vulkan-experimental-{version}.jar"
    source_name = f"iris-vulkan-experimental-{version}-sources.zip"
    notes = install_notes(metadata, runtime_name, source_name)
    files["RELEASE-SOURCE-MANIFEST.json"] = json_bytes({"version": version, "runtimeSha256": sha(jar_data), "sourceInventorySha256": source_digest, "files": file_inventory})
    files["RELEASE-INSTALL.md"] = notes
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="iris-release-", dir=output.parent) as temp:
        stage = Path(temp)
        (stage / runtime_name).write_bytes(jar_data)
        write_zip(stage / source_name, files, f"iris-vulkan-experimental-{version}")
        (stage / "INSTALL.md").write_bytes(notes)
        manifest = {"version": version, "metadata": metadata, "runtime": runtime_name, "runtimeSha256": sha(jar_data), "source": source_name, "sourceSha256": sha((stage / source_name).read_bytes()), "sourceInventorySha256": source_digest, "sourceFiles": len(files), "nestedDependencies": nested, "compilePrerequisites": prerequisites, "sourceMaterial": provenance, "publication": "not published", "validation": {"probeClassesAbsent": True, "sourceWhitelistApplied": True, "credentialPatternScanPassed": True, "privatePathScanPassed": True, "shaderPacksWorldsLogsExcluded": True, "cleanBuildVerifiedByThisTool": False}}
        (stage / "RELEASE-MANIFEST.json").write_bytes(json_bytes(manifest))
        sums = "".join(f"{sha(path.read_bytes())}  {path.name}\n" for path in sorted(stage.iterdir()))
        (stage / "SHA256SUMS.txt").write_text(sums, encoding="utf-8", newline="\n")
        with zipfile.ZipFile(stage / source_name) as archive:
            if archive.testzip() is not None or len(archive.namelist()) != len(files):
                raise ValueError("Final source archive verification failed")
        output.mkdir(exist_ok=True)
        for path in stage.iterdir():
            shutil.copyfile(path, output / path.name)
    print(json.dumps({"releaseDirectory": str(output), "version": version, "runtimeSha256": sha(jar_data), "sourceFiles": len(files), "dependencySourceFiles": len(provenance), "published": False}, indent=2))


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=root)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--download-cache", type=Path, default=root / "build" / "release-source-downloads")
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--offline", action="store_true")
    package(parser.parse_args())


if __name__ == "__main__":
    main()
