"""Prepare a fresh visible named-mob/falling-block diagnostic; never launch Minecraft."""
import argparse
import json
from pathlib import Path
import shutil
import zipfile

import packaged_run
from prepare_probe_profiles import expected_loaded_build, profile_options


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--probe-jar", type=Path, default=root / "fabric/build/probe/iris-native-probe.jar")
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--shader-pack", type=Path, required=True)
    parser.add_argument("--profile", help="Exact pack profile, such as HIGH; omit for a pack without profiles")
    parser.add_argument("--sodium-jar", type=Path, default=root / "build/sodium-0.9.2-audit/sodium-fabric-0.9.2+mc26.2.jar")
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    args = parser.parse_args()
    run = args.run_dir.resolve()
    if "entity-faces" not in run.name:
        raise ValueError("Fresh run-directory name must contain entity-faces")
    for path in (args.jar, args.probe_jar, args.shader_pack, args.sodium_jar):
        if not path.is_file():
            raise FileNotFoundError(path)
    with zipfile.ZipFile(args.probe_jar) as archive:
        for entry in ("net/irisshaders/iris/probe/NativeEntityFacesProbe.class",
                      "net/irisshaders/iris/probe/mixin/ProbeEntityFacesDrawMixin.class"):
            if entry not in archive.namelist():
                raise ValueError("Rebuild the separate probe JAR; missing " + entry)
    options = profile_options(args.shader_pack, args.profile) if args.profile else None
    flags = ["-Diris.vulkan.probe.world=true", "-Diris.vulkan.probe.entityFaces=true",
             "-Diris.vulkan.probe.backend=vulkan", "-Diris.vulkan.probe.runDir=" + run.as_posix()]
    if args.profile:
        flags.append("-Diris.vulkan.probe.expectedProfile=" + args.profile)
    if any('"' in flag or "\n" in flag or "\r" in flag for flag in flags):
        raise ValueError("Invalid diagnostic launch argument")
    args.backend, args.ultra, args.world_source, args.sodium = "vulkan", False, None, None
    args.report = run / "production-closure-report.json"
    packaged_run.prepare(args)
    probe = run / "mods/iris-native-probe.jar"
    shutil.copy2(args.probe_jar, probe)
    config = run / "config/iris.properties"
    config.write_text(config.read_text() + "vulkanWarningVersion=1\n", encoding="utf-8")
    if options is not None:
        (run / "shaderpacks" / (args.shader_pack.name + ".txt")).write_text(options, encoding="utf-8")
    launch = run / "launch.args"
    launch.write_text("".join('"' + flag + '"\n' for flag in flags) + launch.read_text(), encoding="utf-8")
    manifest_path = run / "packaged-run-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest.update({"probeModInstalled": True, "probeJar": str(probe), "probeJarSha256": packaged_run.digest(probe),
                     "diagnosticFlags": flags, "productionDevelopmentFlags": [], "worldSourceCopied": False,
                     "warningAcknowledgment": {"explicitTestOnly": True, "config": "vulkanWarningVersion=1"},
                     "shaderPackSha256": packaged_run.digest(args.shader_pack), "expectedProfile": args.profile,
                     "loadedBuildExpected": expected_loaded_build(root, Path(manifest["irisJar"]), probe),
                     "scope": "Visible packaged Vulkan test with a separate probe and a newly generated disposable world; no user-world copy and no production development flags. Draw counters require screenshot review."})
    packaged_run.write_json(manifest_path, manifest)
    print(json.dumps({"preparedOnly": True, "launch": str(run / "launch.ps1"),
                      "irisSha256": manifest["irisJarSha256"], "probeSha256": manifest["probeJarSha256"]}))


if __name__ == "__main__":
    main()
