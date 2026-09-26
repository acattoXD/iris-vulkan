package net.irisshaders.iris.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static net.irisshaders.iris.vulkan.IrisVulkanStaticVolume.Encoding;
import static net.irisshaders.iris.vulkan.IrisVulkanStaticVolume.Layout;

/** Exercises the production CPU helper and shaderc only; never creates a graphics device. */
public final class IrisVulkanStaticVolumeTest {
    private static final Pattern VOLUME = Pattern.compile(
            "(?m)^texture\\.(deferred|composite)\\.([A-Za-z0-9_.]+)\\s*=\\s*(\\S+)\\s+TEXTURE_3D\\s+"
                    + "(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s*$");
    private static int assertions;

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Usage: IrisVulkanStaticVolumeTest <photon.zip> <artifact-directory>");
        Path artifacts = Path.of(args[1]);
        Files.createDirectories(artifacts);
        List<Layout> layouts = photonPayloads(Path.of(args[0]));
        layouts.add(syntheticPayloads());
        invalidLayoutsAndPayloads();
        syntheticSamplingContracts(artifacts);
        int programs = compileSamplingHelpers(layouts, artifacts);
        System.out.println("IRIS_STATIC_VOLUME_PASS: " + assertions + " assertions, 4 actual Photon declarations, "
                + "RGB16F/R8/RGBA32F uploads, dimension/payload rejection, " + programs + " Vulkan SPIR-V compilations");
    }

    private static List<Layout> photonPayloads(Path archive) throws Exception {
        List<Layout> layouts = new ArrayList<>();
        try (var zip = new ZipFile(archive.toFile())) {
            String properties = readText(zip, "shaders/shaders.properties");
            var matcher = VOLUME.matcher(properties);
            int declarations = 0;
            boolean scattering = false, bubbly = false, swirley = false, composite = false;
            while (matcher.find()) {
                String stage = matcher.group(1), name = matcher.group(2), resource = matcher.group(3);
                int x = Integer.parseInt(matcher.group(5));
                int y = Integer.parseInt(matcher.group(6));
                int z = Integer.parseInt(matcher.group(7));
                Encoding encoding;
                int expectedBytes;
                if (resource.equals("image/atmosphere/scattering.dat")) {
                    require(stage.equals("deferred") && name.equals("depthtex0"), "Photon scattering stage and alias");
                    require(matcher.group(4).equals("RGB16F") && matcher.group(8).equals("RGB")
                            && matcher.group(9).equals("HALF_FLOAT"), "Photon scattering format");
                    require(x == 32 && y == 64 && z == 32, "Photon scattering dimensions");
                    encoding = Encoding.RGB16_FLOAT;
                    expectedBytes = 393216;
                    scattering = true;
                } else {
                    require(matcher.group(4).equals("R8") && matcher.group(8).equals("RED")
                            && matcher.group(9).equals("UNSIGNED_BYTE"), "Photon Worley normalized format");
                    require(x == 64 && y == 64 && z == 64, "Photon Worley dimensions");
                    require(resource.equals("image/worley_bubbly.dat") || resource.equals("image/worley_swirley.dat"),
                            "Unexpected Photon volume: " + resource);
                    encoding = Encoding.R8_UNORM;
                    expectedBytes = 262144;
                    bubbly |= stage.equals("deferred") && name.equals("colortex6.1") && resource.contains("bubbly");
                    swirley |= stage.equals("deferred") && name.equals("colortex7.1") && resource.contains("swirley");
                    composite |= stage.equals("composite") && name.equals("colortex0") && resource.contains("swirley");
                }
                var entry = zip.getEntry("shaders/" + resource);
                require(entry != null && entry.getSize() == expectedBytes, "Actual zip entry byte length: " + resource);
                byte[] payload;
                try (var stream = zip.getInputStream(entry)) { payload = stream.readAllBytes(); }
                require(payload.length == expectedBytes, "Uncompressed payload byte length: " + resource);
                Layout layout = IrisVulkanStaticVolume.layout(x, y, z, payload.length, encoding);
                checkLayout(layout, x, y, z, expectedBytes, encoding == Encoding.RGB16_FLOAT ? x * y * z * 8 : expectedBytes, encoding);
                checkUpload(layout, payload);
                String metadata = readText(zip, "shaders/" + resource + ".mcmeta");
                require(Pattern.compile("\"blur\"\\s*:\\s*true").matcher(metadata).find(), "Photon volume linear filtering: " + resource);
                require(Pattern.compile("\"clamp\"\\s*:\\s*" + (encoding == Encoding.RGB16_FLOAT)).matcher(metadata).find(),
                        "Photon scattering clamps, Worley repeats: " + resource);
                System.out.println("Photon " + stage + "." + name + ": " + expectedBytes + " -> " + layout.outputBytes() + " bytes");
                layouts.add(layout);
                ++declarations;
            }
            require(declarations == 4 && scattering && bubbly && swirley && composite, "All four actual Photon raw 3D declarations");
        }
        return layouts;
    }

    private static Layout syntheticPayloads() {
        // Keep NaNs, infinities, signed zero and subnormals bit-for-bit: conversion must not round RGB half data.
        short[] halves = {(short) 0x0000, (short) 0x8000, (short) 0x0001, (short) 0x3c00, (short) 0xbc00,
                (short) 0x7bff, (short) 0x7c00, (short) 0xfc00, (short) 0x7e21, (short) 0x0400, (short) 0x3555, (short) 0x83ff};
        ByteBuffer halfPayload = ByteBuffer.allocate(halves.length * 2).order(ByteOrder.nativeOrder());
        for (short value : halves) halfPayload.putShort(value);
        checkUpload(IrisVulkanStaticVolume.layout(2, 1, 2, halfPayload.capacity(), Encoding.RGB16_FLOAT), halfPayload.array());

        byte[] red = new byte[256];
        for (int i = 0; i < red.length; ++i) red[i] = (byte) i;
        Layout redLayout = IrisVulkanStaticVolume.layout(8, 4, 8, red.length, Encoding.R8_UNORM);
        checkUpload(redLayout, red);
        ByteBuffer redUpload = IrisVulkanStaticVolume.upload(redLayout, red);
        require(Byte.toUnsignedInt(redUpload.get(0)) == 0 && Byte.toUnsignedInt(redUpload.get(128)) == 128
                && Byte.toUnsignedInt(redUpload.get(255)) == 255, "R8 preserves normalized storage bytes across signed byte boundary");

        int[] floats = {0x00000000, 0x80000000, 0x00000001, 0x3f800000, 0xbf800000, 0x7f800000,
                0xff800000, 0x7fc12345, 0x00800000, 0x7f7fffff, 0x3eaaaaab, 0x80000001,
                0x40000000, 0x40800000, 0x41000000, 0x41800000};
        ByteBuffer floatPayload = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.nativeOrder());
        for (int value : floats) floatPayload.putInt(value);
        Layout floatLayout = IrisVulkanStaticVolume.layout(2, 2, 1, floatPayload.capacity(), Encoding.RGBA32_FLOAT);
        checkLayout(floatLayout, 2, 2, 1, 64, 64, Encoding.RGBA32_FLOAT);
        checkUpload(floatLayout, floatPayload.array());
        for (Encoding encoding : Encoding.values()) {
            int bytes = switch (encoding) { case RGB16_FLOAT -> 6; case R8_UNORM -> 1; case RGBA32_FLOAT -> 16; };
            Layout single = IrisVulkanStaticVolume.layout(1, 1, 1, bytes, encoding);
            checkUpload(single, new byte[bytes]);
        }
        return floatLayout;
    }

    private static void checkLayout(Layout layout, int x, int y, int z, int input, int output, Encoding encoding) {
        require(layout.width() == x && layout.height() == y && layout.depth() == z, "Logical volume dimensions");
        require(layout.atlasHeight() == y * z, "Flattened atlas keeps Y slices in Z order");
        require(layout.inputBytes() == input && layout.outputBytes() == output && layout.encoding() == encoding,
                "Upload layout format and byte counts");
    }

    private static void checkUpload(Layout layout, byte[] payload) {
        byte[] before = payload.clone();
        ByteBuffer uploaded = IrisVulkanStaticVolume.upload(layout, payload);
        require(uploaded.position() == 0 && uploaded.remaining() == layout.outputBytes(), "Upload buffer is ready to read");
        ByteBuffer output = uploaded.duplicate().order(ByteOrder.nativeOrder());
        if (layout.encoding() == Encoding.RGB16_FLOAT) {
            for (int voxel = 0; voxel < payload.length / 6; ++voxel) {
                for (int channelByte = 0; channelByte < 6; ++channelByte) {
                    require(output.get(voxel * 8 + channelByte) == payload[voxel * 6 + channelByte], "RGB half bits and voxel order preserved");
                }
                require(output.getShort(voxel * 8 + 6) == (short) 0x3c00, "Expanded alpha is exactly half-float one");
            }
        } else {
            require(layout.outputBytes() == payload.length, "Unexpanded format byte length");
            for (int i = 0; i < payload.length; ++i) require(output.get(i) == payload[i], "Raw byte and voxel order preserved");
        }
        require(java.util.Arrays.equals(payload, before), "Upload does not mutate pack bytes");
    }

    private static void invalidLayoutsAndPayloads() {
        for (Encoding encoding : Encoding.values()) {
            int bytes = switch (encoding) { case RGB16_FLOAT -> 6; case R8_UNORM -> 1; case RGBA32_FLOAT -> 16; };
            for (int dimension = 0; dimension < 3; ++dimension) {
                int axis = dimension;
                for (int invalid : new int[] {0, -1, Integer.MIN_VALUE}) {
                    rejects(() -> IrisVulkanStaticVolume.layout(axis == 0 ? invalid : 1, axis == 1 ? invalid : 1,
                            axis == 2 ? invalid : 1, bytes, encoding), "Nonpositive volume dimension");
                }
            }
            rejects(() -> IrisVulkanStaticVolume.layout(1, 1, 1, bytes - 1, encoding), "Truncated input rejected");
            rejects(() -> IrisVulkanStaticVolume.layout(1, 1, 1, bytes + 1, encoding), "Trailing bytes rejected");
            rejects(() -> IrisVulkanStaticVolume.layout(1, 1, 1, -1, encoding), "Negative byte count rejected");
            rejects(() -> IrisVulkanStaticVolume.layout(1, 46341, 46341, 0, encoding), "Flattened atlas height overflow rejected");
            rejects(() -> IrisVulkanStaticVolume.layout(46341, 46341, 1, 0, encoding), "Voxel count overflow rejected");
            rejects(() -> IrisVulkanStaticVolume.layout(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, encoding),
                    "Combined dimensions cannot overflow through long arithmetic");
            Layout layout = IrisVulkanStaticVolume.layout(1, 1, 1, bytes, encoding);
            rejects(() -> IrisVulkanStaticVolume.upload(layout, new byte[bytes - 1]), "Upload rejects truncated payload");
            rejects(() -> IrisVulkanStaticVolume.upload(layout, new byte[bytes + 1]), "Upload rejects trailing payload");
        }
        rejects(() -> IrisVulkanStaticVolume.layout(268435456, 1, 1, 1610612736, Encoding.RGB16_FLOAT),
                "RGB expansion must reject output overflow even when input byte count fits");
    }

    private static int compileSamplingHelpers(List<Layout> layouts, Path artifacts) throws Exception {
        int count = 0;
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        require(compiler != 0 && options != 0, "CPU shaderc initialized");
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            for (int index = 0; index < layouts.size(); ++index) {
                for (boolean linear : new boolean[] {false, true}) {
                    for (boolean clamp : new boolean[] {false, true}) {
                        // Include two independent volumes in one shader to catch colliding generated helper names.
                        String functions = IrisVulkanStaticVolume.samplerFunction("customtex0", layouts.get(index), linear, clamp)
                                + IrisVulkanStaticVolume.samplerFunction("customtex1", layouts.get(index), !linear, !clamp);
                        for (boolean vertex : new boolean[] {false, true}) {
                            String stem = "volume-" + index + "-" + (linear ? "linear" : "nearest") + "-"
                                    + (clamp ? "clamp" : "repeat") + (vertex ? ".vert" : ".frag");
                            String source = "#version 450 core\n"
                                    + "layout(set=0,binding=0) uniform sampler2D customtex0;\n"
                                    + "layout(set=0,binding=1) uniform sampler2D customtex1;\n"
                                    + (vertex ? "layout(location=0) in vec3 inputCoord;\n" : "layout(location=0) in vec3 inputCoord;\nlayout(location=0) out vec4 color;\n")
                                    + functions + "\nvoid main() {\n"
                                    + "vec4 value = iris_vulkan_sample3d_customtex0(inputCoord) + iris_vulkan_sample3d_customtex1(inputCoord);\n"
                                    + "value += iris_vulkan_fetch3d_customtex0(ivec3(-1,0,1));\n"
                                    + (vertex ? "gl_Position = value;\n" : "color = value;\n") + "}\n";
                            Files.writeString(artifacts.resolve(stem + ".glsl"), source);
                            long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                                    vertex ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader, stem, "main", options);
                            require(result != 0, "shaderc returned a result for " + stem);
                            try {
                                require(Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success,
                                        stem + ": " + Shaderc.shaderc_result_get_error_message(result));
                                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                                require(spirv != null && spirv.remaining() > 20, "Nonempty SPIR-V: " + stem);
                                byte[] binary = new byte[spirv.remaining()];
                                spirv.get(binary);
                                Files.write(artifacts.resolve(stem + ".spv"), binary);
                                ++count;
                            } finally { Shaderc.shaderc_result_release(result); }
                        }
                    }
                }
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        return count;
    }

    private static void syntheticSamplingContracts(Path artifacts) throws Exception {
        Layout layout = IrisVulkanStaticVolume.layout(2, 2, 2, 8, Encoding.R8_UNORM);
        // The CPU reference evaluates 3D filter weights independently of the emitted row/mix tree.
        // Source contracts tie that reference to the generated operations; these are not GPU executions.
        StringBuilder goldens = new StringBuilder("filter,address,x,y,z,expected,reference\n");
        for (boolean linear : new boolean[] {false, true}) {
            for (boolean clamp : new boolean[] {false, true}) {
                String generated = IrisVulkanStaticVolume.samplerFunction("fixture", layout, linear, clamp);
                require(generated.contains("ivec2(texel.x, texel.y + texel.z * size.y)"), "Atlas lookup uses logical row and slice indices");
                require(generated.contains(clamp ? "clamp(texel, ivec3(0), size - ivec3(1))"
                        : "((texel % size) + size) % size"), "Each axis clamps or wraps negative indices");
                require(generated.contains(clamp ? "clamp(coord, vec3(0.0), vec3(1.0))" : "fract(coord)"),
                        "Each normalized coordinate is bounded before integer conversion");
                require(!generated.contains("texture("), "Atlas filtering does not leak between adjacent logical slices");
                if (linear) {
                    require(generated.contains("unitCoord * vec3(size) - vec3(0.5)")
                            && generated.contains("ivec3 base = ivec3(floor(position))")
                            && generated.contains("vec3 weight = fract(position)"), "Linear filter uses texel centers and floor for negative positions");
                    for (int z = 0; z < 2; ++z) for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
                        require(generated.contains("iris_vulkan_fetch3d_fixture(base + ivec3(" + x + ", " + y + ", " + z + "))"),
                                "Linear filter fetches each of eight independent logical neighbors");
                    }
                    require(generated.contains("mix(row00, row01, weight.y)")
                            && generated.contains("mix(row10, row11, weight.y), weight.z)"), "Linear filter interpolates Y then Z after X");
                } else {
                    require(generated.contains("iris_vulkan_fetch3d_fixture(ivec3(floor(unitCoord * vec3(size))))"),
                            "Nearest selects one texel on all three axes, including Z");
                    require(!generated.contains("mix(") && !generated.contains("weight"), "Nearest cannot blend slices");
                }
                sampleGolden(goldens, linear, clamp, .25, .25, .25, 0);
                sampleGolden(goldens, linear, clamp, .75, .75, .75, 7);
                sampleGolden(goldens, linear, clamp, -.25, -.25, -.25, clamp ? 0 : 7);
                sampleGolden(goldens, linear, clamp, 1.25, 1.25, 1.25, clamp ? 7 : 0);
                sampleGolden(goldens, linear, clamp, 0, 0, 0, linear && !clamp ? 3.5 : 0);
                sampleGolden(goldens, linear, clamp, 1, 1, 1, clamp ? 7 : linear ? 3.5 : 0);
                sampleGolden(goldens, linear, clamp, 0, .25, .25, linear && !clamp ? .5 : 0);
                sampleGolden(goldens, linear, clamp, .25, 0, .25, linear && !clamp ? 1 : 0);
                sampleGolden(goldens, linear, clamp, .25, .25, 0, linear && !clamp ? 2 : 0);
                sampleGolden(goldens, linear, clamp, .25, .25, .49, linear ? 1.92 : 0);
                sampleGolden(goldens, linear, clamp, .25, .25, .51, linear ? 2.08 : 4);
            }
        }
        Files.writeString(artifacts.resolve("sampling-goldens.csv"), goldens);
        System.out.println("Sampling contracts: 44 synthetic center/edge/negative/seam/nearest-Z golden cases; CPU reference plus generated GLSL structure");
    }

    private static void sampleGolden(StringBuilder csv, boolean linear, boolean clamp, double x, double y, double z, double expected) {
        double actual = referenceSample(linear, clamp, new double[] {x, y, z});
        require(Math.abs(actual - expected) < 1e-9, "Synthetic 3D sampling golden: " + x + "," + y + "," + z);
        csv.append(linear ? "linear" : "nearest").append(',').append(clamp ? "clamp" : "repeat").append(',')
                .append(x).append(',').append(y).append(',').append(z).append(',').append(expected).append(',').append(actual).append('\n');
    }

    private static double referenceSample(boolean linear, boolean clamp, double[] coordinates) {
        double[] position = new double[3];
        for (int axis = 0; axis < 3; ++axis) position[axis] = coordinates[axis] * 2 - (linear ? .5 : 0);
        double sum = 0;
        for (int corner = 0; corner < (linear ? 8 : 1); ++corner) {
            int[] voxel = new int[3];
            double weight = 1;
            for (int axis = 0; axis < 3; ++axis) {
                int base = (int) Math.floor(position[axis]);
                int side = linear ? (corner >> axis) & 1 : 0;
                if (linear) {
                    double fraction = position[axis] - base;
                    weight *= side == 0 ? 1 - fraction : fraction;
                }
                int addressed = base + side;
                voxel[axis] = clamp ? Math.max(0, Math.min(1, addressed)) : Math.floorMod(addressed, 2);
            }
            sum += weight * (voxel[0] + 2 * voxel[1] + 4 * voxel[2]);
        }
        return sum;
    }

    private static String readText(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        require(entry != null, "Archive entry exists: " + name);
        try (var stream = zip.getInputStream(entry)) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void rejects(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException | ArithmeticException expected) { ++assertions; return; }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        ++assertions;
        if (!condition) throw new AssertionError(message);
    }
}
