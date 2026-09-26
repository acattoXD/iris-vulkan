package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.format.common.GlyphVertex;
import net.caffeinemc.mods.sodium.client.render.vertex.serializers.generated.VertexSerializerFactory;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.*;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Production format selection, source-byte conversion and transformed glyph normals; no GPU. */
public final class IrisVulkanGlyphNormalTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        layoutsAndWarmup();
        eligibility();
        for (VertexFormat target : List.of(IrisVulkanVertexFormats.GLYPH_WITH_NORMAL, IrisVulkanVertexFormats.GLYPH_SEE_THROUGH_WITH_NORMAL)) {
            for (Matrix4f transform : List.of(new Matrix4f(), new Matrix4f().rotateXYZ(.37f,.62f,-.21f).scale(.3f,.5f,.7f),
                    new Matrix4f().rotateXYZ(.17f,-.82f,.43f).scale(-.3f,.5f,.7f))) {
                glyph(target, transform, false);
                glyph(target, transform, true);
            }
        }
        suppliedBlockNormals();
        System.out.println("IRIS_NATIVE_NORMALS_PASS: " + checks + " checks; real Sodium glyph bytes, transformed/mirrored normals,28/32byte layouts, UV2absence, reallocation, suppliedBLOCKnormals, warmup and bypass/UI gates");
    }

    static void layoutsAndWarmup() throws Exception {
        check(IrisVulkanVertexFormats.BLOCK_WITH_NORMAL.getVertexSize()==32, "BLOCK is32bytes");
        check(IrisVulkanVertexFormats.GLYPH_WITH_NORMAL.getVertexSize()==32, "Lightmapped text is32bytes");
        check(IrisVulkanVertexFormats.GLYPH_SEE_THROUGH_WITH_NORMAL.getVertexSize()==28, "See-through text is28bytes");
        check(!IrisVulkanVertexFormats.GLYPH_SEE_THROUGH_WITH_NORMAL.contains("UV2"), "See-through does not invent lightmap data");
        for (ShaderKey key : List.of(ShaderKey.TEXT,ShaderKey.TEXT_INTENSITY,ShaderKey.TEXT_BE,ShaderKey.TEXT_INTENSITY_BE,
                ShaderKey.HAND_TEXT,ShaderKey.HAND_TEXT_TRANSLUCENT,ShaderKey.HAND_TEXT_INTENSITY,ShaderKey.SHADOW_TEXT,ShaderKey.SHADOW_TEXT_INTENSITY)) {
            check(IrisVulkanVertexFormats.forShader(DefaultVertexFormat.POSITION_TEX_COLOR,PrimitiveTopology.QUADS,key)==IrisVulkanVertexFormats.GLYPH_SEE_THROUGH_WITH_NORMAL,"Actual glyph key receives see-through layout");
        }
        for (ShaderKey key : List.of(ShaderKey.SKY_TEXTURED,ShaderKey.SKY_TEXTURED_COLOR,ShaderKey.TEXTURED,ShaderKey.TEXTURED_COLOR,ShaderKey.WEATHER,ShaderKey.PARTICLES,ShaderKey.SODIUM_TERRAIN_SOLID)) {
            for (VertexFormat format : List.of(DefaultVertexFormat.POSITION_TEX_COLOR,DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR))
                check(IrisVulkanVertexFormats.forShader(format,PrimitiveTopology.QUADS,key)==format,"Non-glyph direct producers are untouched");
        }
        check(IrisVulkanVertexFormats.forShader(DefaultVertexFormat.BLOCK,PrimitiveTopology.QUADS,ShaderKey.SODIUM_TERRAIN_SOLID)==DefaultVertexFormat.BLOCK,"Sodium has its own layout");
        check(IrisVulkanVertexFormats.writer(DefaultVertexFormat.BLOCK,PrimitiveTopology.QUADS)==null,"Raw default builders are never silently enlarged");
        check(IrisVulkanVertexFormats.writer(DefaultVertexFormat.POSITION_TEX_COLOR,PrimitiveTopology.QUADS)==null,"Raw UI/textured builders stay unchanged");
        Method formats=IrisVulkanShaderResources.class.getDeclaredMethod("chooseVertexFormats",com.mojang.blaze3d.pipeline.RenderPipeline.class,ShaderKey.class);formats.setAccessible(true);
        VertexFormat[] prepared=(VertexFormat[])formats.invoke(null,RenderPipelines.TEXT_SEE_THROUGH,ShaderKey.TEXT);
        check(prepared[0].getVertexSize()==28 && prepared[0].contains("iris_Normal") && !prepared[0].contains("iris_UV2"),"Early warmup resolves stable see-through native layout");
        check(RenderPipelines.TEXT_SEE_THROUGH.getVertexFormatBinding(0).getVertexSize()==24,"Original vanilla pipeline remains24bytes");
        VertexFormat[] repeated=(VertexFormat[])formats.invoke(null,RenderPipelines.TEXT_SEE_THROUGH,ShaderKey.TEXT);
        check(repeated[0].equals(prepared[0]),"Deferred/cached replay resolves same layout");
        try {
            VertexSerializerFactory.generate(GlyphVertex.FORMAT,IrisVulkanVertexFormats.GLYPH_WITH_NORMAL,"iris/NormalNegativeControl");
            throw new AssertionError("Baseline Sodium serializer unexpectedly invented a normal");
        } catch(RuntimeException expected) {
            boolean missing=false;
            for(Throwable cause=expected;cause!=null;cause=cause.getCause()) missing |= String.valueOf(cause.getMessage()).contains("missing element");
            check(missing,"Real Sodium serializer reproduces missing-Normal failure: " + expected);
        }
    }

    static void eligibility() throws Exception {
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);Unsafe unsafe=(Unsafe)unsafeField.get(null);
        Iris.pipelineManager=null; check(!IrisVulkanVertexFormats.worldProducerActive(),"Startup without manager stays vanilla");
        Iris.pipelineManager=(PipelineManager)unsafe.allocateInstance(PipelineManager.class);
        Field pipeline=PipelineManager.class.getDeclaredField("pipeline");pipeline.setAccessible(true);
        for(Class<?> type:List.of(VanillaRenderingPipeline.class,IrisRenderingPipeline.class)) {
            pipeline.set(Iris.pipelineManager,unsafe.allocateInstance(type)); check(!IrisVulkanVertexFormats.worldProducerActive(),"Vanilla/OpenGL are untouched");
        }
        var nativePipeline=(NativeVulkanWorldRenderingPipeline)unsafe.allocateInstance(NativeVulkanWorldRenderingPipeline.class);
        pipeline.set(Iris.pipelineManager,nativePipeline);
        set(nativePipeline,"renderingWorld",false);set(nativePipeline,"mainBound",false);
        check(!IrisVulkanVertexFormats.worldProducerActive(),"Native menu/afterworld producer stays original");
        set(nativePipeline,"renderingWorld",true);
        check(!IrisVulkanVertexFormats.worldProducerActive(),"Offscreen/non-main pass does not get native-world layout");
        set(nativePipeline,"mainBound",true); check(IrisVulkanVertexFormats.worldProducerActive(),"Active native main world produces normal layout");
        ImmediateState.bypass=true;
        try {check(!IrisVulkanVertexFormats.worldProducerActive(),"Bypass preserves vanilla byte contract");}
        finally {ImmediateState.bypass=false;}
        set(nativePipeline,"renderingWorld",false);check(!IrisVulkanVertexFormats.worldProducerActive(),"World end restores UI producer behavior");
        pipeline.set(Iris.pipelineManager,null);check(!IrisVulkanVertexFormats.worldProducerActive(),"Pack unload restores original producer behavior");
    }

    static void glyph(VertexFormat target,Matrix4f transform,boolean split) throws Exception {
        ByteBuffer source=MemoryUtil.memAlloc(112);
        try(ByteBufferBuilder destination=new ByteBufferBuilder(1)) {
            float[][] corners={{0,0},{0,1},{1,1},{1,0}};
            for(int i=0;i<4;i++) {
                Vector3f position=transform.transformPosition(new Vector3f(corners[i][0],corners[i][1],0));
                GlyphVertex.put(MemoryUtil.memAddress(source)+i*28L,position.x,position.y,position.z,0xff624321,.15f+i*.1f,.65f-i*.1f,0x00f00090);
            }
            var copy=IrisVulkanVertexFormats.copyPlan(GlyphVertex.FORMAT,target);check(copy!=null,"Native copy bridges actual Sodium glyph input");
            var writer=IrisVulkanVertexFormats.writer(target,PrimitiveTopology.QUADS);
            int stride=target.getVertexSize();
            if(split) {
                // Two finalized individual records, then a bulk push. Every
                // reservation can relocate the real ByteBufferBuilder storage.
                for(int i=0;i<2;i++) {long pointer=destination.reserve(stride);copy.copy(MemoryUtil.memAddress(source)+i*28L,pointer,1);writer.record(base(destination),pointer,false);}
                long pointer=destination.reserve(stride*2);copy.copy(MemoryUtil.memAddress(source)+56,pointer,2);
                for(int i=0;i<2;i++)writer.record(base(destination),pointer+i*(long)stride,false);
            } else {
                long pointer=destination.reserve(stride*4);copy.copy(MemoryUtil.memAddress(source),pointer,4);
                for(int i=0;i<4;i++)writer.record(base(destination),pointer+i*(long)stride,false);
            }
            check(writer.completedVertices()==4,"All bulk/manual vertices recorded once");
            Vector3f expected=new Matrix3f(transform).invert().transpose().transform(new Vector3f(0,0,-1));
            if(new Matrix3f(transform).determinant()<0)expected.negate();expected.normalize();
            try(var result=destination.build()) {
                ByteBuffer bytes=result.byteBuffer().order(ByteOrder.nativeOrder());
                for(int i=0;i<4;i++) {
                    int offset=i*stride,normalOffset=target.getElement("Normal").offset();
                    Vector3f actual=new Vector3f(bytes.get(offset+normalOffset)/127f,bytes.get(offset+normalOffset+1)/127f,bytes.get(offset+normalOffset+2)/127f);
                    check(actual.isFinite() && actual.lengthSquared()>.95f,"Generated glyph normal is finite and nonzero");
                    check(actual.normalize().dot(expected)>.999f,"Glyph normal follows transformed/mirrored quad orientation");
                    for(var element:target.getElements()) {
                        if(element.name().equals("Normal"))continue;
                        var original=GlyphVertex.FORMAT.getElement(element.name());
                        for(int b=0;b<element.format().blockSize();b++)check(bytes.get(offset+element.offset()+b)==source.get(i*28+original.offset()+b),"Position/color/UV/light values survive named-attribute conversion");
                    }
                }
            }
        } finally {MemoryUtil.memFree(source);}
    }

    static void suppliedBlockNormals() throws Exception {
        VertexFormat format=IrisVulkanVertexFormats.BLOCK_WITH_NORMAL;
        ByteBuffer source=MemoryUtil.memCalloc(128),target=MemoryUtil.memCalloc(128);
        try {
            for(int i=0;i<4;i++) {long p=MemoryUtil.memAddress(source)+i*32L;MemoryUtil.memPutFloat(p,i==0||i==1?0:1);MemoryUtil.memPutFloat(p+4,i==1||i==2?1:0);MemoryUtil.memPutByte(p+29,(byte)127);}
            var plan=IrisVulkanVertexFormats.copyPlan(format,format);check(plan!=null&&plan.hasSourceNormal(),"Bulk source with normal is recognized");plan.copy(MemoryUtil.memAddress(source),MemoryUtil.memAddress(target),4);
            var writer=IrisVulkanVertexFormats.writer(format,PrimitiveTopology.QUADS);
            for(int i=0;i<4;i++)writer.record(MemoryUtil.memAddress(target),MemoryUtil.memAddress(target)+i*32L,true);
            for(int i=0;i<4;i++)check(target.get(i*32+28)==0&&target.get(i*32+29)==127&&target.get(i*32+30)==0,"Supplied block normals are preserved instead of overwritten from winding");
        } finally {MemoryUtil.memFree(source);MemoryUtil.memFree(target);}
    }
    static long base(ByteBufferBuilder buffer)throws Exception {Field pointer=ByteBufferBuilder.class.getDeclaredField("pointer");pointer.setAccessible(true);return pointer.getLong(buffer);}
    static void set(Object object,String name,Object value)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
