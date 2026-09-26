# Cloud buffer binding regression

Run with JDK25, the unmodified Alpha20 control JAR and a JSON array of local Minecraft26.3 runtime dependency paths:

```powershell
python tests/cloud-bindings/verify.py --jdk '<jdk-25-directory>' --control-jar '<alpha20.jar>' --classpath-json '<runtime-classpath.json>' --output '<new-evidence-directory>'
```

The test invokes the original and patched production uniform binder, using actual flat/fancy Minecraft cloud pipeline layouts and a recording pass/buffer provider. It verifies refreshed buffer slices, strict missing-buffer handling, unchanged CloudFaces and the real engine producer invocation. No launcher, GPU, graphics device or normal profile is used.

Add `--textures` and use an Alpha21 control JAR to run the complete cloud buffer/texture replay. This reproduces the missing gtexture and stale-texture selection before checking explicit white inputs, both cloud keys, custom texture priority, unaffected textured producers and white texture creation/teardown. The test uses the sibling texture-binding suite's CPU resource/platform provider; production binding code is unchanged by the harness.
