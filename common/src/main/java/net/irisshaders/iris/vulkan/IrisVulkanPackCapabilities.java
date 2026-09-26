package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Checks an already-preprocessed candidate pack without changing renderer state or allocating GPU resources. */
public final class IrisVulkanPackCapabilities {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern SAMPLER = Pattern.compile(
            "(?m)^\\h*(?:layout\\h*\\([^)]*\\)\\h*)?uniform\\h+(?:(?:lowp|mediump|highp)\\h+)*([iu]?sampler\\w+)\\h+(\\w+)\\h*((?:\\[[^]]*]\\h*)*);"
    );
    private static final Pattern STORAGE = Pattern.compile("(?m)^\\h*(?:layout\\s*\\(([^)]*)\\)\\s*)?"
        + "(?:(?:readonly|writeonly|coherent|volatile|restrict)\\s+)*buffer\\s+(\\w+)\\s*\\{");
    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");
    private static final Pattern IMAGE = Pattern.compile(
            "\\buniform\\h+(?:(?:readonly|writeonly|coherent|volatile|restrict|lowp|mediump|highp)\\h+)*([iu]?image\\w+)\\h+(\\w+)\\h*((?:\\[[^]]*]\\h*)*)"
    );
    private static final Set<String> SAMPLER_TYPES = Set.of("sampler2D", "sampler2DShadow", "isampler2D", "usampler2D");

    private IrisVulkanPackCapabilities() { }

    static boolean storageDevelopmentEnabled() {
        return IrisNativeVulkan.storageDevelopmentEnabled();
    }

    record StorageSupport(boolean enabled, Map<String, ImageInformation> images,
                          Map<String, ImageInformation> samplers, Set<Integer> buffers) {
        static final StorageSupport DISABLED = new StorageSupport(false, Map.of(), Map.of(), Set.of());
        StorageSupport {
            images = Map.copyOf(images);
            samplers = Map.copyOf(samplers);
            buffers = Set.copyOf(buffers);
        }
    }

    public record Unsupported(String feature, String program, String resource, String detail) { }

    public record Result(List<Unsupported> unsupported) {
        public Result { unsupported = List.copyOf(unsupported); }
        public boolean supported() { return unsupported.isEmpty(); }
        public String summary() {
            if (supported()) return "Native Vulkan resource preflight passed.";
            return "This shader configuration needs native Vulkan support for "
                    + String.join(", ", unsupported.stream().map(Unsupported::feature).distinct().toList()) + ".";
        }
        public void requireSupported() {
            if (!supported()) throw new UnsupportedOperationException(summary() + " " + unsupported);
        }
    }

    public static void requireSupported(ProgramSet programs) {
        inspect(programs).requireSupported();
    }

    public static Result inspect(ProgramSet programs) {
        LinkedHashSet<Unsupported> failures = new LinkedHashSet<>();
        var pack = programs.getPack();
        StorageSupport storage = inspectStorage(pack, storageDevelopmentEnabled(), failures);
        StorageSupport computeStorage = withColorImages(programs, storage, failures);
        collectCompute(failures, programs, programs.getSetup(), TextureStage.SETUP, computeStorage);
        collectCompute(failures, programs, programs.getShadowCompute(), TextureStage.GBUFFERS_AND_SHADOW, computeStorage);
        collectCompute(failures, programs, programs.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, computeStorage);
        for (ProgramId id : ProgramId.values()) {
            // DH programs are not used by the native main world renderer.
            if (id.getGroup() == ProgramGroup.Dh) continue;
            TextureStage stage = id == ProgramId.Final ? TextureStage.COMPOSITE_AND_FINAL : TextureStage.GBUFFERS_AND_SHADOW;
            programs.get(id).ifPresent(source -> inspectProgram(programs, source, stage, failures, storage));
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            TextureStage stage = switch (id) {
                case Setup -> TextureStage.SETUP;
                case Begin -> TextureStage.BEGIN;
                case ShadowComposite -> TextureStage.SHADOWCOMP;
                case Prepare -> TextureStage.PREPARE;
                case Deferred -> TextureStage.DEFERRED;
                case Composite -> TextureStage.COMPOSITE_AND_FINAL;
            };
            for (ProgramSource source : programs.getComposite(id)) {
                if (source != null && source.isValid()) {
                    if (id == ProgramArrayId.Setup || id == ProgramArrayId.ShadowComposite) {
                        failures.add(new Unsupported(id == ProgramArrayId.Setup ? "setup graphics" : "shadow-composite graphics",
                            source.getName(), "graphics", "The native scheduler executes compute in this phase; its graphics pass is not implemented"));
                    }
                    inspectProgram(programs, source, stage, failures, storage);
                }
            }
            for (ComputeSource[] group : programs.getCompute(id)) collectCompute(failures, programs, group, stage, computeStorage);
        }
        return new Result(new ArrayList<>(failures));
    }

