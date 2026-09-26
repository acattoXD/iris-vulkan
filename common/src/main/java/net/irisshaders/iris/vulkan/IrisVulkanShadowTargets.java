package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native attachments for the pack's shadow namespace, independent of the main gbuffers. */
public final class IrisVulkanShadowTargets implements AutoCloseable {
	private static final Pattern COLOR_SAMPLER = Pattern.compile("\\bshadowcolor([0-7])\\b");
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
	private final PackShadowDirectives directives;
	private final int resolution;
	private final GpuTexture[] depths = new GpuTexture[2];
	private final GpuTextureView[] depthViews = new GpuTextureView[2];
	private final GpuTexture[] colors = new GpuTexture[PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS];
	private final GpuTextureView[] colorViews = new GpuTextureView[colors.length];
	private final Map<GpuTexture, GpuTextureView[]> mipViews = new java.util.IdentityHashMap<>();
	private final Map<GpuFormat, RenderPipeline> mipmapPipelines = new HashMap<>();
	private boolean initialized;
	private boolean closed;

	public IrisVulkanShadowTargets(ProgramSet programs) {
		this.directives = programs.getPackDirectives().getShadowDirectives();
		this.resolution = directives.getResolution();
		if (resolution <= 0) {
			throw new IllegalArgumentException("Shadow map resolution must be positive: " + resolution);
		}
		try {
			for (int i = 0; i < depths.length; i++) {
				int index = i;
				PackShadowDirectives.DepthSamplingSettings settings = directives.getDepthSamplingSettings().get(i);
				depths[i] = RenderSystem.getDevice().createTexture(() -> "Iris native shadowtex" + index,
					USAGE, GpuFormat.D32_FLOAT, resolution, resolution, 1, settings.getMipmap() ? mipLevels() : 1);
				depthViews[i] = RenderSystem.getDevice().createTextureView(depths[i]);
			}
			for (int i : requiredColorTargets(programs)) {
				PackShadowDirectives.SamplingSettings settings = colorSettings(i);
				GpuFormat format = IrisVulkanTargetFormat.resolve(settings.getFormat(), GpuFormat.RGBA8_UNORM, i);
				colors[i] = RenderSystem.getDevice().createTexture(() -> "Iris native shadowcolor" + i,
					USAGE, format, resolution, resolution, 1, settings.getMipmap() ? mipLevels() : 1);
				colorViews[i] = RenderSystem.getDevice().createTextureView(colors[i]);
			}
		} catch (RuntimeException exception) {
			close();
			throw exception;
		}
	}

	public int resolution() {
		return resolution;
	}

	public void beginFrame(CommandEncoder encoder) {
		checkOpen();
		encoder.clearDepthTexture(depths[0], 1.0);
		if (!initialized) {
			encoder.clearDepthTexture(depths[1], 1.0);
		}
		for (int i = 0; i < colors.length; i++) {
			if (colors[i] != null && (!initialized || colorSettings(i).getClear())) {
				encoder.clearColorTexture(colors[i], colorSettings(i).getClearColor());
			}
		}
		initialized = true;
	}

	/** shadowtex1 excludes translucent casters; shadowtex0 includes them. */
	public void copyOpaqueDepth(CommandEncoder encoder) {
		checkOpen();
		encoder.copyTextureToTexture(depths[0], depths[1], 0, 0, 0, 0, 0, resolution, resolution);
	}

	public void finishFrame(CommandEncoder encoder) {
		for (GpuTexture texture : depths) {
			if (texture.getMipLevels() > 1) {
				generateMipmaps(encoder, texture);
			}
		}
		for (GpuTexture texture : colors) {
			if (texture != null && texture.getMipLevels() > 1) {
				generateMipmaps(encoder, texture);
			}
		}
	}

