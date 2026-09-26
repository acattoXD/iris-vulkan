# Render-pass ownership regression

`IrisVulkanRenderPassScopeTest` runs the production `IrisVulkanRenderPassBindings.apply`, `registerPipeline`, and `unregisterPipeline` methods. Small test facades replace only engine binding sinks, global-frame initialization, world-key lookup, and GPU descriptor commands. They record side effects without a game or GPU device; the production ownership/alias/validation logic is not copied into the test.

The fixtures cover:

- An unowned vanilla pipeline declaring `Globals` before global frame data exists. No Iris default, storage, uniform, or texture binding work may occur, and an explicitly supplied projection must remain unchanged.
- Distinct but value-equal compiled pipeline records sharing the same original `RenderPipeline`, with stale Iris resource metadata on that description. Only the registered compiled identity is owned.
- A registered pipeline still fails when a required uniform is missing; the error identifies both `Globals` and the pipeline.
- Registered custom screen passes and the storage-command path still receive binding work.
- Per-draw transform aliases refresh when the engine binding changes within one pass.
- Unregistering a retired compiled pipeline removes ownership; null/invalid pipeline objects do nothing.

Compile the production binder **against the real engine classes first** into a dedicated output directory. Compile `stubs/*.java` and the test separately, then run with the test-facade directory before the binder and normal dependency classpaths. The supplied `verify.ps1` uses the existing line-delimited isolated runtime classpath. Never include these facades in the release JAR.

This is an ownership/dispatch regression, not atlas animation rendering or a Metal runtime test. The existing storage reflection and in-game probes cover the GPU descriptor and visual paths separately.
