#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-3.0-only
"""Check actual framebuffer captures of the native-final-check shaderpack.

Requires Pillow. Screenshots must contain only the client framebuffer, with the
HUD hidden and a varied world scene in view. Optionally compare a second image
taken roughly one second later to verify that frameTimeCounter changes output.
"""

import argparse
import json
import math
from pathlib import Path

from PIL import Image


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def linear(channel):
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def yellow(pixel):
    return pixel[0] >= 220 and pixel[1] >= 160 and pixel[2] <= 35


def check_capture(path):
    with Image.open(path) as source:
        image = source.convert("RGB")
    width, height = image.size
    require(width >= 128 and height >= 128 and width % 2 == 0,
            "Use an even framebuffer width and at least 128 x 128 pixels")
    pixels = image.load()
    mid = width // 2

    marker_columns = [x for x in range(max(0, mid - 8), min(width, mid + 8))
                      if pixels[x, height // 2][0] >= 230
                      and pixels[x, height // 2][1] <= 25
                      and pixels[x, height // 2][2] >= 230]
    require(len(marker_columns) == 4 and marker_columns == list(range(mid - 2, mid + 2)),
            f"Expected four centered magenta pixels, found columns {marker_columns}")

    # Native framebuffer readback can be top- or bottom-origin. Detect the
    # shader band at either edge without changing the scene comparison.
    band_x = max(1, round(width * 0.04))
    edge_top = yellow(pixels[band_x, 1])
    edge_bottom = yellow(pixels[band_x, height - 2])
    require(edge_top != edge_bottom, "Expected a yellow shader band at exactly one image edge")
    rows = range(height) if edge_top else range(height - 1, -1, -1)
    band_height = 0
    for y in rows:
        if not yellow(pixels[band_x, y]):
            break
        band_height += 1
    require(abs(band_height - 0.035 * height) <= 2,
            f"Band height {band_height} does not match live viewHeight={height}")
    band_y = max(1, band_height // 2) if edge_top else height - max(2, band_height // 2)
    sweep_pixels = sum(yellow(pixels[x, band_y]) for x in range(width))
    sweep = sweep_pixels / width
    require(0.09 <= sweep <= 0.91, f"Unexpected animated band extent {sweep:.3f}")

    paired_colors = []
    for row in range(40):
        y = round(height * (0.15 + 0.70 * row / 39))
        for column in range(40):
            x = round(width * (0.05 + 0.39 * column / 39))
            paired_colors.append((pixels[x, y], pixels[x + mid, y]))

    source_colors = [color for color, _ in paired_colors]
    source_range = max(max(color[c] for color in source_colors) - min(color[c] for color in source_colors)
                       for c in range(3))
    require(len(set(source_colors)) >= 32 and source_range >= 32,
            "Scene input is blank or too uniform; aim at varied world geometry")

    # Depending on framebuffer/screenshot encoding, compare either direct RGB
    # values or decoded sRGB. The successful representation is reported.
    comparisons = []
    scales = (0.35, 0.85, 1.0)
    offsets = (0.02, 0.05, 0.06)
    for encoding, decode in (("direct", lambda value: value), ("srgb", linear)):
        errors = []
        for original, toned in paired_colors:
            for c in range(3):
                expected = min(1.0, decode(original[c] / 255.0) * scales[c] + offsets[c])
                errors.append(abs(decode(toned[c] / 255.0) - expected))
        errors.sort()
        comparisons.append({"encoding": encoding, "mean_error": math.fsum(errors) / len(errors),
                            "p95_error": errors[math.floor(0.95 * (len(errors) - 1))]})
    comparison = min(comparisons, key=lambda result: result["mean_error"])
    require(comparison["mean_error"] < 0.03 and comparison["p95_error"] < 0.065,
            f"Sampled scene and cyan tone do not match: {comparisons}")
    return {"capture": str(path.resolve()), "width": width, "height": height,
            "marker_width": len(marker_columns), "band_height": band_height,
            "time_sweep_fraction": sweep, "unique_scene_samples": len(set(source_colors)),
            "tone_comparison": comparison}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("later_capture", type=Path, nargs="?")
    args = parser.parse_args()
    results = [check_capture(args.capture)]
    if args.later_capture:
        results.append(check_capture(args.later_capture))
        motion = abs(results[0]["time_sweep_fraction"] - results[1]["time_sweep_fraction"])
        require(motion > 0.04, "No clear frameTimeCounter change; capture again about one second later")
    print(json.dumps({"status": "PASS", "captures": results,
                      "live_time_uniform_checked": len(results) == 2}, indent=2))


if __name__ == "__main__":
    main()
