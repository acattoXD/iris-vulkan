# Alpha14 candidate: restore shader option translations on Vulkan

Version `1.11.5-vulkan-alpha.14+mc26.3` for Minecraft26.3, Fabric Loader0.19.5+, Java25 and Sodium0.9.2+mc26.3.

JAR SHA256: `fe5f84092222731adc0e64fbdc7289686660c1e82ddb31a2c0932e492ca77d64`. Compared with the immutable Alpha13 JAR, only `fabric.mod.json` and `IrisMixinPlugin.class` differ. The ten existing distribution packaging checks pass.

Vulkan's startup mixin filter accidentally excluded Iris's existing language hook. The shader menu consequently displayed raw keys such as `PERFORMANCE_SETTINGS`, `SHADER_STYLE`, `RP_MODE` and `HIGH`, with untranslated numeric values. Alpha14 enables `MixinClientLanguage` on both graphics backends so the existing UTF-8 shader-pack language files supply names, values, descriptions and formatting again.

For Complementary Unbound r5.9.1, examples are yellow **Performance Settings**, **Visual Style** with purple **Unbound**, **RP Support** with aqua **Integrated PBR+**, and yellow **High (Default)**. Labels and color codes come from the selected pack; none are hard-coded to Complementary. The existing selected-language/English fallback and vanilla-translation precedence are preserved.

The only runtime code change from Alpha13 is the mixin eligibility entry. Rendering, shader quality, buffer management and compiler optimization options are unchanged. This build does not include the separate profiling or hurt-trace addons.

The translation mixin's four target signatures and storage field match Minecraft26.3. The actual pack archive contains the expected names and section-sign color codes. The full Fabric release build passes, including the existing native-target checks. In-game menu verification on the new JAR remains pending; the currently open Alpha13 game cannot acquire a startup mixin without restarting.

Replace the prior Iris JAR after closing that instance, keeping just one Iris JAR. Acquire shader packs separately. This is still an unofficial experimental Vulkan fork; Alpha13's broader pack/device/Ultra limitations continue to apply.
