# Custom 2D storage-sampler regression

`IrisVulkanCustom2DSamplerTest` reads the exact `image.puddle_img` declaration from a locally supplied Complementary Unbound r5.9.1 ZIP. The ZIP is a test input and is not distributed with the mod or its source bundle.

The test uses the real `ShaderProperties` parser, `ImageInformation` sizing, `ResourceSet.collect`, advanced-path selection, Mojang shaderc frontend, and SPIRV-Cross reflection. It proves that the unsigned 128×128 R8UI sampler is kept in the advanced descriptor set while ordinary albedo remains in the normal texture bindings. It also checks that an unregistered 2D sampler is not removed merely because its name is `puddle_sampler`.

The isolated Iris facade supplies only logging/configuration and a metadata-only real `ShaderPack` instance. No GPU image or Minecraft session is created. Use the current compiled production classes plus Minecraft/native compiler dependencies, compile the facade/test and `IrisVulkanShaderResources.java` to a dedicated output directory, then run `net.irisshaders.iris.vulkan.IrisVulkanCustom2DSamplerTest <pack.zip>`. Never put the test facade into a production JAR.

With the existing line-delimited runtime classpath available, run `./tests/storage-2d/verify.ps1 -JdkPath <jdk25> -ShaderPack <ComplementaryUnbound_r5.9.1.zip>`.

`extract_profile.py --pack <pack.zip> --profile ULTRA --output <isolated-run>/shaderpacks/<pack.zip>.txt` writes the actual profile's option values and a provenance JSON file. Run the generic world probe with `-PirisProbeWorld -PirisProbeStorage -PirisProbeScenarios -PirisProbeExpectedProfile=ULTRA`, without `-PirisProbeUltra`; generic scenarios preserve quality and cover rain/reload. Each screenshot gets a `-pack-resources.json` with exact selected options, dynamically declared image sizes/formats, real image/view/sampler handles and compute sequence. This is resource/selection evidence, not a texel readback or visual-compatibility verdict.
