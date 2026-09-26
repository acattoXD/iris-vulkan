"""Prepare packaged alpha validation with a separate probe mod; never launches Minecraft."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import zipfile

import packaged_run


def profile_names(prefix="alpha2"):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", prefix):
        raise ValueError("Profile prefix must be a single safe filename component")
    return prefix + "-r591-enchanted-final", prefix + "-makeup-medium-final"


def profile_options(pack: Path, name: str) -> str:
    with zipfile.ZipFile(pack) as archive:
        properties = archive.read("shaders/shaders.properties").decode("utf-8").replace("\\\n", "")
    match = re.search(r"(?m)^\s*profile\." + re.escape(name) + r"\s*=\s*(.+)$", properties)
    if not match:
        raise ValueError(f"Missing exact profile {name}: {pack}")
    result = {}
    for token in match[1].split():
        if token.startswith("profile."):
            raise ValueError("Inherited profile requires explicit expansion")
        if "=" in token:
            key, value = token.split("=", 1)
        else:
            key, value = token.removeprefix("!"), str(not token.startswith("!")).lower()
        result[key] = value
    return "".join(f"{key}={value}\n" for key, value in sorted(result.items()))


def expected_loaded_build(root: Path, iris: Path, probe: Path):
    source = (root / "fabric/src/probe/java/net/irisshaders/iris/probe/NativeProbe.java").read_text()
    method = source.split("private static void recordLoadedBuild(Path run)", 1)[1].split("String resource =", 1)[0]
    names = re.findall(r'"(net\.irisshaders\.iris\.[^"]+)"', method)
    if not names:
        raise ValueError("NativeProbe loaded-build inventory could not be found")
    records = {}
    with zipfile.ZipFile(iris) as production, zipfile.ZipFile(probe) as diagnostic:
        for name in names:
            path = name.replace(".", "/") + ".class"
            owner, archive = (probe, diagnostic) if ".probe." in name else (iris, production)
            if path not in archive.namelist():
                raise ValueError(f"recordLoadedBuild requires missing class {path} in {owner}")
            records[name] = {"jar": str(owner), "resource": path,
                             "sha256": packaged_run.hashlib.sha256(archive.read(path)).hexdigest()}
    return records


def prepare_profile(args, name, pack, profile, options, enchanted):
    root = Path(__file__).resolve().parents[2]
    run = args.run_root.resolve() / name
    backend = getattr(args, "backend", "vulkan")
    if backend not in ("vulkan", "opengl") or (enchanted and backend != "vulkan"):
        raise ValueError("Invalid backend or Vulkan-only enchanted probe requested on another backend")
    # Keep the normal release preparer's production JAR/classpath checks intact.
    packaged_run.prepare(argparse.Namespace(
        cache=args.cache, run_dir=run, jar=args.jar, jdk=args.jdk, sodium_jar=args.sodium_jar,
        sodium=None, report=run / "production-closure-report.json", shader_pack=pack,
        ultra=False, backend=backend, world_source=None))
    probe = run / "mods" / args.probe_jar.name
    shutil.copy2(args.probe_jar, probe)
    properties = run / "config/iris.properties"
    properties.write_text(properties.read_text() + "vulkanWarningVersion=1\n", encoding="utf-8")
    sidecar = run / "shaderpacks" / (pack.name + ".txt")
    sidecar.write_text(options, encoding="utf-8")
    flags = [f"-Diris.vulkan.probe.runDir={run.as_posix()}", f"-Diris.vulkan.probe.backend={backend}",
             "-Diris.vulkan.probe.world=true", "-Diris.vulkan.probe.scenarios=true",
             f"-Diris.vulkan.probe.expectedProfile={profile}"]
    if enchanted:
        flags.append("-Diris.vulkan.probe.enchanted=true")
    launch = run / "launch.args"
    launch.write_text("".join('"' + flag + '"\n' for flag in flags) + launch.read_text(), encoding="utf-8")
    actual_flags = re.findall(r'"(-D[^"\n]+)"', launch.read_text())
    if any(not flag.startswith("-Diris.vulkan.probe.") for flag in actual_flags):
        raise ValueError("Only diagnostic iris.vulkan.probe.* flags are permitted")
    if "--quickPlaySingleplayer" in launch.read_text():
        raise ValueError("Generic NativeProbe must create its own world at the title screen")
    manifest_path = run / "packaged-run-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    installed_iris = Path(manifest["irisJar"])
    manifest.update({
        "probeModInstalled": True,
        "probeJar": str(probe), "probeJarSha256": packaged_run.digest(probe),
        "diagnosticFlags": flags, "productionDevelopmentFlags": [],
        "warningAcknowledgment": {"explicitTestOnly": True, "config": "vulkanWarningVersion=1"},
        "shaderPack": str(pack.resolve()), "shaderPackSha256": packaged_run.digest(pack),
        "expectedProfile": profile, "profileSidecar": str(sidecar), "profileSidecarSha256": packaged_run.digest(sidecar),
        "loadedBuildExpected": expected_loaded_build(root, installed_iris, probe),
        "scope": "Packaged production Iris and Sodium on official Minecraft/Fabric libraries, with a separately installed diagnostic probe. This run is not probe-free; production backend defaults receive no overrides.",
    })
    packaged_run.write_json(manifest_path, manifest)
    packaged_run.write_json(run / "diagnostic-preparation-report.json", {
        "prepared": True, "launched": False, "productionClosurePassed": manifest["passed"],
        "probeInstalledSeparately": True, "probeSha256": manifest["probeJarSha256"],
        "irisSha256": manifest["irisJarSha256"], "expectedProfile": profile,
        "loadedBuildClassResourcesChecked": len(manifest["loadedBuildExpected"]),
        "onlyDiagnosticJvmFlags": True, "worldSourceCopied": False,
        "scope": manifest["scope"],
    })
    print(json.dumps({"diagnosticPrepared": str(run), "expectedProfile": profile,
                      "probeSha256": manifest["probeJarSha256"], "launched": False}))


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--probe-jar", type=Path, default=root / "fabric/build/probe/iris-native-probe.jar")
    parser.add_argument("--sodium-jar", type=Path, default=root / "build/sodium-0.9.2-audit/sodium-fabric-0.9.2+mc26.2.jar")
    parser.add_argument("--run-root", type=Path, default=root / "build/release-validation")
    parser.add_argument("--name-prefix", default="alpha2", help="Run-directory prefix; defaults to alpha2 for earlier-run reproducibility")
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    parser.add_argument("--complementary-pack", type=Path, required=True)
    parser.add_argument("--makeup-pack", type=Path, required=True)
    parser.add_argument("--r591-options", type=Path, default=root / "build/r591-profile/ULTRA.options")
    args = parser.parse_args()
    r591_name, makeup_name = profile_names(args.name_prefix)
    for file in (args.jar, args.probe_jar, args.sodium_jar, args.complementary_pack, args.makeup_pack, args.r591_options):
        if not file.is_file():
            raise FileNotFoundError(file)
    with zipfile.ZipFile(args.sodium_jar) as archive:
        if json.loads(archive.read("fabric.mod.json"))["version"] != "0.9.2+mc26.2":
            raise ValueError("These final profiles require actual Sodium 0.9.2+mc26.2")
    # Refuse both targets up front rather than leaving one prepared on a repeat invocation.
    for name in (r591_name, makeup_name):
        if (args.run_root / name).exists():
            raise FileExistsError(args.run_root / name)
    expected = profile_options(args.complementary_pack, "ULTRA")
    supplied = args.r591_options.read_text(encoding="utf-8")
    if supplied.strip().splitlines() != expected.strip().splitlines():
        raise ValueError("Saved r5.9.1 sidecar disagrees with the actual pack ULTRA profile")
    prepare_profile(args, r591_name, args.complementary_pack, "ULTRA", supplied, True)
    prepare_profile(args, makeup_name, args.makeup_pack, "medium", profile_options(args.makeup_pack, "medium"), False)


if __name__ == "__main__":
    main()
