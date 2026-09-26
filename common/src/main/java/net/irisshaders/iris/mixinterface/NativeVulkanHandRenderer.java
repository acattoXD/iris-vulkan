package net.irisshaders.iris.mixinterface;

/** Submit the native solid hand before the shader pack's deferred lighting. */
public interface NativeVulkanHandRenderer {
	void iris$renderSolidHandBeforeTranslucents();
}
