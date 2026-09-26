"""Compile the production graphics bridge against final 26.3 and test real SPIR-V contracts.

Only the storage pipeline's GPU entrypoint is replaced by a test boundary which
throws if invoked. Shaderc and SPIRV-Cross are the real Minecraft dependencies.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jdk", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = root / "build/renderpearl-contract"
    output.mkdir(parents=True, exist_ok=True)
    url = "https://piston-meta.mojang.com/v1/packages/96c00d95a31328714d3811cfade2804bb050e455/26.3.json"
    metadata_file = output / "minecraft-26.3.json"
    if not metadata_file.exists():
        metadata_file.write_bytes(urllib.request.urlopen(url).read())
    data = metadata_file.read_bytes()
    assert hashlib.sha1(data).hexdigest() == "96c00d95a31328714d3811cfade2804bb050e455"
    metadata = json.loads(data)
    gradle = Path.home() / ".gradle/caches"

    def artifact(info, candidates):
        for path in candidates:
            if path.is_file() and hashlib.sha1(path.read_bytes()).hexdigest() == info["sha1"]:
                return path
        path = output / "libraries" / info.get("path", "minecraft-26.3-client.jar")
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_bytes(urllib.request.urlopen(info["url"]).read())
        assert hashlib.sha1(path.read_bytes()).hexdigest() == info["sha1"], path
        return path

    client = artifact(metadata["downloads"]["client"], [gradle / "fabric-loom/26.3/minecraft-client.jar"])
    classpath = [output, client]
    for library in metadata["libraries"]:
        rules = library.get("rules", [])
        if rules and not any(rule.get("action") == "allow" and rule.get("os", {}).get("name", "windows") == "windows" for rule in rules):
            continue
        info = library["downloads"]["artifact"]
        group, name, version, *_ = library["name"].split(":")
        directory = gradle / "modules-2/files-2.1" / group / name / version
        classpath.append(artifact(info, directory.glob("*/" + Path(info["path"]).name)))
    classpath_string = ";".join(str(path) for path in classpath)
    sources = [root / name for name in (
        "common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanGraphicsCompiler.java",
        "common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanStorageReflection.java",
        "common/src/main/java/net/irisshaders/iris/vulkan/IrisVulkanDeviceFeatures.java",
        "tests/renderpearl/IrisVulkanStoragePipeline.java",
        "tests/renderpearl/GraphicsCompilerContract.java",
    )]

    def invoke(executable, name, arguments):
        argfile = output / (name + ".args")
        argfile.write_text("\n".join('"' + str(value).replace("\\", "/") + '"' for value in arguments), encoding="utf-8")
        result = subprocess.run([str(args.jdk / "bin" / executable), "@" + str(argfile)], text=True, capture_output=True)
        (output / (name + ".txt")).write_text(result.stdout + result.stderr, encoding="utf-8")
        print(result.stdout + result.stderr, end="")
        if result.returncode:
            raise SystemExit(result.returncode)

    invoke("javac.exe", "compile", ["-proc:none", "-encoding", "UTF-8", "-classpath", classpath_string, "-d", output, *sources])
    invoke("java.exe", "result", ["--enable-native-access=ALL-UNNAMED", "-classpath", classpath_string,
                                  "net.irisshaders.iris.vulkan.GraphicsCompilerContract"])


if __name__ == "__main__":
    main()
