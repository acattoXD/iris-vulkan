# Packaged UI disclosure smoke tests

The disposable probe JAR contains an isolated menu-only controller, `NativeVulkanWarningProbe`. It exercises production UI callbacks and the actual Sodium binding; it does not replace production classes, open a world, force a shader profile, or enable any rendering-development switch.

Prepare three **new** packaged-run directories with `packaged_run.py`, each with `ui-warning` in its final directory name. Use the final runtime mod JAR and tested Sodium JAR. Keep these separate from the ordinary no-probe packaged smoke test.

| Mode | Actual renderer | Assertions |
| --- | --- | --- |
| `startup-accept` | Vulkan | Warning appears on the title screen; Continue preserves the shader setting, persists acknowledgment, and does not reopen. |
| `startup-decline` | Vulkan | Disable shaders returns to title, persists `enableShaders=false` plus acknowledgment, and survives a fresh config reload. |
| `opengl-selection` | OpenGL | No startup warning. Applying Vulkan through Sodium opens disclosure without changing the actual option; Escape cancels, restores Sodium's cached value and does not reopen on Apply. Selecting again and accepting saves Vulkan after Sodium's earlier storage flush. |

In each isolated directory, put a current `iris-native-probe.jar` in `mods` **in addition to** the final runtime mod and Sodium. Set `config/iris.properties` to:

```properties
enableShaders=true
shaderPack=
disableUpdateMessage=true
vulkanWarningVersion=0
```

Prefix the prepared `launch.args` with these two quoted JVM arguments, using the selected mode and that exact isolated game directory:

```text
"-Diris.uiSmoke=startup-accept"
"-Diris.uiSmoke.runDir=C:/path/to/ui-warning-startup-accept"
```

These are test-controller switches only. Do not add `iris.vulkan.worldDevelopment`, `iris.vulkan.storageDevelopment`, `iris.vulkan.screenPassMode`, Fabric development launch flags, or a quick-play world argument. The regular packaged-run manifest predates addition of the fixture, so this is a separate UI test, not a replacement for the normal no-probe install verdict.

Launch each test serially. The controller waits for rendered UI frames, saves visible screenshots to `evidence/`, checks screen narration text and persisted files, writes `evidence/ui-warning-report.json` and `ui-warning-result.txt`, and exits. A run is successful only with `IRIS_UI_WARNING_PASS` **and** visual inspection of the warning screenshots. It times out after two minutes. Reports record production class URLs and SHA-256 values so a stale/common classpath cannot masquerade as the final installed JAR.

The production runtime JAR must continue to exclude all probe classes. A normal OpenGL launch remains covered separately by the packaged-run harness without the fixture.
