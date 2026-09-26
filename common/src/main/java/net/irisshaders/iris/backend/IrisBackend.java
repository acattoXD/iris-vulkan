package net.irisshaders.iris.backend;

import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;

public final class IrisBackend {
	private IrisBackend() {
	}

	public static GpuDeviceBackend getBackend(GpuDevice device) {
		return ((GpuDeviceAccessor) device).getBackend();
	}

	public static boolean isOpenGl(GpuDevice device) {
		return getBackend(device) instanceof GlDevice;
	}

	public static boolean isVulkan(GpuDevice device) {
		return getBackend(device) instanceof VulkanDevice;
	}
}
