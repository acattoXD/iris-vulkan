"""Prepare a new isolated packaged-JAR benchmark profile; never launches a game."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

import packaged_run


# Minecraft 26.2 has MINIMIZED and AFK, not an OFF enum. MINIMIZED disables
# inactivity throttling while retaining the engine's minimized-window limit.
BENCHMARK_OPTION_OVERRIDES = {
    "maxFps": "260", "enableVsync": "false", "renderDistance": "4", "simulationDistance": "4",
    "fov": "0.0", "bobView": "true", "inactivityFpsLimit": '"minimized"',
}


def tree_manifest(root: Path):
    files = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("Benchmark input world must not contain symlinks")
        if path.is_file() and path.name != "session.lock":
            files.append({"path": path.relative_to(root).as_posix(), "sha256": packaged_run.digest(path), "bytes": path.stat().st_size})
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"sha256": hashlib.sha256(encoded).hexdigest(), "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--probe-jar", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--world-source", type=Path, required=True, help="Explicit closed fixture world, copied without modifying it")
    parser.add_argument("--shader-pack", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    parser.add_argument("--sodium", default="net.caffeinemc:sodium-fabric:0.9.1-beta.3+mc26.2")
    parser.add_argument("--sodium-jar", type=Path)
    parser.add_argument("--warmup-seconds", type=float, default=15)
    parser.add_argument("--measure-seconds", type=float, default=20)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    if "benchmark" not in run.name:
        raise ValueError("Isolated run directory name must contain benchmark")
    if not (0.5 <= args.warmup_seconds <= 120 and 1 <= args.measure_seconds <= 120 and 1 <= args.repetitions <= 10):
        raise ValueError("Invalid bounded benchmark durations")
    with zipfile.ZipFile(args.probe_jar) as probe:
        if "net/irisshaders/iris/probe/NativeBenchmarkProbe.class" not in probe.namelist():
            raise ValueError("Probe JAR does not contain the benchmark controller")
        if b"limiterFailure" not in probe.read("net/irisshaders/iris/probe/NativeBenchmarkProbe.class"):
            raise ValueError("Probe JAR predates live AFK-limiter verification; rebuild it once before preparing matched future runs")
    before = tree_manifest(args.world_source.resolve())
    args.backend = "vulkan"
    args.ultra = True
    args.report = run.parent / (run.name + "-closure.json")
    packaged_run.prepare(args)
    after = tree_manifest(args.world_source.resolve())
    if after != before:
        raise ValueError("Input world changed while copying; use a closed world")
    copied_world = run / "saves" / args.world_source.name
    copied = tree_manifest(copied_world)
    if copied != before:
        raise ValueError("Copied world does not match input snapshot")
    shutil.copy2(args.probe_jar, run / "mods" / "iris-native-probe.jar")
    options = (run / "options.txt").read_text().splitlines()
    replacements = BENCHMARK_OPTION_OVERRIDES
    options = [line for line in options if line.split(":", 1)[0] not in replacements]
    (run / "options.txt").write_text("\n".join(options + [key + ":" + value for key, value in replacements.items()]) + "\n")
    config = run / "config/iris.properties"
    config.write_text(config.read_text() + "vulkanWarningVersion=1\n")
    switches = ["-Diris.benchmark=true", f"-Diris.benchmark.runDir={run.as_posix()}", f"-Diris.benchmark.label={args.label}",
                f"-Diris.benchmark.warmupSeconds={args.warmup_seconds}", f"-Diris.benchmark.measureSeconds={args.measure_seconds}",
                f"-Diris.benchmark.repetitions={args.repetitions}"]
    if any('"' in item or "\n" in item or "\r" in item for item in switches):
        raise ValueError("Benchmark label/path cannot contain argument delimiters")
    launch = run / "launch.args"
    original = launch.read_text()
    # Class-load logging is useful for install tests but adds I/O to measured frames.
    original = "\n".join(line for line in original.splitlines() if "-Xlog:class+load" not in line) + "\n"
    if any(flag in original for flag in ("-Diris.vulkan.", "fabric.development", "dev-launch-injector")):
        raise ValueError("Production rendering/development flags must not enter benchmark launch")
    launch.write_text("\n".join('"' + item + '"' for item in switches) + "\n" + original)
    sidecar = run / "shaderpacks" / (args.shader_pack.name + ".txt")
    manifest = {
        "label": args.label, "irisJar": args.jar.name, "irisJarSha256": packaged_run.digest(args.jar),
        "probeJarSha256": packaged_run.digest(args.probe_jar), "shaderPack": args.shader_pack.name,
        "shaderPackSha256": packaged_run.digest(args.shader_pack), "shaderOptions": sidecar.read_text(),
        "shaderOptionsSha256": packaged_run.digest(sidecar), "world": copied_world.name, "worldSnapshot": before,
        "settings": {"resolution": [1280, 720], "renderDistance": 4, "simulationDistance": 4, "fov": 70, "maxFps": 260, "vsync": False,
                     "inactivityFpsLimit": "minimized",
                     "player": [0, 64, 15], "yaw": 180, "pitch": 17, "time": 4000, "weather": "clear"},
        "timing": {"warmupSeconds": args.warmup_seconds, "measurementSeconds": args.measure_seconds, "repetitions": args.repetitions},
        "productionDevelopmentFlags": [], "testControllerSwitches": switches,
        "disclosureAcknowledgment": "This isolated benchmark explicitly acknowledges the experimental Vulkan warning",
        "scope": "Same fixed scene and inputs for baseline/optimized comparison; no benchmark result is claimed by preparation",
    }
    packaged_run.write_json(run / "benchmark-input.json", manifest)
    print(json.dumps({"preparedBenchmark": str(run), "launch": str(run / "launch.ps1"), "worldSha256": before["sha256"], "productionFlags": []}, indent=2))


if __name__ == "__main__":
    main()