    private static StorageSupport inspectStorage(ShaderPack pack, boolean enabled, Set<Unsupported> failures) {
        Map<String, ImageInformation> images = new LinkedHashMap<>();
        Map<String, ImageInformation> samplers = new LinkedHashMap<>();
        Set<Integer> buffers = new LinkedHashSet<>();
        for (ImageInformation image : pack.getIrisCustomImages()) {
            String unsupported = enabled ? storageImageFailure(image) : storageGateReason();
            if (unsupported != null) {
                failures.add(new Unsupported("shader storage images", "shaders.properties", image.name(), unsupported));
                continue;
            }
            if (images.putIfAbsent(image.name(), image) != null) {
                failures.add(new Unsupported("shader storage images", "shaders.properties", image.name(), "Duplicate storage image name"));
            }
            if (image.samplerName() != null && !image.samplerName().isBlank()
                && samplers.putIfAbsent(image.samplerName(), image) != null) {
                failures.add(new Unsupported("shader storage images", "shaders.properties", image.samplerName(), "Duplicate storage sampler name"));
            }
        }
        pack.getBufferObjects().forEach((index, buffer) -> {
            String unsupported = !enabled ? storageGateReason() : index < 0 ? "SSBO binding must be nonnegative"
                : buffer.size() <= 0 || buffer.size() > Long.MAX_VALUE - 3 ? "SSBO byte size must be positive and fit the native allocation size"
                : buffer.relative() && (!Float.isFinite(buffer.scaleX()) || !Float.isFinite(buffer.scaleY())
                    || buffer.scaleX() <= 0 || buffer.scaleY() <= 0) ? "Relative SSBO dimensions must be finite and positive"
                : !buffer.relative() && buffer.content() != null && buffer.content().length > buffer.size()
                    ? "Initial SSBO content exceeds its declared byte size" : null;
            if (unsupported == null) buffers.add(index);
            else failures.add(new Unsupported("shader storage buffers", "shaders.properties", "bufferObject." + index, unsupported));
        });
        if (enabled && (!images.isEmpty() || !buffers.isEmpty())) {
            var gpu = RenderSystem.tryGetDevice();
            if (gpu != null && (Object) gpu instanceof GpuDeviceAccessor accessor && accessor.getBackend() instanceof VulkanDevice device) {
                var features = IrisVulkanDeviceFeatures.enabled(device);
                for (String missing : features.missingForStorage(!pack.getBufferObjects().isEmpty())) {
                    failures.add(new Unsupported("Vulkan device storage features", "device", missing,
                        features.recorded() ? "The device did not advertise this optional feature at creation"
                            : "The storage-feature device creation hook has not run; restart with the native storage hook installed"));
                }
            }
        }
        return new StorageSupport(enabled, images, samplers, buffers);
    }

    static String storageImageFailure(ImageInformation image) {
        if (!image.isRelative() && image.target() != TextureType.TEXTURE_2D && image.target() != TextureType.TEXTURE_3D) {
            return "Native storage descriptors implement TEXTURE_2D and TEXTURE_3D; requested " + image.target();
        }
        if (image.isRelative()) {
            if (!Float.isFinite(image.relativeWidth()) || !Float.isFinite(image.relativeHeight())
                || image.relativeWidth() <= 0 || image.relativeHeight() <= 0) return "Relative image dimensions must be finite and positive";
        } else if (image.width() <= 0 || image.height() <= 0 || image.target() == TextureType.TEXTURE_3D && image.depth() <= 0) {
            return "Storage image dimensions must be positive";
        }
        try {
            IrisVulkanStorageResources.vkFormat(image.internalTextureFormat());
            IrisVulkanStorageResources.glslFormat(image.internalTextureFormat());
        } catch (IllegalArgumentException unsupported) {
            return unsupported.getMessage();
        }
        return null;
    }

