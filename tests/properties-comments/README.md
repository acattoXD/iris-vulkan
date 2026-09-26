# Properties comment regression

This CPU-only test executes the production properties preprocessor and JCPP. It reproduces the previous truncated item map, then checks CR/LF line endings, conditionals, continuations, and every item mapping in a separately supplied Derivative shader pack. No third-party pack is bundled.

```powershell
python tests/properties-comments/verify.py --jdk '<jdk-25-directory>' --iris '<alpha18-control.jar>' --classpath-json '<runtime-classpath.json>' --pack '<Derivative-pack.zip>' --output '<new-evidence-directory>'
```

The control JAR must predate the comment fix; the patched preprocessor is compiled directly from this checkout. A runtime classpath JSON is an array of local dependency-JAR paths. This test never creates a graphics device or launches Minecraft.
