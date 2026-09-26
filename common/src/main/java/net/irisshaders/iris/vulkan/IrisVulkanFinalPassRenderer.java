package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;

public final class IrisVulkanFinalPassRenderer {
	private final IrisNativeVulkan.ScreenPassMode mode;
	private final IrisVulkanScreenPassGraph graph;
	private final IrisVulkanScreenPassExecutor executor;
	private final FrameUpdateNotifier frameUpdateNotifier;
	private final CustomUniforms customUniforms;
	private final ProgramSet programs;
	private final IrisVulkanComputeExecutor compute;

	public IrisVulkanFinalPassRenderer(ProgramSet programSet) {
		this.programs = programSet;
		this.compute = IrisNativeVulkan.storageDevelopmentEnabled() ? new IrisVulkanComputeExecutor(programSet) : null;
		this.mode = IrisNativeVulkan.screenPassMode();
		if (this.mode == IrisNativeVulkan.ScreenPassMode.ALL && !IrisNativeVulkan.worldDevelopmentEnabled()) {
			var unsupported = IrisNativeVulkan.unsupportedActiveWorldPrograms(programSet);
			if (!unsupported.isEmpty()) {
				throw new UnsupportedOperationException("This native Vulkan prototype cannot render the selected pack's world programs: "
					+ String.join(", ", unsupported.keySet()) + ". Use OpenGL for this pack.");
			}
		}
		FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();
		CustomUniforms customUniforms = null;
		IrisVulkanScreenPassGraph graph = null;
		boolean registered = false;

		try {
			var pack = programSet.getPack();
			customUniforms = IrisVulkanUniformSnapshot.createCustomUniforms(programSet, frameUpdateNotifier);
			IrisVulkanUniformSnapshot.registerActiveCustomUniforms(customUniforms);
			registered = true;

			graph = IrisVulkanScreenPassPlanner.create(programSet);
			if (IrisNativeVulkan.worldDevelopmentEnabled()) {
				for (var node : graph.nodes()) {
					if (!node.ready()) throw new IllegalStateException("Required pack pass " + node.label() + " is unavailable: " + node.failureReason());
				}
			}
			this.graph = graph;
			this.executor = new IrisVulkanScreenPassExecutor(graph, mode, IrisNativeVulkan.selectedScreenPassLabel(), compute);
			this.frameUpdateNotifier = frameUpdateNotifier;
			this.customUniforms = customUniforms;
		} catch (RuntimeException | Error e) {
			if (registered) {
				IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(customUniforms);
			}
			if (graph != null) {
				graph.destroy();
			}
			throw e;
		}

		if (IrisNativeVulkan.worldDevelopmentEnabled() && graph.finalPass() == null) {
			Iris.logger.info("Shaderpack has no final pass; native Vulkan will present the world result from colortex0.");
		} else if (!graph.hasRunnablePasses()) {
			Iris.logger.info("Shaderpack has no supported native Vulkan screen passes; screen pass execution is disabled.");
		} else if (!graph.hasReadyFinalPass()) {
			Iris.logger.info("Shaderpack has no supported native Vulkan final pass; logical passes will fall back to colortex0.");
		}
	}

	public void render() {
		if (!IrisNativeVulkan.worldDevelopmentEnabled()) {
			frameUpdateNotifier.onNewFrame();
			customUniforms.beginFrame();
		}
		executor.render();
	}

	public void beginWorldFrame() {
		frameUpdateNotifier.onNewFrame();
		customUniforms.beginFrame();
		if (compute != null) {
			var backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend();
			if (!(backend instanceof VulkanDevice device)) throw new IllegalStateException("Native storage needs a Vulkan device");
			IrisVulkanStorageResources.ensureLoaded(device, programs.getPack(), width(), height(), Iris.getCurrentDimension());
			IrisVulkanStorageResources.beginFrame();
			compute.dispatchSetup(width(), height());
		}
		executor.beginWorldFrame();
		if (compute != null) compute.dispatchShadow(width(), height());
	}

	/** Shadow vertices fill the voxel atlas; flood propagation finishes before prepare/terrain sampling. */
	public void afterShadows() {
		if (compute != null) {
			long dispatchesBefore = compute.dispatchCount();
			int viewportWidth = width(), viewportHeight = height();
			for (int i = 0; i < ProgramArrayId.ShadowComposite.getNumPrograms(); ++i) {
				compute.dispatchStage(ProgramArrayId.ShadowComposite, i, viewportWidth, viewportHeight);
			}
			// A recorded dispatch already brackets its work with storage barriers.
			// With no dispatch, preserve the shadow->prepare/world dependency here.
			if (compute.dispatchCount() == dispatchesBefore) IrisVulkanStorageResources.barrier();
		}
		executor.renderPrepare();
	}
	public void renderDeferred() { executor.renderDeferred(); }
	public long computeDispatchCount() { return compute == null ? 0 : compute.dispatchCount(); }
	private static int width() { return Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getWidth(0); }
	private static int height() { return Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getHeight(0); }

	public void destroy() {
		try {
			executor.destroy();
			if (compute != null) compute.close();
		} finally {
			try {
				graph.destroy();
			} finally {
				IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(customUniforms);
			}
		}
	}

	public boolean hasRunnablePasses() {
		// World-only packs are valid: presentation copies their colortex0 even
		// when there are no post-processing programs. Invalid declared nodes
		// have already been rejected by the constructor above.
		return IrisNativeVulkan.worldDevelopmentEnabled() || graph.hasRunnablePasses();
	}

	public boolean requiresGbufferCapture() {
		return executor.requiresGbufferCapture();
	}
}
