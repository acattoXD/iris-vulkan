# Alpha5 changes and validation

Version: `1.11.3-vulkan-alpha.5+mc26.2`. Tested JAR SHA-256: `920016e9ae6701d705a0c66828814e7fb2de90995219a307aaa098ebf319b468`.

Requirements remain Minecraft 26.2, Java 25, Fabric Loader 0.19.2+, and exactly one of Sodium `0.9.1-beta.3+mc26.2` or `0.9.2+mc26.2`. Replace any earlier Iris JAR; this unofficial fork retains mod ID `iris`.

## End Portal and gateway rendering

The reported failure occurs with Minecraft 26.2, Vulkan, Sodium 0.9.2, and Complementary Unbound r5.9.1 High. When an End Portal becomes active, its vanilla position-only geometry reaches the block-entity shader route, which expects `iris_Color`. Native shader compilation rejects the missing input.

Alpha5 enables the existing `MixinTheEndPortalRenderer` and `MixinTheEndGatewayRenderer` hooks on both graphics backends. With a loaded shader pack, these hooks select the portal texture and provide complete entity vertices: color, animated UV coordinates, full-bright light coordinates, overlay, and normals. The portal's existing tint and animation are retained.

The native shader adapter also supplies `vec4(1.0)` when a non-array `vec4 iris_Color` input is genuinely absent from the vertex format. This matches the no-color path's multiplication by the color modulator. A supplied vertex-color attribute remains unchanged; the fallback does not recolor normal entity or portal vertices.

## Validation status

| Check | Alpha5 status |
| --- | --- |
| Portal/gateway hooks selected for both backends | [638 focused checks pass](../tests/portals/README.md), exercising the actual production handlers and backend-selection gates. |
| Portal vertices, animated UVs, full-bright lighting and texture routing | The same suite verifies all six faces, packed tint, transformed normals, light/overlay values, time-dependent UVs, and the actual portal texture. |
| Missing-color fallback and preservation of supplied color | Native GLSL/SPIR-V tests reproduce the original missing-color failure, verify the identity fallback with a nonwhite/nonopaque modulator, preserve supplied colors, and retain strict failures for unknown inputs. |
| Actual twelfth Ender Eye activates a completed portal frame / Vulkan, High | [High passed](../build/release-validation/portal-stable/alpha5-portal-high/native-probe-result.txt). The [earlier alpha4 control](../build/release-validation/portal-final/alpha4-portal-high/launcher.log) reproduced the original compile exception on activation. |
| Gateway, portal removal/recreation, and shader reload / High and Ultra | All six stages passed in both the [High report](../build/release-validation/portal-stable/alpha5-portal-high/evidence/portal-report.json) and [Ultra report](../build/release-validation/portal-stable/alpha5-portal-ultra/evidence/portal-report.json). Portal and gateway screenshots were visually reviewed, including the gateway-only surface and both surfaces after reload. |

Runtime checks used Windows 11, RTX 5070 / NVIDIA 616.92, Java 25.0.3 and Sodium 0.9.2. Both runs loaded the exact packaged JAR above with a separate diagnostic probe; provenance checked 22 class resources and found no unexpected sources. The fixture settles 120 frames before measuring 60 frames, avoiding previous-frame submissions during block changes. It verifies portal material 5025, full vertex inputs, texture identity and completed draws; gateway beams are checked separately. See [fixture instructions](../tests/portal/README.md). These runs do not establish every mod combination or GPU's compatibility.

[Alpha4](ALPHA4.md) records the earlier Sodium region-manager correction, and [alpha3](ALPHA3.md) records atlas binding and Vulkan regression results. Those results belong to their documented artifacts. Full pack/device compatibility and a performance improvement remain unverified. Pair a new binary with its exact source/checksum bundle using [the release procedure](RELEASING.md); earlier release bundles remain unchanged.
