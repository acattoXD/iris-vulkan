package net.irisshaders.iris.probe.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Keep per-draw diagnostics completely absent from ordinary/benchmark runs. */
public final class ProbeMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.endsWith(".ProbeEntityFacesDrawMixin")) return Boolean.getBoolean("iris.vulkan.probe.entityFaces");
        if (mixin.substring(mixin.lastIndexOf('.') + 1).startsWith("ProbePortal"))
            return Boolean.getBoolean("iris.vulkan.probe.portal");
        return !mixin.endsWith(".ProbeGlintDrawTraceMixin") || Boolean.getBoolean("iris.vulkan.probe.enchanted");
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
