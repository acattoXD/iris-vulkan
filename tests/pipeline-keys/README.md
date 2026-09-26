# Native world pipeline shader-key regression

Runs real RenderPearl descriptors and production adaptation/key-routing code without starting Minecraft or creating a graphics device. Existing compiled Iris classes and a Minecraft 26.3 runtime classpath are required; the changed production files are compiled afresh before the test facades.

```powershell
./tests/pipeline-keys/verify.ps1 -JdkPath '<path-to-jdk-25>' -RuntimeClassPath '<path-to-26.3-runtime-classpath.json>'
```

Checks pack-disabled blending on translucent Sodium terrain, pack-enabled blending on cutout terrain, repeated adapted and format-compatible descriptor identities, metadata propagation through multiple copies, original descriptor world/shadow switching, distinct immutable world/shadow variants, and pack teardown clearing all related caches. It does not validate live GPU rendering or shader-pack visuals.
