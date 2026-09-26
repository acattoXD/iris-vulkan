"""Compare matched packaged benchmark reports; reject mismatched inputs before timing claims."""
import argparse
import json
from pathlib import Path
import statistics


def compare(before, after):
    for label, report in (("baseline", before), ("candidate", after)):
        if not report.get("passed"):
            raise ValueError(f"{label} benchmark did not pass")
        settings = report["input"]["settings"]
        if not isinstance(settings, dict) or settings.get("inactivityFpsLimit") != "minimized":
            raise ValueError(f"{label} benchmark did not disable AFK throttling with Minecraft's minimized setting")
        for window in report["windows"]:
            limiter = window.get("limiter", {})
            frames = window.get("frames", 0)
            if frames <= 0 or limiter.get("samples") != frames \
                    or limiter.get("throttleReasons") != {"NONE": frames} \
                    or limiter.get("effectiveCapsFps") != {str(settings["maxFps"]): frames} \
                    or limiter.get("iconifiedFrames") != 0:
                raise ValueError(f"{label} benchmark lacks complete unthrottled live limiter evidence")
    a, b = before["input"], after["input"]
    for key in ("probeJarSha256", "shaderPackSha256", "shaderOptionsSha256", "settings", "timing"):
        if a[key] != b[key]:
            raise ValueError(f"Unmatched benchmark input: {key}")
    if a["worldSnapshot"]["sha256"] != b["worldSnapshot"]["sha256"]:
        raise ValueError("Unmatched source worlds")
    if before["device"] != after["device"]:
        raise ValueError("GPU/driver/timestamp metadata differs")
    if before.get("productionFlags") or after.get("productionFlags"):
        raise ValueError("A benchmark used production rendering-development overrides")
    results = {}
    for scope in ("frameIntervalMs", "frameWallMs", "renderThreadCpuMs", "worldSubmitMs", "gpuWorldMs"):
        results[scope] = {}
        for stat in ("mean", "p50", "p95", "p99", "max"):
            baseline = statistics.median(window[scope][stat] for window in before["windows"])
            candidate = statistics.median(window[scope][stat] for window in after["windows"])
            results[scope][stat] = {"baselineMedianWindow": baseline, "candidateMedianWindow": candidate,
                                   "reductionPercent": (1 - candidate / baseline) * 100 if baseline else None}
        results[scope]["tailCounts"] = {
            stat: {"baseline": sum(window[scope][stat] for window in before["windows"]), "candidate": sum(window[scope][stat] for window in after["windows"])}
            for stat in ("over33ms", "over50ms", "over100ms")}
    return {"matchedInputs": True, "baselineJarSha256": a["irisJarSha256"], "candidateJarSha256": b["irisJarSha256"],
            "metrics": results, "interpretation": "Positive reduction means shorter measured time. Median across repeated windows limits outlier influence; compare the full per-window report and screenshots. FPS cap can hide throughput changes. Tail counts also depend on sample count."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.baseline.read_text()), json.loads(args.candidate.read_text()))
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
