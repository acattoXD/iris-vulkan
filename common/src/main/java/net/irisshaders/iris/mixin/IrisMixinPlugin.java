package net.irisshaders.iris.mixin;

import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.*;

public class IrisMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> BACKEND_NEUTRAL_MIXINS = Set.of(
		"net.irisshaders.iris.mixin.GpuDeviceAccessor",
		"net.irisshaders.iris.mixin.LevelRendererAccessor",
		// Shader-pack option names, values and formatting are independent of the renderer.
		"net.irisshaders.iris.mixin.MixinClientLanguage",
		// Shader packs require bob/hurt/nausea in model-view on every backend.
		"net.irisshaders.iris.mixin.MixinModelViewBobbing",
		// Portals need Iris's full entity vertices and portal texture on both APIs.
		"net.irisshaders.iris.mixin.MixinTheEndPortalRenderer",
		"net.irisshaders.iris.mixin.MixinTheEndGatewayRenderer",
		// Shader-pack shadow maps replace vanilla entity shadow quads on both APIs.
		"net.irisshaders.iris.mixin.MixinEntityRenderDispatcher",
		"net.irisshaders.iris.mixin.MixinBlockStateBehavior",
		"net.irisshaders.iris.mixin.MixinBiome",
		"net.irisshaders.iris.mixin.MixinFogRenderer",
		"net.irisshaders.iris.mixin.MixinBiomes",
		"net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionArenas",
		"net.irisshaders.iris.compat.sodium.mixin.MixinArenaAggregator",
		"net.irisshaders.iris.mixin.vertices.block_rendering.MixinClientLevel",
		"net.irisshaders.iris.mixin.MixinOptions_Entrypoint",
		"net.irisshaders.iris.mixin.MixinExperimentalVulkanWarning",
		"net.irisshaders.iris.mixin.MixinItem"
	);

    public static boolean usingVulkan;

    static {
        var selection = IrisEarlyBackendSelection.read(IrisPlatformHelpers.getInstance().getGameDir(),
                IrisEarlyBackendSelection.launchArguments());
        usingVulkan = selection.vulkan();
        org.slf4j.LoggerFactory.getLogger("Iris/Backend").info("Selecting {} mixins from {}",
                usingVulkan ? "Vulkan" : "OpenGL", selection.source());
    }

	@Override
	public void onLoad(String mixinPackage) {

	}

	@Override
	public String getRefMapperConfig() {
		return "iris.refmap.json";
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (BACKEND_NEUTRAL_MIXINS.contains(mixinClassName)) {
			return true;
		}

        if (mixinClassName.contains("VKOnly") || mixinClassName.contains(".vulkan.")) return usingVulkan;
		return !usingVulkan;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//if (targetClassName.contains("LevelRenderer")) {
		//	targetClass.methods.forEach(m -> System.out.println(m.name + m.desc));
		//}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}
}
