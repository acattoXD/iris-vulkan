# Sodium 0.9.2 compatibility bridge for Minecraft 26.3

Sodium 0.9.2 for Fabric 26.3 changed `RenderRegion.DeviceResources` from a direct
`ChunkMeshFormats.COMPACT` field read to `ChunkMeshFormats.getCurrent()`. Its arenas
now use `ArenaAggregator` and `RegionAllocatorHandle`; the geometry/index buffer
accessors used by native shadows retain their signatures.

`MixinRenderRegionArenas` supports both stride sources with two individually
optional redirects in one group requiring **exactly one** successful injection.
Both obtain `WorldRenderingSettings`' current vertex type. The group prevents a
silent fallback to Sodium's 20-byte compact layout when Iris needs 36 bytes.

Sodium's new `ArenaAggregator` also registers its geometry datatype from a
separate hardcoded COMPACT read. `MixinArenaAggregator` replaces that registration
with the same current Iris format; its `@Pseudo` target is absent on beta3.
Changing only `RenderRegion.DeviceResources` leaves the allocator at 20 bytes and
crashes when it is asked for 36-byte geometry. The allocator test executes the
real 0.9.2 constructor and private `getDataTypeForStride`: the unpatched class
reproduces the crash, and the actual production callback fixes all 16 Iris
attribute combinations while preserving 4-byte indices and invalid-stride errors.

Geometry strides remain immutable for existing allocations. A format change
marks `WorldRenderingSettings` for reload; Iris calls `LevelExtractor.allChanged`,
which reaches `LevelRenderer.invalidateCompiledGeometry`. Sodium's hook invokes
`SodiumWorldRenderer.reload`, destroys the old section/region manager and its
aggregator, then constructs new ones. The regression verifies this actual
bytecode chain and tests the 36→20→36 settings/reconstruction sequence. It does
not claim to have exercised a complete GPU shader-toggle lifecycle by itself.

```powershell
tests/native-world/verify-sodium-versions.ps1 `
  -JdkPath 'C:/Program Files/Java/jdk-25.0.3' `
  -NewSodiumJar 'build/sodium-0.9.2-audit/sodium-fabric-0.9.2+mc26.2.jar' `
  -ShaderPack '<copied-test-profile>/shaderpacks/ComplementaryUnbound_r5.8.1.zip'
```

This CPU-only runner uses beta3 and 0.9.2 in separate classpaths, checks exact-one
group matching against both actual JARs, invokes both handlers with extended and
compact vertex types, and runs native hook/material-byte/SPIR-V regressions. It
does not launch Minecraft or change the original runtime classpath.

The official 0.9.2 artifact is Modrinth version `xJZxADzI`; audit provenance and
the verified publisher SHA-512 are retained in `build/sodium-0.9.2-audit`.
All nine embedded Fabric modules and the core vertex ABI classes are identical
to beta3. Sodium 0.9.2 also excludes Iris versions `<=1.11.2`, so release metadata
must be updated separately only for a newly tested artifact. The frozen alpha1
and matched optimization baselines retain beta3.

Use the packaged harness's explicit `--sodium-jar` argument for a separate normal
installation trial once release constraints permit it. Terrain streaming,
translucent sorting, native shadows and shader reloads still require actual
runtime validation; these CPU checks do not establish full compatibility.
