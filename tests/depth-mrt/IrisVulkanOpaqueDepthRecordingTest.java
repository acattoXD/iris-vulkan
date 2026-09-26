package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.util.shaderc.Shaderc;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Executes the actual compiled copy methods with only device resource providers replaced. */
public final class IrisVulkanOpaqueDepthRecordingTest {
    private static final String FIXTURE=IrisVulkanOpaqueDepthRecordingTest.class.getName().replace('.','/');
    private static final List<Pass> PASSES=new ArrayList<>();
    private static final Map<RenderPipeline,Source> SOURCES=new IdentityHashMap<>();
    private static int assertions;
    private record Source(String vertex,String fragment,boolean collapse) { }
    private record Compiled(RenderPipeline descriptor) implements CompiledRenderPipeline {
        public boolean isClosed(){return false;} public void close(){ }
    }
    private static final class Pass {
        RenderPassDescriptor descriptor; RenderPipeline pipeline; GpuTextureView source; int draws,closes,vertices,indices;
    }
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}

    public static void main(String[] args) throws Exception {
        Path report=Path.of(args[0]); Files.createDirectories(report);
        String original="net/irisshaders/iris/vulkan/IrisVulkanDepthCopy", generated=FIXTURE+"$Subject";
        Map<String,String> remaps=new HashMap<>();remaps.put(original,generated);
        remaps.put("net/irisshaders/iris/vulkan/IrisNativeVulkan",FIXTURE);
        remaps.put("com/mojang/blaze3d/systems/RenderSystem",FIXTURE);
        remaps.put("com/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer",FIXTURE+"$Indices");
        remaps.put("com/mojang/blaze3d/systems/SamplerCache",FIXTURE+"$Samplers");
        remaps.put("net/irisshaders/iris/pathways/FullScreenQuadRenderer",FIXTURE+"$Quad");
        byte[] bytes;
        try(var input=IrisVulkanOpaqueDepthRecordingTest.class.getClassLoader().getResourceAsStream(original+".class")){
            ClassWriter writer=new ClassWriter(0);new ClassReader(input).accept(new ClassRemapper(writer,new SimpleRemapper(remaps)),0);bytes=writer.toByteArray();
        }
        Class<?> subject=new ClassLoader(IrisVulkanOpaqueDepthRecordingTest.class.getClassLoader()){
            Class<?> define(){return defineClass(generated.replace('/','.'),bytes,0,bytes.length);}
        }.define();
        Method copy=subject.getMethod("copy",CommandEncoder.class,GpuTextureView.class,GpuTextureView.class);
        Method opaque=subject.getMethod("copyOpaque",CommandEncoder.class,GpuTextureView.class,GpuTextureView.class,GpuTextureView.class,GpuTextureView.class);
        CommandEncoder encoder=proxy(CommandEncoder.class,(object,method,values)->{
            if(method.isDefault())return InvocationHandler.invokeDefault(object,method,values);
            if(!method.getName().equals("createRenderPass"))throw new AssertionError("Unexpected encoder operation: "+method.getName());
            Pass record=new Pass();record.descriptor=(RenderPassDescriptor)values[0];PASSES.add(record);
            return proxy(RenderPass.class,(pass,call,a)->{
                switch(call.getName()){
                    case "setPipeline" -> record.pipeline=((Compiled)a[0]).descriptor();
                    case "setUniform" -> {check(a[0].equals("iris_NativeDepth"),"Native depth binding name");record.source=(GpuTextureView)a[1];}
                    case "setVertexBuffer" -> {check((int)a[0]==0,"Vertex slot0");record.vertices++;}
                    case "setIndexBuffer" -> {check(a[1]==IndexType.SHORT,"Quad index type");record.indices++;}
                    case "drawIndexed" -> {check(Arrays.equals(a,new Object[]{6,1,0,0,0}),"Exactly one fullscreen indexed quad");record.draws++;}
                    case "close" -> record.closes++;
                    default -> throw new AssertionError("Unexpected render operation: "+call.getName());
                }
                return null;
            });
        });
        for(int[] dimensions:List.of(new int[]{1,1},new int[]{17,9},new int[]{2560,1351})){
            GpuTextureView source=view(dimensions[0],dimensions[1],GpuFormat.D32_FLOAT,false);
            GpuTextureView[] targets={view(dimensions[0],dimensions[1],GpuFormat.R32_FLOAT,false),view(dimensions[0],dimensions[1],GpuFormat.R32_FLOAT,false),view(dimensions[0],dimensions[1],GpuFormat.R32_FLOAT,false)};
            PASSES.clear();for(GpuTextureView target:targets)copy.invoke(null,encoder,source,target);
            check(PASSES.size()==3,"Reference emits three passes");check(PASSES.stream().mapToInt(p->p.draws).sum()==3,"Reference emits three draws");
            PASSES.clear();opaque.invoke(null,encoder,source,targets[0],targets[1],targets[2]);
            check(PASSES.size()==1,"MRT emits one pass");Pass pass=PASSES.getFirst();
            check(pass.draws==1 && pass.closes==1 && pass.vertices==1 && pass.indices==1,"One draw and balanced resources");
            check(pass.descriptor.depthAttachment()==null,"MRT must not attach the sampled main depth");
            check(pass.descriptor.colorAttachments().size()==3,"Exactly three destination attachments");
            for(int i=0;i<3;i++)check(pass.descriptor.colorAttachments().get(i).textureView()==targets[i],"Separate target order"+i);
            check(pass.descriptor.renderArea().equals(new RenderPass.RenderArea(0,0,dimensions[0],dimensions[1])),"Explicit full-size viewport including odd dimensions");
            check(pass.pipeline.getColorTargetStates().size()==3,"Three pipeline color formats");
            for(var state:pass.pipeline.getColorTargetStates())check(state.format()==GpuFormat.R32_FLOAT && state.blendFunction().isEmpty(),"R32 outputs without blending");
            check(pass.source==source,"Main depth is sampled without format/view substitution");
            check(!SOURCES.get(pass.pipeline).collapse(),"Do not collapse the three fragment outputs");
        }
        GpuTextureView source=view(16,8,GpuFormat.D32_FLOAT,false),a=view(16,8,GpuFormat.R32_FLOAT,false),b=view(16,8,GpuFormat.R32_FLOAT,false),c=view(16,8,GpuFormat.R32_FLOAT,false);
        reject(opaque,encoder,source,a,a,c,"Aliased output images");
        reject(opaque,encoder,source,a,b,view(15,8,GpuFormat.R32_FLOAT,false),"Wrong target width");
        reject(opaque,encoder,source,a,b,view(16,7,GpuFormat.R32_FLOAT,false),"Wrong target height");
        reject(opaque,encoder,source,a,b,view(16,8,GpuFormat.RGBA8_UNORM,false),"Wrong output format");
        reject(opaque,encoder,source,a,b,view(16,8,GpuFormat.R32_FLOAT,true),"Closed output");
        reject(opaque,encoder,null,a,b,c,"Missing source");
        GpuTextureView colorSource=view(16,8,GpuFormat.R32_FLOAT,false);reject(opaque,encoder,colorSource,colorSource,b,c,"Source/output alias");
        Source mrt=SOURCES.values().stream().filter(s->s.fragment().contains("iris_ForwardDepth2")).findFirst().orElseThrow();
        String assembly=compileAssembly(mrt.fragment());Files.writeString(report.resolve("opaque-depth.spvasm"),assembly);
        Matcher locations=Pattern.compile("(?m)^\\s*OpDecorate\\s+(%\\S+)\\s+Location\\s+(\\d+)\\s*$").matcher(assembly);
        Map<Integer,String> outputs=new TreeMap<>();while(locations.find())outputs.put(Integer.parseInt(locations.group(2)),locations.group(1));
        check(outputs.keySet().equals(Set.of(0,1,2)),"SPIR-V exposes exactly locations0/1/2");
        Matcher subtraction=Pattern.compile("(?m)^\\s*(%\\S+)\\s*=\\s*OpFSub\\s+%\\S+\\s+(%\\S+)\\s+(%\\S+)\\s*$").matcher(assembly);
        check(subtraction.find(),"One-minus-native-depth exists");String result=subtraction.group(1),one=subtraction.group(2),red=subtraction.group(3);
        check(!subtraction.find(),"Only one depth conversion in MRT shader");
        check(Pattern.compile(Pattern.quote(one)+"\\s*=\\s*OpConstant\\s+%\\S+\\s+1(?:\\.0)?\\b").matcher(assembly).find(),"Subtraction begins with float1");
        check(Pattern.compile(Pattern.quote(red)+"\\s*=\\s*OpCompositeExtract\\s+%\\S+\\s+%\\S+\\s+0\\b").matcher(assembly).find(),"Uses sampled red depth component");
        for(String output:outputs.values())check(Pattern.compile("OpStore\\s+"+Pattern.quote(output)+"\\s+"+Pattern.quote(result)+"\\b").matcher(assembly).find(),"Every output stores identical converted SSA value");
        check(assembly.lines().filter(l->l.contains("OpImageFetch")).count()==1,"One texelFetch, not three");
        System.out.println("PASS "+assertions+" production command-recording and shaderc/SPIR-V equality checks");
        Files.writeString(report.resolve("recording-result.txt"),"PASS "+assertions+" checks; production draw method3 passes/3 draws ->1 pass/1 draw, three independent R32 outputs; shaderc SPIR-V stores one identical1-nativeDepth value to locations0/1/2\n");
    }
    private static void reject(Method method,CommandEncoder encoder,GpuTextureView source,GpuTextureView a,GpuTextureView b,GpuTextureView c,String reason)throws Exception{
        PASSES.clear();try{method.invoke(null,encoder,source,a,b,c);throw new AssertionError("Accepted "+reason);}catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,reason);}
        check(PASSES.isEmpty(),"Validation must run before opening a pass: "+reason);
    }
    private static String compileAssembly(String source){
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try{Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options,true);Shaderc.shaderc_compile_options_set_optimization_level(options,Shaderc.shaderc_optimization_level_performance);
            result=Shaderc.shaderc_compile_into_spv_assembly(compiler,source,Shaderc.shaderc_fragment_shader,"actual-opaque-depth","main",options);
            check(Shaderc.shaderc_result_get_compilation_status(result)==Shaderc.shaderc_compilation_status_success,Shaderc.shaderc_result_get_error_message(result));
            return java.nio.charset.StandardCharsets.UTF_8.decode(Shaderc.shaderc_result_get_bytes(result)).toString();
        }finally{if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
    private static GpuTextureView view(int width,int height,GpuFormat format,boolean closed){
        GpuTexture texture=proxy(GpuTexture.class,(o,m,a)->switch(m.getName()){
            case "getWidth"->width;case "getHeight"->height;case "getFormat"->format;case "isClosed"->closed;
            default->throw new AssertionError("Unexpected texture call: "+m.getName());});
        return proxy(GpuTextureView.class,(o,m,a)->switch(m.getName()){
            case "texture"->texture;case "getWidth"->width;case "getHeight"->height;case "isClosed"->closed;
            default->throw new AssertionError("Unexpected view call: "+m.getName());});
    }
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    private static GpuBuffer buffer(){return proxy(GpuBuffer.class,(object,method,args)->{
        if(method.isDefault())return InvocationHandler.invokeDefault(object,method,args);
        return switch(method.getName()){case "size"->256L;case "isClosed"->false;default->throw new AssertionError("Unexpected buffer call: "+method.getName());};});}
    public static Indices getSequentialBuffer(PrimitiveTopology topology){check(topology==PrimitiveTopology.QUADS,"Quad primitive");return new Indices();}
    public static Samplers getSamplerCache(){return new Samplers();}
    public static CompiledRenderPipeline compiledFor(RenderPipeline pipeline){return new Compiled(pipeline);}
    public static void registerCustomPipelineSource(RenderPipeline pipeline,String name,String vertex,String fragment,boolean collapse){SOURCES.put(pipeline,new Source(vertex,fragment,collapse));}
    public static void unregisterCustomPipelineSource(RenderPipeline pipeline){SOURCES.remove(pipeline);}
    public static final class Indices{public GpuBuffer getBuffer(int count){check(count==6,"Six indices");return buffer();}public IndexType type(){return IndexType.SHORT;}}
    public static final class Samplers{public GpuSampler getClampToEdge(FilterMode mode,boolean mip){check(mode==FilterMode.NEAREST&&!mip,"Nearest level-zero depth sampling");return proxy(GpuSampler.class,(o,m,a)->{throw new AssertionError("Sampler need not be queried");});}}
    public static final class Quad{public static final Quad INSTANCE=new Quad();public GpuBuffer getQuad(){return buffer();}}
}
