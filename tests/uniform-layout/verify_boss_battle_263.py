"""Compile and run the CPU-only 26.3 bossBattle uniform regression."""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-json", type=Path, required=True)
    parser.add_argument("--iris", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    port = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Use a fresh output directory to retain earlier evidence")
    output.mkdir(parents=True)

    sources = [
        port / "common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanUniformSnapshot.java",
        Path(__file__).with_name("IrisVulkanBossBattleUniformTest.java"),
    ]
    libraries = [Path(path) for path in json.loads(args.classpath_json.read_text())]
    # The source under test must provide the snapshot and its current Iris
    # dependencies; discard stale Iris/mod jars from a game launch classpath.
    libraries = [path for path in libraries if not path.name.lower().startswith("iris-")]
    classpath = ";".join(str(path.resolve()) for path in [output, args.iris.resolve(), *libraries])

    compile_result = subprocess.run(
        [str(args.jdk / "bin/javac.exe"), "-proc:none", "-cp", classpath, "-d", str(output), *(str(path) for path in sources)],
        capture_output=True,
        text=True,
    )
    (output / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
    if compile_result.returncode:
        raise RuntimeError(compile_result.stdout + compile_result.stderr)

    run_result = subprocess.run(
        [str(args.jdk / "bin/java.exe"), "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
         "net.irisshaders.iris.vulkan.IrisVulkanBossBattleUniformTest"],
        capture_output=True,
        text=True,
    )
    (output / "result.txt").write_text(run_result.stdout + run_result.stderr)
    (output / "inputs.json").write_text(json.dumps({
        "sources": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        "irisSha256": hashlib.sha256(args.iris.read_bytes()).hexdigest(),
        "passed": run_result.returncode == 0,
        "scope": "Actual 26.3 BossHealthOverlay event map extraction and OptiFine bossBattle key mapping; no game/window/GPU claim",
    }, indent=2) + "\n")
    print(run_result.stdout, end="")
    if run_result.returncode:
        raise RuntimeError(run_result.stderr)


if __name__ == "__main__":
    main()
