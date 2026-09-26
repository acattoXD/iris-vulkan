"""Summarize the real pack's preprocessed CPU source inventory and verify High fidelity."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build/ultra-audit"
COMMENTS = re.compile(r"/\*.*?\*/|//[^\r\n]*", re.S)


def normalize(source):
    return re.sub(r"\s+", "", COMMENTS.sub("", source))


def read(path):
    return path.read_text(encoding="utf-8")


report = {"scope": "Preprocessed source contracts and High source-equivalence test. GPU execution is not validated here."}
comparisons = []
reference = ROOT / "fabric/run-vulkan-pack-audit/active-programs"
for path in sorted((OUTPUT / "HIGH/world0").iterdir()):
    original = reference / path.name
    if not original.is_file():
        continue
    actual, expected = normalize(read(path)), normalize(read(original))
    comparisons.append({"source": path.name, "matchesRuntimeAudit": actual == expected,
                        "normalizedSha256": hashlib.sha256(actual.encode()).hexdigest()})
report["highSourceEquivalence"] = comparisons
assert comparisons and all(item["matchesRuntimeAudit"] for item in comparisons), "High differs from the runtime ProgramSet audit"

profiles = {}
for name in ("HIGH", "ULTRA"):
    path = OUTPUT / name
    props = read(path / "preprocessed.properties")
    inventory = json.loads(read(path / "inventory.json"))
    images, buffers = [], []
    for image_name, definition in re.findall(r"^image\.(\w+)\s*=\s*(.+)$", props, re.M):
        parts = definition.split()
        sampler, pixel, internal, dtype, clear, relative = parts[:6]
        dims = [int(x) for x in parts[6:]]
        assert relative == "false"
        bpp = {"r16ui": 2, "r8ui": 1, "rgba16f": 8, "rgba8": 4}[internal.lower()]
        size = bpp
        for dimension in dims:
            size *= dimension
        images.append({"image": image_name, "samplerAlias": sampler, "format": internal,
                       "dimensions": dims, "initialZeroClear": True, "perFrameClear": clear == "true",
                       "bytes": size, "MiB": size / 2**20})
    for binding, size in re.findall(r"^bufferObject\.(\d+)\s*=\s*(\d+)\s*$", props, re.M):
        buffers.append({"binding": int(binding), "bytes": int(size), "MiB": int(size) / 2**20,
                        "initialZeroClear": True, "perFrameClear": False})
    dimensions = {}
    for dim in ("world0", "world-1", "world1"):
        stages, computes = [], []
        for source in sorted((path / dim).iterdir()):
            text = COMMENTS.sub("", read(source))
            stores = sorted(set(re.findall(r"\bimageStore\s*\(\s*(\w+)", text)))
            blocks = re.findall(r"layout\(std430,\s*binding\s*=\s*(\d+)\)\s*(readonly\s+)?buffer\s+(\w+)", text)
            if stores or blocks:
                stages.append({"source": source.name, "imageStoreTargets": stores,
                               "storageBlocks": [{"binding": int(binding), "block": block, "readonly": bool(ro)} for binding, ro, block in blocks]})
            if source.suffix == ".csh":
                local = re.search(r"layout\s*\(local_size_x\s*=\s*(\d+),\s*local_size_y\s*=\s*(\d+),\s*local_size_z\s*=\s*(\d+)\)", text)
                groups = re.search(r"const\s+ivec3\s+workGroups\s*=\s*ivec3\((\d+),\s*(\d+),\s*(\d+)\)", text)
                computes.append({"name": source.stem, "localSize": [int(x) for x in local.groups()],
                                 "workGroups": [int(x) for x in groups.groups()],
                                 "phase": "shadow composite: after all shadow geometry, before prepare and main world draws"})
        dimensions[dim] = {"computeSchedule": computes, "imageAndStorageWritersReaders": stages}
    bytes_total = sum(item["bytes"] for item in images + buffers)
    profiles[name] = {"customImages": images, "SSBOs": buffers, "advancedResourceBytes": bytes_total,
                      "advancedResourceGiB": bytes_total / 2**30,
                      "selectedProfileOptions": {key: inventory["selectedOptions"][key] for key in
                           ("SHADOW_QUALITY", "shadowDistance", "COLORED_LIGHTING", "WORLD_SPACE_REFLECTIONS", "WORLD_SPACE_PLAYER_REF", "RAIN_PUDDLES", "ANISOTROPIC_FILTER", "DETAIL_QUALITY", "LIGHTSHAFT_QUALI_DEFINE", "CLOUD_QUALITY")},
                      "dimensions": dimensions}
report["profiles"] = profiles
assert profiles["ULTRA"]["advancedResourceBytes"] == 2052325376
assert len(profiles["ULTRA"]["customImages"]) == 5 and len(profiles["ULTRA"]["SSBOs"]) == 1
assert profiles["HIGH"]["advancedResourceBytes"] == 0
for dim in profiles["ULTRA"]["dimensions"].values():
    assert dim["computeSchedule"] == [{"name": "shadowcomp", "localSize": [8, 8, 8], "workGroups": [64, 32, 64],
                                      "phase": "shadow composite: after all shadow geometry, before prepare and main world draws"}]
(OUTPUT / "requirements.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(f"ULTRA_REQUIREMENTS_PASS: {len(comparisons)} High stages match runtime; exact Ultra allocation 2,052,325,376 bytes; one shadowcomp dispatch per dimension")
