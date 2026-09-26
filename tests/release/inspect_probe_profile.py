"""Audit packaged production/probe provenance separately after a diagnostic run."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

import packaged_run


def source_matches(source, expected_jar, resource):
    if not source.startswith("jar:") or "!/" not in source:
        return False
    jar_url, actual_resource = source[4:].split("!/", 1)
    parsed = urlsplit(jar_url)
    expected_url = urlsplit(Path(expected_jar).resolve().as_uri())
    return parsed.scheme == "file" and not parsed.netloc and actual_resource == resource \
        and unquote(parsed.path).casefold() == unquote(expected_url.path).casefold()


def inspect(run):
    run = run.resolve()
    manifest = json.loads((run / "packaged-run-manifest.json").read_text())
    if not manifest.get("probeModInstalled"):
        raise ValueError("Use packaged_run.py inspect for a probe-free run")
    iris, probe = Path(manifest["irisJar"]), Path(manifest["probeJar"])
    errors = []
    for artifact, key in ((iris, "irisJarSha256"), (probe, "probeJarSha256")):
        if packaged_run.digest(artifact) != manifest[key]:
            errors.append(f"Installed artifact changed: {artifact.name}")
    recorded = json.loads((run / "evidence/loaded-build.json").read_text())
    for name, expected in manifest["loadedBuildExpected"].items():
        actual = recorded.get(name, {})
        if actual.get("sha256") != expected["sha256"] or not source_matches(actual.get("source", ""), expected["jar"], expected["resource"]):
            errors.append(f"Loaded class resource mismatch: {name}")
    lines = (run / "class-load.log").read_text(encoding="utf-8", errors="replace").splitlines()
    production = "\n".join(line for line in lines if "net.irisshaders.iris.probe." not in line)
    diagnostic = "\n".join(line for line in lines if "net.irisshaders.iris.probe." in line)
    prod_named, prod_generated, prod_unexpected = packaged_run.classify_iris_loads(production, iris)
    probe_named, probe_generated, probe_unexpected = packaged_run.classify_iris_loads(diagnostic, probe)
    if prod_unexpected:
        errors.append("Production Iris class loaded outside the installed alpha JAR")
    if probe_unexpected:
        errors.append("Diagnostic probe class loaded outside the separate installed probe JAR")
    if not prod_named or not probe_named:
        errors.append("Expected both production and separately installed diagnostic classes")
    arguments = (run / "launch.args").read_text()
    flags = re.findall(r'"(-D[^"\n]+)"', arguments)
    if flags != manifest["diagnosticFlags"] or any(not flag.startswith("-Diris.vulkan.probe.") for flag in flags):
        errors.append("Diagnostic launch flags changed or override production defaults")
    if "--quickPlaySingleplayer" in arguments:
        errors.append("Generic probe launch unexpectedly bypassed title/world creation")
    report = {
        "passed": not errors, "errors": errors, "probeInstalledSeparately": True,
        "irisJarSha256": packaged_run.digest(iris), "probeJarSha256": packaged_run.digest(probe),
        "loadedBuildResourcesChecked": len(manifest["loadedBuildExpected"]),
        "loadedProductionClasses": len(prod_named), "loadedProbeClasses": len(probe_named),
        "generatedProductionHelpers": len(prod_generated), "generatedProbeHelpers": len(probe_generated),
        "unexpectedSources": prod_unexpected + probe_unexpected,
        "scope": "Production packaged-JAR and separate diagnostic-probe provenance only; rendering/scenario verdicts require their own reports and visual review.",
    }
    packaged_run.write_json(run / "diagnostic-runtime-provenance.json", report)
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    raise SystemExit(inspect(parser.parse_args().run_dir))