	private void generateMipmaps(CommandEncoder encoder, GpuTexture texture) {
		GpuTextureView[] levels = mipViews.computeIfAbsent(texture, source -> {
			GpuTextureView[] result = new GpuTextureView[source.getMipLevels()];
			for (int level = 0; level < result.length; level++) {
				result[level] = RenderSystem.getDevice().createTextureView(source, level, 1);
			}
			return result;
		});
		RenderPipeline pipeline = mipmapPipelines.computeIfAbsent(texture.getFormat(), this::mipmapPipeline);
		var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		for (int level = 1; level < levels.length; level++) {
			GpuTextureView destination = levels[level];
			int mip = level;
			RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Iris native shadow mip " + mip)
				.withRenderArea(new RenderPass.RenderArea(0, 0, destination.getWidth(0), destination.getHeight(0)));
			if (texture.getFormat().hasDepthAspect()) descriptor.withDepthAttachment(destination);
			else descriptor.withColorAttachment(destination);
			try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
				pass.setPipeline(IrisNativeVulkan.compiledFor(pipeline));
				pass.setUniform("InSampler", levels[level - 1], RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setIndexBuffer(indices.getBuffer(6), indices.type());
				pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		}
	}

	private RenderPipeline mipmapPipeline(GpuFormat format) {
		boolean depth = format.hasDepthAspect();
		String name = "native_shadow_mipmap_" + format.name().toLowerCase(java.util.Locale.ROOT);
		RenderPipeline original = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("iris", name))
			.withVertexShader("core/screenquad")
			.withFragmentShader("core/blit_screen")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.withColorTargetState(new ColorTargetState(Optional.empty(), depth ? GpuFormat.RGBA8_UNORM : format, ColorTargetState.WRITE_ALL))
			.build();
		RenderPipeline result = depth ? VKOnly_RenderPipelineAccessor.iris$create(original.getLocation(),
			original.getShaders(), original.getShaderDefines(), original.getBindGroupLayouts(),
			new ColorTargetState[0], new DepthStencilState(CompareOp.ALWAYS_PASS, true), original.getPolygonMode(), false,
			original.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new), original.getPrimitiveTopology(), original.pushConstantSize(), original.getSortKey()) : original;
		String vertex = """
			#version 450 core
			in vec3 Position;
			in vec2 UV0;
			out vec2 texCoord;
			void main() { gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0); texCoord = UV0; }
			""";
		String fragment = depth ? """
			#version 450 core
			uniform sampler2D InSampler;
			in vec2 texCoord;
			void main() { gl_FragDepth = texture(InSampler, texCoord).r; }
			""" : """
			#version 450 core
			uniform sampler2D InSampler;
			in vec2 texCoord;
			layout(location = 0) out vec4 fragColor;
			void main() { fragColor = texture(InSampler, texCoord); }
			""";
		IrisNativeVulkan.registerCustomPipelineSource(result, name, vertex, fragment, false);
		return result;
	}

	public RenderPassDescriptor rewrite(RenderPassDescriptor descriptor, int[] drawBuffers) {
		checkOpen();
		if (drawBuffers.length > colors.length) {
			throw new IllegalArgumentException("Too many shadow color attachments: " + drawBuffers.length);
		}
		Set<Integer> unique = new TreeSet<>();
		RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(descriptor.label());
		for (int index : drawBuffers) {
			if (index < 0 || index >= colors.length || colorViews[index] == null || !unique.add(index)) {
				throw new IllegalArgumentException("Invalid shadow draw buffer " + index);
			}
			builder.withColorAttachment(colorViews[index]);
		}
		return builder.withDepthAttachment(depthViews[0])
			.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution)).build();
	}

	public boolean matches(List<RenderPassDescriptor.Attachment<java.util.Optional<org.joml.Vector4fc>>> attachments,
							 int[] drawBuffers) {
		if (closed || attachments == null || attachments.size() != drawBuffers.length) {
			return false;
		}
		for (int i = 0; i < drawBuffers.length; i++) {
			if (attachments.get(i) == null || attachments.get(i).textureView() != colorViews[drawBuffers[i]]) {
				return false;
			}
		}
		return true;
	}

	public List<GpuFormat> formats(int[] drawBuffers) {
		List<GpuFormat> formats = new ArrayList<>(drawBuffers.length);
		for (int index : drawBuffers) {
			formats.add(colors[index].getFormat());
		}
		return List.copyOf(formats);
	}

	public Binding binding(String sampler) {
		if (!initialized || closed) {
			return null;
		}
		int depth = switch (sampler) {
			case "shadow", "shadowtex0" -> 0;
			case "watershadow", "shadowtex1" -> 1;
			default -> -1;
		};
		if (depth >= 0) {
			return new Binding(depthViews[depth], sampler(directives.getDepthSamplingSettings().get(depth)));
		}
		if (sampler.equals("shadowcolor")) {
			sampler = "shadowcolor0";
		}
		Matcher color = COLOR_SAMPLER.matcher(sampler);
		if (color.matches()) {
			int index = Integer.parseInt(color.group(1));
			if (colorViews[index] != null) {
				return new Binding(colorViews[index], sampler(colorSettings(index)));
			}
		}
		return null;
	}

	private static GpuSampler sampler(PackShadowDirectives.SamplingSettings settings) {
		return RenderSystem.getSamplerCache().getClampToEdge(
			settings.getNearest() ? FilterMode.NEAREST : FilterMode.LINEAR, settings.getMipmap());
	}

	private PackShadowDirectives.SamplingSettings colorSettings(int index) {
		PackShadowDirectives.SamplingSettings settings = directives.getColorSamplingSettings().get(index);
		return settings == null ? new PackShadowDirectives.SamplingSettings() : settings;
	}

	private int mipLevels() {
		return 32 - Integer.numberOfLeadingZeros(resolution);
	}

	private static Set<Integer> requiredColorTargets(ProgramSet programs) {
		Set<Integer> targets = new TreeSet<>();
		// The legacy aliases are legal even when their uniforms are declared through macros.
		targets.add(0);
		targets.add(1);
		for (ProgramId id : ProgramId.values()) {
			programs.get(id).ifPresent(source -> collect(targets, source, id.getGroup() == ProgramGroup.Shadow));
		}
		for (ProgramArrayId id : ProgramArrayId.values()) {
			for (ProgramSource source : programs.getComposite(id)) {
				collect(targets, source, id == ProgramArrayId.ShadowComposite);
			}
		}
		return targets;
	}

	private static void collect(Set<Integer> targets, ProgramSource source, boolean shadowOutputs) {
		if (source == null || !source.isValid()) {
			return;
		}
		if (shadowOutputs) {
			for (int target : source.getDirectives().getDrawBuffers()) {
				if (target < 0 || target >= PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS) {
					throw new IllegalArgumentException("Shadow output outside 0..7: " + source.getName() + " -> " + target);
				}
				targets.add(target);
			}
		}
		for (String text : List.of(source.getVertexSource().orElse(""), source.getFragmentSource().orElse(""))) {
			Matcher matcher = COLOR_SAMPLER.matcher(text);
			while (matcher.find()) {
				targets.add(Integer.parseInt(matcher.group(1)));
			}
		}
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("Native shadow targets are closed");
		}
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		for (GpuTextureView[] views : mipViews.values()) for (GpuTextureView view : views) view.close();
		mipViews.clear();
		for (RenderPipeline pipeline : mipmapPipelines.values()) IrisNativeVulkan.unregisterCustomPipelineSource(pipeline);
		mipmapPipelines.clear();
		for (GpuTextureView view : depthViews) if (view != null) view.close();
		for (GpuTextureView view : colorViews) if (view != null) view.close();
		for (GpuTexture texture : depths) if (texture != null) texture.close();
		for (GpuTexture texture : colors) if (texture != null) texture.close();
	}

	public record Binding(GpuTextureView view, GpuSampler sampler) {
	}
}
