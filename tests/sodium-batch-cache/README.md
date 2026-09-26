# Sodium region batch-cache invalidation

The obsolete `MixinRenderRegionManager` redirect targeted `RenderRegion.clearAllCachedBatches()` inside a private `uploadResults(RenderRegion, Collection, UniformBufferManager)` overload. That overload still exists in beta.3 and 0.9.2, but 0.9.2 no longer invokes the all-batches clear there. The removed redirect therefore had no target in 0.9.2.

It is redundant on beta.3: the retained `MixinRenderRegion` injects into `clearAllCachedBatches` itself and clears active, saved regular, and saved shadow batches. Its `clearCachedBatchFor` hook also invalidates inactive caches while vanilla clears the selected active batch. Sodium's actual `MultiDrawBatch.clear()` is final and idempotently resets size, filled state and maximum element count, so the retained HEAD hook followed by the vanilla method is safe.

`IrisSodiumBatchCacheTest` checks the configuration no longer registers the manager redirect, inspects each publisher JAR's real upload/clear bytecode, and minimally merges the retained region mixin into that JAR's actual `RenderRegion`. It validates the real HEAD annotations and executes real batch clearing across regular/shadow swaps, all-batch invalidation, per-pass invalidation, and repeated clearing. Constructors that allocate GPU resources are skipped; no game, OpenGL context, or Vulkan device is created. This is a targeted method/field merge, not a complete Mixin bootstrap or a rendering verdict.

Compile the production `MixinRenderRegion.java` and test against the project's current classpath, then run:

```text
net.irisshaders.iris.vulkan.IrisSodiumBatchCacheTest <port-root> <sodium-beta3.jar> <sodium-0.9.2.jar>
```

Verified results: beta.3's upload overload has one `clearAllCachedBatches` and four `clearCachedBatchFor` calls; 0.9.2 has zero and three respectively. Retained region hooks clear both scopes correctly on both versions. OpenGL/Vulkan login and rendering still require independent runtime checks.
