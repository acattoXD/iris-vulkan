package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Actual 26.2 baked-quad producer bytes: normal loss in BLOCK and preservation in a matching extended layout. */
public final class IrisVulkanMovingBlockContractTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        VertexFormat original = RenderPipelines.SOLID_BLOCK.getVertexFormatBinding(0);
        check(original.equals(DefaultVertexFormat.BLOCK), "Real solid-block pipeline uses BLOCK");
        check(original.getVertexSize() == 28 && !original.contains("Normal"), "Actual 28-byte moving-block layout has no normal");
        check(RenderTypes.solidMovingBlock().pipeline() == RenderPipelines.SOLID_BLOCK, "Actual moving-block route is SOLID_BLOCK");
        check(RenderPipelines.CUTOUT_BLOCK.getVertexFormatBinding(0).equals(original)
            && RenderPipelines.TRANSLUCENT_BLOCK.getVertexFormatBinding(0).equals(original), "All three moving-block layers share the missing-normal contract");
        VertexFormat extended = IrisVulkanVertexFormats.BLOCK_WITH_NORMAL;
        VertexFormat alias = IrisVulkanShaderResources.aliasVanillaVertexFormat(extended);
        check(alias.getVertexSize() == 32 && alias.getElement("iris_Normal").offset() == 28, "Native alias preserves the extended byte layout");
        Field faceField = AbstractEndPortalRenderer.class.getDeclaredField("FACES"); faceField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Direction,List<Vector3fc>> faces = (Map<Direction,List<Vector3fc>>) faceField.get(null);
        for (Direction face : Direction.values()) {
            List<Vector3fc> vertices = faces.get(face);
            BakedQuad quad = new BakedQuad(vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(3),
                0, 0, 0, 0, face, new BakedQuad.MaterialInfo(null, ChunkSectionLayer.SOLID, null, -1, true, 0));
            byte[] baseline = produce(original, quad), capable = produce(extended, quad);
            check(baseline.length == 112 && capable.length == 128, "Real producer uses four correctly sized vertices per face");
            for (int vertex = 0; vertex < 4; vertex++) {
                for (int byteIndex = 0; byteIndex < 28; byteIndex++)
                    check(baseline[vertex * 28 + byteIndex] == capable[vertex * 32 + byteIndex], "Existing position/color/UV/light bytes remain identical");
                int start = vertex * 32 + 28;
                check(capable[start] == face.getStepX() * 127 && capable[start + 1] == face.getStepY() * 127
                    && capable[start + 2] == face.getStepZ() * 127, "Actual putBakedQuad emits the correct face normal when a slot exists");
                int squaredLength = capable[start] * capable[start] + capable[start + 1] * capable[start + 1] + capable[start + 2] * capable[start + 2];
                check(squaredLength == 127 * 127, "Every emitted geometric normal is nonzero and normalizable");
            }
        }
        Method fallback = IrisVulkanShaderResources.class.getDeclaredMethod("defaultVertexInputExpression", String.class, String.class, String.class);
        fallback.setAccessible(true);
        System.out.println("CURRENT_MISSING_NORMAL_EXPRESSION=" + fallback.invoke(null, "iris_Normal", "vec3", null));
        System.out.println("MOVING_BLOCK_CONTRACT_PASS: " + checks + " actual producer-byte checks; BLOCK loses normals while a32-byte layout preserves six face normals without altering existing attributes. This audits the producer contract, not a rendered-image fix.");
    }
    private static byte[] produce(VertexFormat format, BakedQuad quad) {
        try (ByteBufferBuilder staging = new ByteBufferBuilder(128)) {
            BufferBuilder builder = new BufferBuilder(staging, PrimitiveTopology.QUADS, format);
            QuadInstance instance = new QuadInstance(); instance.setColor(0xffbda16f); instance.setLightCoords(0x00f000a0);
            builder.putBakedQuad(new PoseStack().last(), quad, instance);
            try (var mesh = builder.buildOrThrow()) {
                ByteBuffer buffer = mesh.vertexBuffer().order(ByteOrder.nativeOrder()); byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
            }
        }
    }
    private static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
}