    private static String storageGateReason() {
        return "Native shader storage requires iris.vulkan.storageDevelopment=true and the native worldDevelopment renderer";
    }

    private static void collectCompute(Set<Unsupported> failures, ProgramSet programs, ComputeSource[] sources,
                                       TextureStage stage, StorageSupport storage) {
        if (sources == null) return;
        for (ComputeSource source : sources) {
            if (source == null || !source.isValid()) continue;
            if (!storage.enabled()) {
                failures.add(new Unsupported("compute passes", source.getName(), source.getName(), storageGateReason()));
                continue;
            }
            source.getSource().ifPresent(code -> {
                inspectSource(programs, source.getName(), code, stage, failures, storage);
                // Reject incompatible image formats and opaque array/declaration
                // shapes at pack selection, rather than on the first lazy dispatch.
                try { IrisVulkanComputeCompiler.prepare(source); }
                catch (IllegalArgumentException | UnsupportedOperationException invalid) {
                    failures.add(new Unsupported("compute declarations", source.getName(), "source", invalid.getMessage()));
                }
            });
            try {
                IrisVulkanComputeCompiler.prepare(source);
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                failures.add(new Unsupported("compute resource bindings", source.getName(), source.getName(), unsupported.getMessage()));
            }
            var indirect = source.getIndirectPointer();
            if (indirect != null) {
                var buffer = programs.getPack().getBufferObjects().get(indirect.buffer());
                if (!storage.buffers().contains(indirect.buffer()) || indirect.offset() < 0 || (indirect.offset() & 3) != 0
                    || buffer != null && !buffer.relative() && (buffer.size() < 12 || indirect.offset() > buffer.size() - 12)) {
                    failures.add(new Unsupported("indirect compute dispatch", source.getName(), "bufferObject." + indirect.buffer(),
                        "Indirect dispatch needs an existing SSBO and an aligned, in-range 12-byte command"));
                }
            }
        }
    }

    private static void inspectProgram(ProgramSet programs, ProgramSource source, TextureStage stage, Set<Unsupported> failures, StorageSupport storage) {
        if (source.getGeometrySource().isPresent()) failures.add(new Unsupported("geometry shaders", source.getName(), "geometry", "Native pack geometry stages are not implemented"));
        if (source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) failures.add(new Unsupported("tessellation shaders", source.getName(), "tessellation", "Native pack tessellation stages are not implemented"));
        source.getVertexSource().ifPresent(code -> inspectSource(programs, source.getName(), code, stage, failures, storage));
        source.getFragmentSource().ifPresent(code -> inspectSource(programs, source.getName(), code, stage, failures, storage));
    }

    private static void inspectSource(ProgramSet programs, String program, String source, TextureStage stage, Set<Unsupported> failures, StorageSupport storage) {
        String code = cleaned(source);
        var pack = programs.getPack();
        failures.addAll(sourceRequirements(program, code, name -> IrisVulkanCustomTextures.supportsStaticVolume(pack, stage, name,
            programs.getPackDirectives().getTextureMap()), storage));
        var sampler = SAMPLER.matcher(code);
        while (sampler.find()) {
            String name = sampler.group(2);
            var stageTextures = pack.getCustomTextureDataMap().get(stage);
            boolean custom = pack.getIrisCustomTextureDataMap().containsKey(name) || stageTextures != null && stageTextures.containsKey(name);
            if (custom && !IrisVulkanCustomTextures.supports(pack, stage, name)) {
                var data = stageTextures != null && stageTextures.containsKey(name) ? stageTextures.get(name) : pack.getIrisCustomTextureDataMap().get(name);
                failures.add(new Unsupported("custom texture bindings", program, name, IrisVulkanCustomTextures.unsupportedReason(data)));
            }
        }
    }

