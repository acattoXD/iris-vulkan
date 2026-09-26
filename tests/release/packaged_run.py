"""Prepare/audit a normal Fabric launch from cached official artifacts. Never launches a game."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import struct
from urllib.parse import unquote, urlsplit
import zipfile


def digest(path: Path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def class_references(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class magic")
    count = struct.unpack_from(">H", data, 8)[0]
    utf8, classes = {}, []
    offset, index = 10, 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            utf8[index] = data[offset:offset + size].decode("utf-8", errors="replace")
            offset += size
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (7, 8, 16, 19, 20):
            if tag == 7:
                classes.append(struct.unpack_from(">H", data, offset)[0])
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    return {utf8[index].lstrip("[").removeprefix("L").removesuffix(";") for index in classes}


def audit_archives(paths):
    errors, archives, classes, references, modules = [], [], {}, {}, {}

    def inspect(data, label, iris_artifact=False):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = set(archive.namelist())
            if len(names) != len(archive.namelist()):
                errors.append(f"Duplicate ZIP entries: {label}")
            if iris_artifact and any("/probe/" in name or name == "iris-native-probe.mixins.json" for name in names):
                errors.append(f"Disposable probe code is inside the shipped artifact: {label}")
            meta = json.loads(archive.read("fabric.mod.json")) if "fabric.mod.json" in names else {}
            archives.append({"source": label, "sha256": hashlib.sha256(data).hexdigest(), "modId": meta.get("id"), "version": meta.get("version")})
            if meta.get("id"):
                previous = modules.setdefault(meta["id"], meta)
                if previous.get("version") != meta.get("version"):
                    errors.append(f"Conflicting bundled versions of {meta['id']}")
            for name in names:
                if name.endswith(".class") and not name.startswith("META-INF/"):
                    binary = name[:-6]
                    classes[binary] = label
                    if binary.startswith("net/irisshaders/"):
                        references[binary] = class_references(archive.read(name))
            def need(name):
                if name not in names:
                    errors.append(f"Missing declared resource {name} in {label}")
            for key in ("accessWidener", "icon"):
                if isinstance(meta.get(key), str):
                    need(meta[key])
            for entry in meta.get("mixins", []):
                config = entry if isinstance(entry, str) else entry["config"]
                need(config)
                if config not in names:
                    continue
                mixin = json.loads(archive.read(config))
                for group in ("mixins", "client", "server"):
                    for name in mixin.get(group, []):
                        need((mixin.get("package", "") + "." + name).replace(".", "/") + ".class")
                if mixin.get("plugin"):
                    need(mixin["plugin"].replace(".", "/") + ".class")
            for entries in meta.get("entrypoints", {}).values():
                for entry in entries:
                    name = entry if isinstance(entry, str) else entry.get("value", "")
                    need(name.split("::")[0].replace(".", "/") + ".class")
            for entry in meta.get("jars", []):
                need(entry["file"])
                if entry["file"] in names:
                    inspect(archive.read(entry["file"]), label + "!/" + entry["file"], iris_artifact)

    for path in paths:
        inspect(path.read_bytes(), str(path), "iris" in path.name.lower())
    missing_classes = {}
    for source, targets in references.items():
        for target in targets:
            if target.startswith("net/irisshaders/") and target not in classes:
                missing_classes.setdefault(target, []).append(source)
    for target in missing_classes:
        errors.append(f"Missing Iris-owned class: {target}")
    available = set(modules) | {"minecraft", "java"}
    for meta in modules.values():
        available.update(meta.get("provides", []))
    for mod, meta in modules.items():
        for dependency in meta.get("depends", {}):
            if dependency not in available:
                errors.append(f"Missing mandatory mod {dependency}, required by {mod}")
    # This exact tested Sodium release rejects old Iris versions. Fabric remains
    # the authoritative general version-constraint resolver during the real launch.
    if "iris" in modules and "sodium" in modules:
        version = modules["iris"]["version"]
        numbers = tuple(map(int, re.match(r"(\d+)\.(\d+)\.(\d+)", version).groups()))
        excluded = modules["sodium"].get("breaks", {}).get("iris", "")
        match = re.fullmatch(r"<=\s*(\d+)\.(\d+)\.(\d+)", excluded)
        if match and numbers <= tuple(map(int, match.groups())):
            errors.append(f"Selected Sodium rejects Iris versions {excluded}")
    return {"passed": not errors, "errors": errors, "archives": archives,
            "moduleVersions": {key: value["version"] for key, value in modules.items()},
            "irisClassCount": len(references), "missingIrisClasses": missing_classes,
            "scope": "ZIP/resource/Iris-owned class closure and mandatory mod presence. Fabric resolves general version constraints at runtime."}


def cached(cache, coordinate, expected_sha1=None):
    group, artifact, version, *classifier = coordinate.split(":")
    filename = artifact + "-" + version + ("-" + classifier[0] if classifier else "") + ".jar"
    candidates = sorted((cache / "modules-2/files-2.1" / group / artifact / version).glob("*/" + filename))
    for candidate in candidates:
        if expected_sha1 is None or digest(candidate, "sha1") == expected_sha1:
            return candidate.resolve()
    raise FileNotFoundError(f"Cached artifact missing or hash mismatch: {coordinate}")


def allowed_windows(library):
    rules = library.get("rules", [])
    allowed = not rules
    for rule in rules:
        os = rule.get("os", {})
        matches = os.get("name", "windows") == "windows" and os.get("arch", "amd64") in ("amd64", "x86_64")
        if matches:
            allowed = rule["action"] == "allow"
    # Mojang's platform list includes all Windows CPU classifiers; this harness
    # targets the user's x64 JDK and must not introduce other OS/CPU natives.
    classifier = library["name"].split(":")[3:]
    return allowed and (not classifier or classifier[0] in ("unsafe", "natives-windows"))


def prepare(args):
    cache, run, iris = args.cache.resolve(), args.run_dir.resolve(), args.jar.resolve()
    if run.exists():
        raise ValueError(f"Refusing to modify an existing run directory: {run}")
    jdk = args.jdk.resolve()
    if not (jdk / "bin/java.exe").is_file():
        raise ValueError("JDK bin/java.exe is required")
    metadata = json.loads((cache / "fabric-loom/26.2/mojang_minecraft_info.json").read_text())
    minecraft = cache / "fabric-loom/26.2/minecraft-client.jar"
    if digest(minecraft, "sha1") != metadata["downloads"]["client"]["sha1"]:
        raise ValueError("Minecraft must be the unmodified official client JAR")
    loader = cached(cache, "net.fabricmc:fabric-loader:0.19.2")
    sodium = args.sodium_jar.resolve() if args.sodium_jar else cached(cache, args.sodium)
    with zipfile.ZipFile(loader) as archive:
        installer = json.loads(archive.read("fabric-installer.json"))
    closure = audit_archives([iris, sodium, loader])
    if not closure["passed"]:
        write_json(args.report.resolve(), closure)
        raise ValueError("Packaged closure failed: " + "; ".join(closure["errors"][:12]))
    classpath = [loader, minecraft]
    for library in metadata["libraries"]:
        if allowed_windows(library):
            classpath.append(cached(cache, library["name"], library["downloads"]["artifact"]["sha1"]))
    for library in installer["libraries"]["common"] + installer["libraries"].get("client", []):
        classpath.append(cached(cache, library["name"], library.get("sha1")))
    classpath = list(dict.fromkeys(classpath))
    assets = cache / "fabric-loom/assets"
    index = assets / "indexes/26.2-32.json"
    if digest(index, "sha1") != metadata["assetIndex"]["sha1"]:
        raise ValueError("Cached asset index hash mismatch")
    objects = json.loads(index.read_text())["objects"]
    missing = [value["hash"] for value in objects.values() if not (assets / "objects" / value["hash"][:2] / value["hash"]).is_file()]
    if missing:
        raise ValueError(f"{len(missing)} cached asset objects are missing")
    for path in classpath:
        if path.suffix != ".jar" or "dev-launch-injector" in str(path) or "minecraft-merged" in str(path):
            raise ValueError(f"Development classpath entry rejected: {path}")
    (run / "mods").mkdir(parents=True)
    (run / "config").mkdir()
    for mod in (iris, sodium):
        shutil.copy2(mod, run / "mods" / mod.name)
    if args.shader_pack:
        pack = args.shader_pack.resolve()
        (run / "shaderpacks").mkdir()
        shutil.copy2(pack, run / "shaderpacks" / pack.name)
        (run / "config/iris.properties").write_text(f"enableShaders=true\nshaderPack={pack.name}\ndisableUpdateMessage=true\n")
        if args.ultra:
            (run / "shaderpacks" / (pack.name + ".txt")).write_text("ANISOTROPIC_FILTER=8\nCLOUD_QUALITY=3\nCOLORED_LIGHTING=512\nDETAIL_QUALITY=3\nLIGHTSHAFT_QUALI_DEFINE=3\nSHADOW_QUALITY=3\nWORLD_SPACE_REFLECTIONS=1\nshadowDistance=256.0\n")
    (run / "options.txt").write_text(f'preferredGraphicsBackend:"{args.backend}"\nrenderDistance:4\nmaxFps:60\nenableVsync:false\nfullscreen:false\nonboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\n')
    jvm = ["-Xmx6G", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
           "-Xlog:class+load=info:file=class-load.log", "-classpath", ";".join(str(path) for path in classpath)]
    game = ["--username", "IrisReleaseCheck", "--version", "26.2", "--gameDir", str(run),
            "--assetsDir", str(assets), "--assetIndex", "26.2-32", "--accessToken", "0",
            "--uuid", "d982b63aab3e341da8150792b69bd499", "--versionType", "release",
            "--graphicsBackend", args.backend.upper(), "--width", "1280", "--height", "720"]
    if args.world_source:
        world = args.world_source.resolve()
        if not (world / "level.dat").is_file():
            raise ValueError("world-source must be an existing closed world's directory")
        shutil.copytree(world, run / "saves" / world.name, ignore=shutil.ignore_patterns("session.lock"))
        game += ["--quickPlaySingleplayer", world.name]
    arguments = jvm + [installer["mainClass"]["client"]] + game
    if any("-Diris." in value or "fabric.development" in value or "fabric.dli" in value for value in arguments):
        raise ValueError("Development flags are forbidden")
    quoted = ['"' + value.replace("\\", "/").replace('"', '\\"') + '"' for value in arguments]
    (run / "launch.args").write_text("\n".join(quoted) + "\n", encoding="utf-8")
    escaped_java = str(jdk / "bin/java.exe").replace("'", "''")
    (run / "launch.ps1").write_text("$ErrorActionPreference = 'Stop'\nSet-Location -LiteralPath $PSScriptRoot\n"
                                    f"& '{escaped_java}' '@launch.args' *> 'launcher.log'\nexit $LASTEXITCODE\n")
    closure.update({"runDirectory": str(run), "irisJar": str(run / "mods" / iris.name), "irisJarSha256": digest(iris),
                    "minecraftSource": "Unmodified official client JAR, pinned to Mojang version metadata SHA-1",
                    "minecraftSha1": digest(minecraft, "sha1"), "developmentFlags": [], "probeModInstalled": False,
                    "assetObjectsPresent": len(objects), "classpath": [{"path": str(path), "sha256": digest(path)} for path in classpath]})
    write_json(run / "packaged-run-manifest.json", closure)
    write_json(args.report.resolve(), closure)
    print(json.dumps({"prepared": str(run), "irisSha256": digest(iris), "classpathJars": len(classpath), "launch": str(run / "launch.ps1")}))


def classify_iris_loads(log, iris):
    pattern = re.compile(r"\[class,load\]\s+(net\.irisshaders\.iris\.\S+)\s+source:\s+(.*)$")
    helper_pattern = re.compile(r"(?P<owner>net\.irisshaders\.iris\..+)\$\$(?P<kind>Lambda|TypeSwitch)/0x[0-9a-fA-F]+$")
    expected_path = urlsplit(iris.resolve().as_uri()).path.casefold()
    entries = []
    for line in log.splitlines():
        match = pattern.search(line)
        if match:
            entries.append((match[1], match[2], line))

    def from_installed_jar(source):
        parsed = urlsplit(source)
        return parsed.scheme == "file" and not parsed.netloc and unquote(parsed.path).casefold() == unquote(expected_path)

    verified_hosts = {name for name, source, _ in entries if from_installed_jar(source)}
    named, generated, unexpected = [], [], []
    for name, source, line in entries:
        helper = helper_pattern.fullmatch(name)
        if helper and source in verified_hosts and helper["owner"] in verified_hosts \
                and (helper["owner"] == source or helper["owner"].startswith(source + "$")):
            # JDK 25 hidden switch/lambda classes name their lookup/nest host.
            # Both the generated-name owner and reported host must themselves
            # have loaded from the exact verified installed JAR.
            generated.append((helper["kind"], line))
            continue
        named.append(line)
        if not from_installed_jar(source):
            unexpected.append(line)
    return named, generated, unexpected


def inspect_run(args):
    run = args.run_dir.resolve()
    manifest = json.loads((run / "packaged-run-manifest.json").read_text())
    iris = Path(manifest["irisJar"])
    errors = []
    if digest(iris) != manifest["irisJarSha256"]:
        errors.append("Installed packaged Iris JAR changed after preparation")
    log = (run / "class-load.log").read_text(encoding="utf-8", errors="replace")
    iris_lines, generated, unexpected = classify_iris_loads(log, iris)
    if unexpected:
        errors.append("Iris class loaded outside the verified installed JAR")
    if not any("net.irisshaders.iris.Iris source:" in line for line in iris_lines):
        errors.append("No loaded Iris main class in JVM class-load evidence")
    if any("net.irisshaders.iris.probe." in line for line in log.splitlines()):
        errors.append("Disposable probe class loaded")
    report = {"passed": not errors, "errors": errors, "irisJarSha256": digest(iris),
              "loadedIrisClassCount": len(iris_lines), "unexpectedSources": unexpected,
              "generatedLambdaClassCount": sum(kind == "Lambda" for kind, _ in generated),
              "generatedTypeSwitchClassCount": sum(kind == "TypeSwitch" for kind, _ in generated),
              "verifiedGeneratedSourcesSample": [line for kind, line in generated if kind == "TypeSwitch"][:8],
              "loadedSourcesSample": iris_lines[:8],
              "scope": "Runtime packaged-JAR provenance only; logs and screenshots must separately establish backend and rendered-world behavior."}
    write_json(run / "packaged-runtime-provenance.json", report)
    print(json.dumps(report, indent=2))
    if errors:
        raise SystemExit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--jar", type=Path, required=True)
    prep.add_argument("--jdk", type=Path, required=True)
    prep.add_argument("--run-dir", type=Path, required=True)
    prep.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches")
    prep.add_argument("--report", type=Path, default=Path("build/release-closure-report.json"))
    sodium_input = prep.add_mutually_exclusive_group()
    sodium_input.add_argument("--sodium", default="net.caffeinemc:sodium-fabric:0.9.1-beta.3+mc26.2")
    sodium_input.add_argument("--sodium-jar", type=Path, help="Explicit verified local Sodium JAR; the default remains the tested beta3 artifact")
    prep.add_argument("--backend", choices=("vulkan", "opengl"), default="vulkan")
    prep.add_argument("--shader-pack", type=Path)
    prep.add_argument("--ultra", action="store_true")
    prep.add_argument("--world-source", type=Path, help="Explicit closed test world to copy; source is never modified")
    inspect = sub.add_parser("inspect")
    inspect.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args)
    else:
        inspect_run(args)


if __name__ == "__main__":
    main()
