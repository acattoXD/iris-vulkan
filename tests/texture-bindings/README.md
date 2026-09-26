# Mixed producer texture bindings (Minecraft 26.3)

After compiling production classes, run:

```powershell
python ports/iris-vulkan-26.3/tests/texture-bindings/verify.py --jdk 'C:/Program Files/Java/jdk-25.0.3' --classpath-json build/optimization-26.3/uniform-tests/classpath.json --output build/optimization-26.3/texture-bindings-01
```

The contract invokes the actual compiled `IrisVulkanRenderPassBindings` methods through reflection. It checks every `Patch.SODIUM` shader key, including solid/cutout/translucent shadow routes, while fake terrain and feature resources coexist in a retained map. It copies/replays that map, switches back to font/entity/null-key selection, checks both view and sampler identity and dimensions, and rejects foreign producer fallback. The real `findTextureBinding` is exercised for all albedo aliases, valid lightmaps, glint, and staged/global custom precedence. Custom resource caches are populated in the isolated test JVM and restored afterward; no shader pack is loaded.

A test-only service provider prevents Fabric launcher initialization. Production binding, custom-texture, Iris, and shader-key classes are not stubbed or recompiled by this runner. Fake resources do not allocate GPU objects. The runner verifies production class origin and records its SHA-256 with results.

This is a CPU selector regression, not a rendered-image or FPS test. Map copying models retained/replayed state but does not execute `WorldRenderPass`. The missing-lightmap engine fallback and custom-resource uploads require a running renderer and are outside this test.