    /** Pure shader-source check shared with offline regression tests. */
	private static StorageSupport withColorImages(ProgramSet programs, StorageSupport storage, Set<Unsupported> failures) {
		if (!storage.enabled()) return storage;
		Map<String, ImageInformation> images = new LinkedHashMap<>(storage.images());
		Map<String, ImageInformation> builtinImages = new LinkedHashMap<>(IrisVulkanColorImages.declarations(programs));
		builtinImages.putAll(IrisVulkanColorImages.graphicsDeclarations(programs));
		for (ImageInformation information : builtinImages.values()) {
			String unsupported = storageImageFailure(information);
			if (unsupported == null) {
				try { unsupported = IrisVulkanColorImages.formatFailure(information.internalTextureFormat()); }
				catch (IllegalArgumentException | UnsupportedOperationException failure) { unsupported = failure.getMessage(); }
			}
			if (unsupported != null) failures.add(new Unsupported("render-target storage images", "compute", information.name(), unsupported));
			else images.putIfAbsent(information.name(), information);
		}
		return new StorageSupport(true, images, storage.samplers(), storage.buffers());
	}

    static List<Unsupported> sourceRequirements(String program, String source, Predicate<String> supportedStaticVolume) {
        return sourceRequirements(program, source, supportedStaticVolume, StorageSupport.DISABLED);
    }

    static List<Unsupported> sourceRequirements(String program, String source, Predicate<String> supportedStaticVolume, StorageSupport supported) {
        String code = cleaned(source);
        List<Unsupported> failures = new ArrayList<>();
        var storage = STORAGE.matcher(code);
        while (storage.find()) {
            String name = storage.group(2);
            if (!supported.enabled()) {
                failures.add(new Unsupported("shader storage buffers", program, name, storageGateReason()));
                continue;
            }
            var binding = BINDING.matcher(storage.group(1) == null ? "" : storage.group(1));
            boolean declared = false;
            if (binding.find()) {
                try { declared = supported.buffers().contains(Integer.parseInt(binding.group(1))); }
                catch (NumberFormatException ignored) { }
            }
            if (!declared) failures.add(new Unsupported("shader storage buffers", program, name,
                "SSBO needs an explicit binding matching a declared bufferObject index"));
        }
        var image = IMAGE.matcher(code);
        while (image.find()) {
            String type = image.group(1), name = image.group(2);
            if (!image.group(3).isBlank()) {
                failures.add(new Unsupported("storage image arrays", program, name, "Storage image descriptor arrays are not implemented"));
            } else if (!supported.enabled() || !matchesImageType(supported.images().get(name), type, false)) {
                failures.add(new Unsupported("shader storage images", program, name,
                    supported.enabled() ? type + " has no matching declared native image format and dimensionality" : storageGateReason()));
            }
        }
        var sampler = SAMPLER.matcher(code);
        while (sampler.find()) {
            String type = sampler.group(1), name = sampler.group(2);
            if (!sampler.group(3).isBlank()) {
                failures.add(new Unsupported("sampler arrays", program, name, type + sampler.group(3)));
            } else if (supported.enabled() && supported.samplers().containsKey(name)
                && !matchesImageType(supported.samplers().get(name), type, true)) {
                failures.add(new Unsupported("additional sampler types", program, name, type + " does not match its declared storage image"));
            } else if (!SAMPLER_TYPES.contains(type) && !(type.equals("sampler3D") && supportedStaticVolume.test(name))
                && !(supported.enabled() && matchesImageType(supported.samplers().get(name), type, true))) {
                failures.add(new Unsupported("additional sampler types", program, name, type + " is not represented by native pack bindings"));
            }
        }
        return failures;
    }

    private static boolean matchesImageType(ImageInformation information, String type, boolean sampler) {
        if (information == null || storageImageFailure(information) != null) return false;
        String prefix = switch (information.internalTextureFormat().getShaderDataType()) {
            case FLOAT -> "";
            case INT -> "i";
            case UINT -> "u";
        };
        String dimensions = !information.isRelative() && information.target() == TextureType.TEXTURE_3D ? "3D" : "2D";
        return type.equals(prefix + (sampler ? "sampler" : "image") + dimensions);
    }

    private static String cleaned(String source) {
        return COMMENTS.matcher(IrisVulkanShaderPruning.removeUnusedUniforms(source)).replaceAll("");
    }
}
