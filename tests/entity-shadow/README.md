# Vanilla entity shadow suppression

```powershell
./tests/entity-shadow/verify.ps1 -JdkPath '<path-to-jdk-25>' -ShaderPack '<path-to-local-shader-pack.zip>'
```

The CPU regression compiles the current native world pipeline, shared entity
dispatcher mixin, and actual mixin-selection plugin. It invokes the real
`iris$maybeSuppressEntityShadow` condition through a real PipelineManager and
executes native `renderShadows` with a recording shadow renderer. Minecraft/GPU
startup is bypassed; shader-pack parsing, predicates and lifecycle remain real.
ASM also checks the exact `SubmitNodeCollector.submitShadow(PoseStack,float,List)`
invocation exists in Minecraft 26.3's dispatcher `submit` method.

The 16 checks cover both backend selections; null/vanilla pipelines; actual
Mineek with no shadow program; retaining its vanilla shadow before and after
the no-shadow frame stage; an installed but unused renderer; suppression before
the first native shadow pass, after the pass and after the next-frame
`shadowsRendered` reset; and explicit `shadow.enabled=false`.

This mirrors OpenGL's installed-shadow-renderer policy while retaining the
native pack's `usesShadowMaps` check. It does not disable every vanilla ground
shadow merely because a shader pack is loaded. Results are written to
`build/entity-shadow-tests/result.txt`. A real GPU scene is still required to
verify appearance and confirm submission filtering during normal rendering.
