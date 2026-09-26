"""Check actual Sodium/compiled Iris bytecode for the 26.3 shadow batch lifecycle.

This regression guards API/lifecycle assumptions; it is not a rendered-shadow test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def method(text, signature):
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("  ") and signature in line)
    end = start + 1
    while end < len(lines) and (not lines[end].startswith("  ") or lines[end].startswith("    ") or not lines[end].strip()):
        end += 1
    return "\n".join(lines[start:end])


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sodium", type=Path, required=True)
    parser.add_argument("--iris", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Java/jdk-25.0.3"))
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    checked, errors = [], []

    def inspect(jar, name, verbose=False):
        result = subprocess.run([str(args.jdk / "bin/javap.exe"), "-p", "-c"] + (["-v"] if verbose else [])
                                + ["-classpath", str(jar.resolve()), name], capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout

    def check(condition, description):
        (checked if condition else errors).append(description)

    base = "net.caffeinemc.mods.sodium.client."
    renderer = inspect(args.sodium, base + "render.chunk.DefaultChunkRenderer")
    prepare = method(renderer, "public void prepare(")
    draw = method(renderer, "public void render(")
    check("useBlockFaceCulling:Z" in prepare and "useBlockFaceCulling:Z" not in draw,
          "Face-culling hook belongs to prepare, not render")
    check("fillCommandBuffer:" in prepare and "MultiDrawBatch.prepare:" in prepare and "shouldDraw:[Z" in prepare,
          "prepare fills/uploads batches and updates per-pass draw flags")
    check("fillCommandBuffer:" not in draw and "ensureCapacity:" not in draw and "shouldDraw:[Z" in draw,
          "render consumes prepared batches without allocating/filling them")
    manager = inspect(args.sodium, base + "render.chunk.RenderSectionManager")
    regular = method(manager, "public void prepareChunkRendering(")
    check("getRenderLists:" in regular and "ChunkRenderer.prepare:" in regular,
          "Regular preparation uses the manager's existing render lists")
    check("ChunkRenderer.rotate:" in method(manager, "public void prepareRender("),
          "Sodium rotates draw storage at the existing frame boundary")
    indirect = inspect(args.sodium, base + "gpu.device.batch.VKIndirectDrawBatch")
    indirect_prepare = method(indirect, "public void prepare(")
    check("VKIndirectContext.addCommand:" in indirect_prepare and "MemoryUtil.memCopy:" in indirect_prepare,
          "Indirect preparation appends copied command bytes before GPU draws")

    shadow = inspect(args.iris, "net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer")
    shadow_frame = method(shadow, "public void render(")
    preparations = [m.start() for m in re.finditer("ChunkRenderer.prepare:", shadow_frame)]
    terrain_draw = shadow_frame.find("Method drawTerrain:")
    check(len(preparations) == 3 and 0 < preparations[0] < terrain_draw,
          "Shadow batches prepared before terrain draw, with regular preparation in both finally paths")
    check("getRenderLists:" in shadow_frame and "Field net/minecraft/client/renderer/state/level/CameraRenderState.pos:" in shadow_frame,
          "Restoration retains regular render lists and extracted player camera position")
    check("ChunkRenderer.prepare:" not in method(shadow, "private void drawTerrain("),
          "No batch allocation/upload occurs inside drawTerrain's open RenderPass")
    check("ChunkRenderer.rotate:" not in shadow_frame,
          "Shadow rendering does not rotate/reset already-recorded frame command storage")
    for position in preparations[1:]:
        previous_prepare = shadow_frame.rfind("ChunkRenderer.prepare:", 0, position)
        region = shadow_frame[previous_prepare:position]
        check("putstatic" in region and "Field rendering:" in region and "clearAllCachedBatches:" in region,
              "Regular preparation follows shadow cache invalidation and scope restoration")
    hook = inspect(args.iris, "net.irisshaders.iris.mixin.vulkan.VKOnly_MixinDefaultChunkRenderer_Shadow", True)
    check('method=["prepare"]' in hook and "useBlockFaceCulling:Z" in hook,
          "Compiled native face-culling redirect targets Sodium prepare")
    report = {"passed": not errors, "checks": checked, "errors": errors,
              "sodiumSha256": sha(args.sodium), "irisSha256": sha(args.iris),
              "scope": "Actual dependency and compiled-hook bytecode/lifecycle contract. GPU rendering must be checked separately."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
