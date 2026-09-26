package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.joml.Vector3d;
import org.joml.Vector4fc;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/** Real parser/planner/allocation loops; only GPU calls and live fog state are recorded. */
public final class IrisVulkanTargetLivenessTest {
    private static final String PREFIX="net.irisshaders.iris.vulkan.";
    private static final String MODEL=PREFIX+"IrisVulkanTargetModel";
    private static final String SELF=IrisVulkanTargetLivenessTest.class.getName().replace('.','/');
    private static final List<Texture> textures=new ArrayList<>();
    private static final List<String> clears=new ArrayList<>();
    private static int checks;

    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(o,m,a)->{
        if(m.getDeclaringClass()==Object.class)return switch(m.getName()){case "hashCode"->System.identityHashCode(o);case "equals"->o==a[0];case "toString"->type.getSimpleName();default->throw new AssertionError(m);};
        return handler.invoke(o,m,a);
    });}
    private static final class Texture {
        final String label;final GpuFormat format;final int width,height,mips,usage;final GpuTexture texture;
        int closed;final List<int[]> viewCloses=new ArrayList<>();
        Texture(Object[] a){label=((Supplier<?>)a[0]).get().toString();usage=(int)a[1];format=(GpuFormat)a[2];width=(int)a[3];height=(int)a[4];mips=(int)a[6];
            texture=proxy(GpuTexture.class,(o,m,v)->switch(m.getName()){
                case "getWidth"->Math.max(1,width>>(int)v[0]);case "getHeight"->Math.max(1,height>>(int)v[0]);
                case "getFormat"->format;case "getMipLevels"->mips;case "usage"->usage;case "isClosed"->closed!=0;
                case "close"->{check(++closed==1,"Texture closed exactly once: "+label);yield null;}
                default->throw new AssertionError("Unexpected texture call "+m);
            });textures.add(this);
        }
        GpuTextureView view(){int[] count={0};viewCloses.add(count);return proxy(GpuTextureView.class,(o,m,a)->switch(m.getName()){
            case "texture"->texture;case "getWidth"->width;case "getHeight"->height;case "isClosed"->count[0]!=0||closed!=0;
            case "close"->{check(++count[0]==1,"View closed exactly once");yield null;}default->throw new AssertionError(m);
        });}
        long bytes(){long n=0;for(int level=0;level<mips;level++)n+=(long)Math.max(1,width>>level)*Math.max(1,height>>level)*format.blockSize();return n;}
    }
    public static final class Gpu {
        public static GpuDevice getDevice(){return DEVICE;}
    }
    public static final class NativeFlags {
        public static boolean worldDevelopmentEnabled(){return true;}
        public static void unregisterCustomPipelineSource(RenderPipeline ignored){throw new AssertionError("No GPU pipeline should be created");}
    }
    public static final class Fog {
        public static final Fog INSTANCE=new Fog();
        public Vector3d getFogColor(){return new Vector3d(.1,.2,.3);}
    }
    /** Cross-loader access only; delegates to the unchanged production methods. */
    public static final class Access {
        public static Set<Integer> computeTargets(ProgramSet programs) { return IrisVulkanColorImages.computeTargets(programs); }
        public static Object allocate(boolean storage,Supplier<?> supplier) { return IrisVulkanColorImages.allocate(storage,supplier); }
        public static IrisVulkanTargetSpec withStorageImage(IrisVulkanTargetSpec spec,boolean storage) { return spec.withStorageImage(storage); }
    }
    private static final GpuDevice DEVICE=proxy(GpuDevice.class,(o,m,a)->switch(m.getName()){
        case "createTexture"->new Texture(a).texture;
        case "createTextureView"->textures.stream().filter(t->t.texture==a[0]).findFirst().orElseThrow().view();
        default->throw new AssertionError("Unexpected GPU call "+m);
    });
    private static final CommandEncoder ENCODER=proxy(CommandEncoder.class,(o,m,a)->{
        if(!m.getName().equals("clearColorTexture"))throw new AssertionError("Unexpected encoded work "+m);
        Texture t=textures.stream().filter(x->x.texture==a[0]).findFirst().orElseThrow();Vector4fc c=(Vector4fc)a[1];
        check(t.closed==0,"No clear after close");clears.add(t.label+":"+c.x()+","+c.y()+","+c.z()+","+c.w());return null;
    });

    private static final class ModelLoader extends ClassLoader implements AutoCloseable {
        final Path classes;final ZipFile archive;
        ModelLoader(Path classes,Path jar)throws Exception{super(IrisVulkanTargetLivenessTest.class.getClassLoader());this.classes=classes;archive=jar==null?null:new ZipFile(jar.toFile());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(!name.startsWith(MODEL)&&!name.equals(PREFIX+"IrisVulkanTargetIndices"))return super.loadClass(name,resolve);
            synchronized(getClassLoadingLock(name)){
                Class<?> loaded=findLoadedClass(name);if(loaded==null)try{
                    String file=name.replace('.','/')+".class";byte[] bytes=archive==null?Files.readAllBytes(classes.resolve(file)):archive.getInputStream(archive.getEntry(file)).readAllBytes();
                    ClassWriter writer=new ClassWriter(0);var remapper=new ClassRemapper(writer,new SimpleRemapper(Map.of(
                        "com/mojang/blaze3d/systems/RenderSystem",SELF+"$Gpu",
                        "net/irisshaders/iris/vulkan/IrisVulkanColorImages",SELF+"$Access",
                        "net/irisshaders/iris/vulkan/IrisNativeVulkan",SELF+"$NativeFlags",
                        "net/irisshaders/iris/uniforms/CapturedRenderingState",SELF+"$Fog")));
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,remapper){
                        @Override public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){
                            return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,method,desc,signature,exceptions)){
                                @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                                    if(owner.equals("net/irisshaders/iris/vulkan/IrisVulkanTargetSpec")&&name.equals("withStorageImage"))
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF+"$Access",name,"(Lnet/irisshaders/iris/vulkan/IrisVulkanTargetSpec;Z)Lnet/irisshaders/iris/vulkan/IrisVulkanTargetSpec;",false);
                                    else super.visitMethodInsn(opcode,owner,name,descriptor,itf);
                                }
                            };
                        }
                    },0);
                    byte[] remapped=writer.toByteArray();loaded=defineClass(name,remapped,0,remapped.length);
                }catch(Exception e){throw new ClassNotFoundException(name,e);}if(resolve)resolveClass(loaded);return loaded;
            }
        }
        @Override public void close()throws Exception{if(archive!=null)archive.close();}
    }
    private static ShaderPack pack(ShaderProperties properties)throws Exception{
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);ShaderPack pack=(ShaderPack)((Unsafe)field.get(null)).allocateInstance(ShaderPack.class);
        Field active=ShaderPack.class.getDeclaredField("activeFeatures");active.setAccessible(true);active.set(pack,EnumSet.allOf(FeatureFlags.class));
        Field images=ShaderPack.class.getDeclaredField("irisCustomImages");images.setAccessible(true);images.set(pack,properties.getIrisCustomImages());return pack;
    }
    private static ProgramSet programs(Map<String,String> source,String propertiesText)throws Exception{
        var options=new ShaderPackOptions(new IncludeGraph(Path.of("."),ImmutableList.of(),false),Map.of());
        var properties=new ShaderProperties(propertiesText,options,List.of());
        return new ProgramSet(AbsolutePackPath.fromAbsolutePath("/"),p->source.get(p.getPathString().substring(1)),properties,pack(properties));
    }
    private static ProgramSet exported(Path root,String dimension)throws Exception{
        Map<String,String> source=new HashMap<>();try(var paths=Files.list(root.resolve(dimension))){for(Path p:paths.toList())if(Files.isRegularFile(p))source.put(p.getFileName().toString(),Files.readString(p));}
        return programs(source,Files.readString(root.resolve("preprocessed.properties")));
    }
    @SuppressWarnings("unchecked") private static List<IrisVulkanTargetSpec> specs(Class<?> type,ProgramSet programs)throws Exception{
        Method method=type.getDeclaredMethod("buildSpecs",ProgramSet.class,int.class,int.class,GpuFormat.class);method.setAccessible(true);
        return(List<IrisVulkanTargetSpec>)method.invoke(null,programs,2560,1440,GpuFormat.RGBA8_UNORM);
    }
    private static Set<Integer> indices(List<IrisVulkanTargetSpec> specs){Set<Integer> result=new TreeSet<>();specs.forEach(s->result.add(s.index()));return result;}
    private static Map<String,Object> record(Class<?> type,ProgramSet programs,List<IrisVulkanTargetSpec> specs)throws Exception{
        int start=textures.size();Object model=type.getConstructor().newInstance();
        check((boolean)type.getMethod("configure",ProgramSet.class,int.class,int.class,GpuFormat.class).invoke(model,programs,2560,1440,GpuFormat.RGBA8_UNORM),"Initial configuration installs");
        check(textures.size()-start==specs.size()*2,"Only required target pairs allocated");
        clears.clear();type.getMethod("seed",CommandEncoder.class,GpuTextureView.class).invoke(model,ENCODER,null);List<String> first=List.copyOf(clears);
        clears.clear();type.getMethod("seed",CommandEncoder.class,GpuTextureView.class).invoke(model,ENCODER,null);List<String> steady=List.copyOf(clears);
        int count=textures.size();check(!(boolean)type.getMethod("configure",ProgramSet.class,int.class,int.class,GpuFormat.class).invoke(model,programs,2560,1440,GpuFormat.RGBA8_UNORM),"Same inputs keep allocations");check(textures.size()==count,"No reallocations on repeated configure");
        type.getMethod("close").invoke(model);for(Texture t:textures.subList(start,textures.size())){check(t.closed==1,"Each allocated texture destroyed");for(int[] v:t.viewCloses)check(v[0]==1,"Each view destroyed");}
        return Map.of("indices",indices(specs),"texturesAllocated",textures.size()-start,"logicalAllocationBytes",textures.subList(start,textures.size()).stream().mapToLong(Texture::bytes).sum(),"firstFrameClears",first,"steadyFrameClears",steady);
    }
    private static void synthetic(Class<?> type)throws Exception{
        String v="#version 450\nvoid main(){gl_Position=vec4(0);}";
        Map<String,String> sources=new HashMap<>();sources.put("gbuffers_terrain.vsh",v);sources.put("gbuffers_terrain.fsh","#version 450\nuniform sampler2D colortex8;\nvoid main(){}\n");
        Set<Integer> defaults=indices(specs(type,programs(sources,"")));check(defaults.equals(Set.of(0,3,4)),"Internal fallback targets preserved without unused8");
        for(String stage:List.of("vsh","fsh","gsh","tcs","tes")){
            Map<String,String> copy=new HashMap<>(sources);copy.put("gbuffers_terrain."+stage,v+"\nuniform sampler2D colortex8; vec4 sampleIt(){return texture(colortex8,vec2(0));}");
            check(indices(specs(type,programs(copy,""))).contains(8),"Live sampler in stage "+stage);
        }
        for(String f:List.of("/* DRAWBUFFERS:8 */","/* RENDERTARGETS:8 */","const bool colortex8MipmapEnabled=true;")){
            Map<String,String> copy=new HashMap<>(sources);copy.put("gbuffers_terrain.fsh",sources.get("gbuffers_terrain.fsh")+f);
            check(indices(specs(type,programs(copy,""))).contains(8),"Explicit graphics requirement "+f);
        }
        for(String property:List.of("flip.gbuffers_terrain.colortex8=true","flip.prepare_pre.colortex8=true","flip.final_pre.colortex8=false"))
            check(indices(specs(type,programs(sources,property))).contains(8),"Flip target retained "+property);
        for(String name:List.of("setup","shadow","final","begin","prepare","deferred","composite","shadowcomp")){
            Map<String,String> copy=new HashMap<>(sources);copy.put(name+".csh","#version 450\nlayout(rgba16f) writeonly uniform image2D colorimg8; void main(){}\n");
            check(indices(specs(type,programs(copy,""))).contains(8),"Storage declaration in compute stage "+name);
        }
        Map<String,String> shadow=new HashMap<>(sources);shadow.put("shadow.vsh",v);shadow.put("shadow.fsh","#version 450\n/* DRAWBUFFERS:8 */\nvoid main(){}\n");
        check(!indices(specs(type,programs(shadow,""))).contains(8),"Shadowcolor outputs do not allocate main colortex8");
    }
    private static void lifecycle(Class<?> type,Path fixtures)throws Exception{
        int start=textures.size();Object model=type.getConstructor().newInstance();
        ProgramSet high=exported(fixtures.resolve("HIGH/expanded"),"world0"),ultra=exported(fixtures.resolve("ULTRA/expanded"),"world0");
        int[][] sizes={{2560,1440},{2560,1440},{1280,720},{1280,720},{2560,1440}};
        ProgramSet[] sequence={high,ultra,ultra,high,high};
        for(int step=0;step<sequence.length;step++){
            int before=textures.size();boolean changed=(boolean)type.getMethod("configure",ProgramSet.class,int.class,int.class,GpuFormat.class).invoke(model,sequence[step],sizes[step][0],sizes[step][1],GpuFormat.RGBA8_UNORM);
            check(changed,"Preset/resize change recreates the required targets");
            for(Texture t:textures.subList(start,before))check(t.closed==1,"Previous target cannot survive preset/resize replacement");
            boolean has8=(boolean)type.getMethod("isAllocated",int.class).invoke(model,8);check(has8==(sequence[step]==ultra),"Preset transition keeps correct target8 liveness");
            check((type.getMethod("currentView",int.class).invoke(model,8)!=null)==has8,"No stale view from previous preset");
            clears.clear();type.getMethod("seed",CommandEncoder.class,GpuTextureView.class).invoke(model,ENCODER,null);
            long clear8=clears.stream().filter(s->s.contains("colortex8 ")).count();check(clear8==(has8?3:0),"Fresh reflection target initialized when Ultra enables it");
            int after=textures.size();check(!(boolean)type.getMethod("configure",ProgramSet.class,int.class,int.class,GpuFormat.class).invoke(model,sequence[step],sizes[step][0],sizes[step][1],GpuFormat.RGBA8_UNORM),"Repeated transition inputs reuse resources");check(textures.size()==after,"No allocation on repeated frame");
        }
        check((boolean)type.getMethod("configure",ProgramSet.class,int.class,int.class,GpuFormat.class).invoke(model,null,2560,1440,GpuFormat.RGBA8_UNORM),"No-pack fallback installs");
        for(int i=0;i<32;i++)check((boolean)type.getMethod("isAllocated",int.class).invoke(model,i)==Set.of(0,3,4).contains(i),"No-pack fallback target "+i);
        type.getMethod("close").invoke(model);for(Texture t:textures.subList(start,textures.size())){check(t.closed==1,"Lifecycle texture closed once");for(int[] count:t.viewCloses)check(count[0]==1,"Lifecycle view closed once");}
    }
    public static void main(String[] args)throws Exception{
        Path classes=Path.of(args[0]),baseline=Path.of(args[1]),fixtures=Path.of(args[2]),output=Path.of(args[3]);List<Object> reports=new ArrayList<>();
        try(ModelLoader oldLoader=new ModelLoader(classes,baseline);ModelLoader newLoader=new ModelLoader(classes,null)){
            Class<?> old=oldLoader.loadClass(MODEL),current=newLoader.loadClass(MODEL);synthetic(current);lifecycle(current,fixtures);
            for(String preset:List.of("HIGH","ULTRA"))for(String dimension:List.of("world0","world-1","world1")){
                ProgramSet programs=exported(fixtures.resolve(preset+"/expanded"),dimension);var before=specs(old,programs);var after=specs(current,programs);
                check(indices(before).containsAll(indices(after)),"Optimization can only omit targets");
                for(var retained:after)check(before.contains(retained),"Retained target settings/mips/clear values unchanged");
                check(indices(after).contains(8)==preset.equals("ULTRA"),"Target8 liveness for "+preset+"/"+dimension);
                var oldRecord=record(old,programs,before);var newRecord=record(current,programs,after);
                Set<Integer> omitted=new TreeSet<>(indices(before));omitted.removeAll(indices(after));
                for(String key:List.of("firstFrameClears","steadyFrameClears")){
                    List<?> expected=((List<?>)oldRecord.get(key)).stream().filter(event->omitted.stream().noneMatch(i->event.toString().contains("colortex"+i+" "))).sorted(Comparator.comparing(Object::toString)).toList();
                    List<?> actual=((List<?>)newRecord.get(key)).stream().sorted(Comparator.comparing(Object::toString)).toList();check(expected.equals(actual),"All retained clear commands/values unchanged");
                }
                reports.add(Map.of("preset",preset,"dimension",dimension,"removedTargets",omitted,"before",oldRecord,"after",newRecord));
            }
        }
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",true,"checks",checks,"records",reports,"scope","Actual ProgramSet/target planner/allocation/clear loops. GPU effects recorded only; not shader rendering or FPS."))+"\n");
        System.out.println("PASS "+checks+" real parser/planner/allocation/clear checks across6 preset/dimension combinations");
    }
}
