package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Compiles an externally exported actual Photon compute source; performs no GPU work. */
public final class PhotonColorImageCompile {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        String source = Files.readString(input);
        require(source.contains("colorimg4") && source.contains("colortex4"), "Actual source contains both aliases");
        var prepared = IrisVulkanComputeCompiler.prepareSource("photon/deferred4_a", source, Map.of("colorimg4", "rgba16f"));
        var storage = prepared.descriptors().stream().filter(d -> d.name().equals("colorimg4")).findFirst().orElseThrow();
        require(storage.kind() == IrisVulkanComputeCompiler.Kind.STORAGE_IMAGE && storage.type().equals("image2D")
                && storage.imageFormat().equals("rgba16f"), "Storage descriptor retains exact typed image format");
        require(prepared.descriptors().stream().anyMatch(d -> d.name().equals("colortex4")
                && d.kind() == IrisVulkanComputeCompiler.Kind.SAMPLED_IMAGE), "Sampled descriptor retained alongside storage alias");
        require(prepared.source().matches("(?s).*\\bimageStore\\s*\\(\\s*colorimg4\\b.*"), "Image writes retained");
        Files.writeString(output.resolve("deferred4_a.prepared.csh"), prepared.source());
        byte[] binary;
        int writes = 0, rgba16fStorageTypes = 0;
        try (var spirv = IrisVulkanComputeCompiler.compileSpirv(prepared)) {
            require(spirv.localSize().x == 256 && spirv.localSize().y == 1 && spirv.localSize().z == 1,
                    "Actual Photon workgroup shape is 256x1x1");
            var words = spirv.bytes().asIntBuffer();
            require(words.get(0) == 0x07230203, "SPIR-V magic");
            for (int offset = 5; offset < words.limit();) {
                int instruction = words.get(offset), count = instruction >>> 16, opcode = instruction & 65535;
                require(count > 0 && offset + count <= words.limit(), "Well-formed SPIR-V instruction");
                if (opcode == 99) writes++; // OpImageWrite
                if (opcode == 25 && count >= 9 && words.get(offset + 7) == 2 && words.get(offset + 8) == 2)
                    rgba16fStorageTypes++; // OpTypeImage, Sampled=2, ImageFormat=Rgba16f
                offset += count;
            }
            binary = new byte[spirv.bytes().remaining()]; spirv.bytes().duplicate().get(binary);
        }
        require(writes > 0 && rgba16fStorageTypes > 0, "Compiled shader contains storage image writes and Rgba16f image type");
        Files.write(output.resolve("deferred4_a.spv"), binary);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", true); result.put("source", input.toString()); result.put("source_sha256", hash(Files.readAllBytes(input)));
        result.put("prepared_sha256", hash(prepared.source().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        result.put("spirv_sha256", hash(binary)); result.put("spirv_bytes", binary.length); result.put("op_image_write_count", writes);
        result.put("rgba16f_storage_type_count", rgba16fStorageTypes); result.put("descriptors", prepared.descriptors());
        result.put("limits", "Actual exported source compiled by production prepareSource/compileSpirv and CPU shaderc; no Vulkan driver, GPU dispatch, rendered pixels, or live resource binding.");
        Files.writeString(output.resolve("photon-compile.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        System.out.println("PASS: actual Photon deferred4_a compiled to " + binary.length + " SPIR-V bytes with " + writes
                + " OpImageWrite instructions, rgba16f storage image, colortex4 sampler, and 256x1x1 local size");
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
