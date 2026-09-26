# Native graphics uniform uploads

Native graphics binds now serialize fresh values into bounded, zeroed stack
scratch and call Minecraft's `TransientMemory.uploadGpu`. Blocks exceeding 16 KiB
or available stack space use explicitly freed native scratch. The independent
`capture()` API still returns retained bytes, and compute uploads retain their
existing dedicated-buffer path. Layout caching never caches draw values.

The device's `minUniformOffsetAlignment` controls allocation alignment; the bound
slice retains the exact std140 block length. The returned transient slice is used
immediately and is not cached, mapped, closed, or retired by Iris.

Inspection of Minecraft 26.2 `VulkanTransientMemory.upload` establishes that it
allocates mapped arena space, executes `MemoryUtil.memCopy` synchronously, closes
the mapped view, then returns the slice. Scratch can therefore be freed upon
return. Arena transfers/retirement belong to the encoder submission; transient
buffer handles themselves become closed when its submission index advances.

```powershell
tests/uniform-layout/verify-transient.ps1 -JdkPath 'C:/Program Files/Java/jdk-25.0.3'
```

The test compares 66 production uploads against retained capture bytes across
changing frame values, same-frame entity/item IDs, texture sizes, matrix history,
custom uniforms, and a large block. It checks exact alignment/ranges/padding,
stack reuse, constrained caller stacks, no caller closes, and the actual 26.2 engine bytecode contract. The
recording upload boundary is a CPU test, not a claim of GPU rendering validation.
Existing layout/value regressions run alongside it.

For the matched runtime benchmark, subtract `IrisVulkanUniformSnapshot.uploadStats()`
at sample boundaries. Report transient upload count/bytes, stack/native-fallback
counts, and dedicated upload count separately. Graphics draws should produce
transient uploads; remaining dedicated uploads can come from compute. A counter
change alone is not a frame-time improvement: compare warmed render-thread CPU
and frame-wall p50/p95/p99 plus GPU timestamps against the frozen alpha JAR using
the same shader options, world, camera, inventory, resolution and uncapped
presentation. Verify held/enchanted items, water/translucency, bobbing and Ultra
storage after timing, with no screenshot/readback work in measured frames.
