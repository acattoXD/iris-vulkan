import copy
import unittest

from compare_benchmarks import compare
from prepare_benchmark import BENCHMARK_OPTION_OVERRIDES


def report(value):
    metric = {"mean": value, "p50": value, "p95": value, "p99": value, "max": value,
              "over33ms": 0, "over50ms": 0, "over100ms": 0}
    scopes = ("frameIntervalMs", "frameWallMs", "renderThreadCpuMs", "worldSubmitMs", "gpuWorldMs")
    return {"passed": True, "productionFlags": [], "device": {"name": "fixture", "timestampPeriodNs": 1},
            "input": {"probeJarSha256": "probe", "shaderPackSha256": "pack", "shaderOptionsSha256": "options", "settings": {"inactivityFpsLimit": "minimized", "maxFps": 260}, "timing": {}, "worldSnapshot": {"sha256": "world"}, "irisJarSha256": str(value)},
            "windows": [{**{scope: dict(metric) for scope in scopes}, "frames": 100,
                         "limiter": {"samples": 100, "throttleReasons": {"NONE": 100}, "effectiveCapsFps": {"260": 100}, "iconifiedFrames": 0}}
                        for _ in range(3)]}


class BenchmarkToolsTest(unittest.TestCase):
    def test_preparation_uses_actual_minimized_enum_serialization(self):
        self.assertEqual(BENCHMARK_OPTION_OVERRIDES["inactivityFpsLimit"], '"minimized"')

    def test_time_reduction_is_positive_and_uses_repeated_windows(self):
        result = compare(report(10), report(8))
        self.assertAlmostEqual(result["metrics"]["gpuWorldMs"]["p95"]["reductionPercent"], 20)

    def test_different_world_profile_probe_or_device_is_rejected(self):
        for key in ("probeJarSha256", "shaderPackSha256", "shaderOptionsSha256", "settings", "timing", "worldSnapshot"):
            changed = copy.deepcopy(report(8))
            changed["input"][key] = {"sha256": "other"} if key == "worldSnapshot" else "different"
            with self.subTest(key=key), self.assertRaises(ValueError):
                compare(report(10), changed)
        changed = report(8)
        changed["device"]["name"] = "different"
        with self.assertRaises(ValueError):
            compare(report(10), changed)

    def test_failed_benchmark_is_not_reported_as_speedup(self):
        changed = report(1)
        changed["passed"] = False
        with self.assertRaises(ValueError):
            compare(report(10), changed)

    def test_afk_or_missing_live_limiter_evidence_is_rejected(self):
        for modification in ("afk", "missing", "short_afk", "cap", "iconified", "partial"):
            changed = report(8)
            window = changed["windows"][0]
            if modification == "afk":
                changed["input"]["settings"]["inactivityFpsLimit"] = "afk"
            elif modification == "missing":
                window.pop("limiter")
            elif modification == "short_afk":
                window["limiter"]["throttleReasons"] = {"SHORT_AFK": 100}
            elif modification == "cap":
                window["limiter"]["effectiveCapsFps"] = {"30": 100}
            elif modification == "iconified":
                window["limiter"]["iconifiedFrames"] = 1
            else:
                window["limiter"]["samples"] = 99
            with self.subTest(modification=modification), self.assertRaises(ValueError):
                compare(report(10), changed)


if __name__ == "__main__":
    unittest.main()
