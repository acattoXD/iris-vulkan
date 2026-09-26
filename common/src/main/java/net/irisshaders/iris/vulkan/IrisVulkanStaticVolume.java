package net.irisshaders.iris.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Lossless upload layouts and single-level 3D sampling for a 2D slice atlas. */
final class IrisVulkanStaticVolume {
    enum Encoding {
        RGBA32_FLOAT(16, 16), RGB16_FLOAT(6, 8), R8_UNORM(1, 1);

        final int inputStride, outputStride;
        Encoding(int inputStride, int outputStride) {
            this.inputStride = inputStride;
            this.outputStride = outputStride;
        }
    }

    record Layout(int width, int height, int depth, int atlasHeight, int inputBytes, int outputBytes,
                  Encoding encoding) { }

    private IrisVulkanStaticVolume() { }

    static Layout layout(int width, int height, int depth, int actualBytes, Encoding encoding) {
        Objects.requireNonNull(encoding, "encoding");
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Static volume dimensions must be positive");
        }
        try {
            int atlasHeight = Math.multiplyExact(height, depth);
            long texels = Math.multiplyExact((long) width, atlasHeight);
            int inputBytes = Math.toIntExact(Math.multiplyExact(texels, encoding.inputStride));
            int outputBytes = Math.toIntExact(Math.multiplyExact(texels, encoding.outputStride));
            if (actualBytes != inputBytes) {
                throw new IllegalArgumentException("Static volume payload has " + actualBytes
                    + " bytes; expected exactly " + inputBytes);
            }
            return new Layout(width, height, depth, atlasHeight, inputBytes, outputBytes, encoding);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Static volume dimensions or upload byte count overflow", overflow);
        }
    }

    static ByteBuffer upload(Layout layout, byte[] source) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(source, "source");
        Layout checked = layout(layout.width(), layout.height(), layout.depth(), source.length, layout.encoding());
        if (!checked.equals(layout)) throw new IllegalArgumentException("Inconsistent static volume layout");
        ByteBuffer output = ByteBuffer.allocateDirect(layout.outputBytes()).order(ByteOrder.nativeOrder());
        if (layout.encoding() == Encoding.RGB16_FLOAT) {
            // Retain every authored half-float bit, including HDR values. Vulkan RGB16
            // texture support varies; RGBA16 is equivalent with the missing alpha set to one.
            for (int offset = 0; offset < source.length; offset += 6) {
                output.put(source, offset, 6).putShort((short) 0x3c00);
            }
        } else {
            output.put(source);
        }
        return output.flip();
    }

    static String samplerFunction(String sampler, Layout layout, boolean linear, boolean clamp) {
        if (sampler == null || !sampler.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid static volume sampler name");
        }
        Layout checked = layout(layout.width(), layout.height(), layout.depth(), layout.inputBytes(), layout.encoding());
        if (!checked.equals(layout)) throw new IllegalArgumentException("Inconsistent static volume layout");
        String size = "ivec3(" + layout.width() + ", " + layout.height() + ", " + layout.depth() + ")";
        String fetch = "iris_vulkan_fetch3d_" + sampler;
        String sample = "iris_vulkan_sample3d_" + sampler;
        StringBuilder glsl = new StringBuilder("\nvec4 ").append(fetch).append("(ivec3 texel) {\n")
            .append("    const ivec3 size = ").append(size).append(";\n")
            .append(clamp ? "    texel = clamp(texel, ivec3(0), size - ivec3(1));\n"
                : "    texel = ((texel % size) + size) % size;\n")
            .append("    return texelFetch(").append(sampler)
            .append(", ivec2(texel.x, texel.y + texel.z * size.y), 0);\n}\n")
            .append("vec4 ").append(sample).append("(vec3 coord) {\n")
            .append("    const ivec3 size = ").append(size).append(";\n")
            // Bound coordinates before converting to integers, including negative repeat
            // coordinates. Clamp/repeat belongs to all three logical axes, not atlas rows.
            .append(clamp ? "    vec3 unitCoord = clamp(coord, vec3(0.0), vec3(1.0));\n"
                : "    vec3 unitCoord = fract(coord);\n");
        if (!linear) {
            glsl.append("    return ").append(fetch).append("(ivec3(floor(unitCoord * vec3(size))));\n");
        } else {
            glsl.append("    vec3 position = unitCoord * vec3(size) - vec3(0.5);\n")
                .append("    ivec3 base = ivec3(floor(position));\n")
                .append("    vec3 weight = fract(position);\n");
            for (int z = 0; z < 2; z++) {
                for (int y = 0; y < 2; y++) {
                    glsl.append("    vec4 row").append(z).append(y).append(" = mix(")
                        .append(fetch).append("(base + ivec3(0, ").append(y).append(", ").append(z).append(")), ")
                        .append(fetch).append("(base + ivec3(1, ").append(y).append(", ").append(z)
                        .append(")), weight.x);\n");
                }
            }
            glsl.append("    return mix(mix(row00, row01, weight.y), mix(row10, row11, weight.y), weight.z);\n");
        }
        return glsl.append("}\n").toString();
    }
}
