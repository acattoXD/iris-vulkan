"""Prepare a menu-only, real-GPU vanilla sprite animation regression; never launch Minecraft."""
import argparse
import json
from pathlib import Path
import shutil
import zipfile

import packaged_run


def record_diagnostic_manifest(run, inputs, corrected_after_launch=False):
    path = run / "packaged-run-manifest.json"
    manifest = json.loads(path.read_text())
    probe = run / "mods/iris-native-probe.jar"
    with zipfile.ZipFile(probe) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
    manifest["probeModInstalled"] = True
    manifest["probeJarSha256"] = packaged_run.digest(probe)
    manifest["diagnosticProbe"] = {"path": str(probe), "id": metadata["id"], "version": metadata["version"],
                                   "sha256": manifest["probeJarSha256"]}
    manifest["diagnosticFlags"] = inputs["testControllerSwitches"]
    manifest["productionRenderingFlags"] = inputs["productionRenderingFlags"]
    manifest["diagnosticTestOnly"] = True
    manifest["scope"] = inputs["scope"] + "; this is not a probe-free normal-install validation"
    if corrected_after_launch:
        manifest["metadataCorrection"] = "The diagnostic probe and test flags were present during this run; this amendment corrects preparation metadata only. Runtime results are unchanged."
    packaged_run.write_json(path, manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--probe-jar", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    parser.add_argument("--sodium", default="net.caffeinemc:sodium-fabric:0.9.1-beta.3+mc26.2")
    parser.add_argument("--sodium-jar", type=Path)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    if "atlas-smoke" not in run.name:
        raise ValueError("Fresh run directory name must contain atlas-smoke")
    with zipfile.ZipFile(args.probe_jar) as jar:
        if "net/irisshaders/iris/probe/NativeAtlasAnimationProbe.class" not in jar.namelist():
            raise ValueError("Test-only probe JAR does not contain the atlas GPU fixture")
    args.backend, args.shader_pack, args.ultra, args.world_source = "vulkan", None, False, None
    args.report = run.parent / (run.name + "-closure.json")
    packaged_run.prepare(args)
    shutil.copy2(args.probe_jar, run / "mods/iris-native-probe.jar")
    # Disclosure is acknowledged only for this disposable diagnostic profile.
    (run / "config/iris.properties").write_text("enableShaders=false\nvulkanWarningVersion=1\ndisableUpdateMessage=true\n")
    switches = ["-Diris.atlasSmoke=true", "-Diris.atlasSmoke.runDir=" + run.as_posix()]
    if any('"' in value or "\n" in value or "\r" in value for value in switches):
        raise ValueError("Invalid run-directory argument")
    launch = run / "launch.args"
    launch.write_text("\n".join('"' + value + '"' for value in switches) + "\n" + launch.read_text())
    inputs = {
        "irisJarSha256": packaged_run.digest(args.jar), "probeJarSha256": packaged_run.digest(args.probe_jar),
        "scope": "Actual vanilla sprite BLIT and INTERPOLATE with null Globals and fenced pixel readback; separate diagnostic mod, no world or shader pack",
        "productionRenderingFlags": [], "testControllerSwitches": switches,
        "disclosure": "Explicitly acknowledged in this disposable test profile",
    }
    packaged_run.write_json(run / "atlas-smoke-input.json", inputs)
    record_diagnostic_manifest(run, inputs)
    print("Prepared only: " + str(run / "launch.ps1"))


if __name__ == "__main__":
    main()
