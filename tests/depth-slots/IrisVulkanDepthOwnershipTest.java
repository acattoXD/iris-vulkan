package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** Actual compiled gbuffer depth methods; only engine/device/shader side effects are replaced. */
public final class IrisVulkanDepthOwnershipTest {
    private static final String SELF=IrisVulkanDepthOwnershipTest.class.getName().replace('.','/');
    private static final Map<GpuTexture,Image> TEXTURES=new IdentityHashMap<>();
    private static final Map<GpuTextureView,Image> VIEWS=new IdentityHashMap<>();
    private static final List<Image> OWNED=new ArrayList<>(),NATIVE=new ArrayList<>();
    private static Class<?> subject;
    private static int assertions,copies,merges;
    private static boolean usedFourthOwner;
    private static boolean failInitialClear;
    private static int initialClearAttempts,initialClearSuccesses;
    private static GpuTextureView[] viewsBeforeClear;
    private static final List<String> TRACES=List.of("","F","D","P","HF","HHHF","PHHHF","OF","OPF","OPHF","OPHHF","OPHHHHHF","OHF","OPOHF","OPHFPHF");
    private static final class Image {
        final int width,height;final boolean nativeImage;final float[] data;final GpuTexture texture;final GpuTextureView view;
        int textureCloses,viewCloses;
        Image(int w,int h,boolean input){width=w;height=h;nativeImage=input;data=new float[w*h];
            texture=proxy(GpuTexture.class,(o,m,a)->switch(m.getName()){
                case "getWidth"->width;case "getHeight"->height;case "getFormat"->input?GpuFormat.D32_FLOAT:GpuFormat.R32_FLOAT;
                case "isClosed"->textureCloses>0;case "close"->{check(++textureCloses==1,"Texture closed twice");yield null;}
                default->throw new AssertionError("Unexpected image method "+m.getName());});
            view=proxy(GpuTextureView.class,(o,m,a)->switch(m.getName()){
                case "texture"->texture;case "getWidth"->width;case "getHeight"->height;
                case "isClosed"->viewCloses>0||textureCloses>0;case "close"->{check(++viewCloses==1,"View closed twice");yield null;}
                default->throw new AssertionError("Unexpected view method "+m.getName());});
            TEXTURES.put(texture,this);VIEWS.put(view,this);(input?NATIVE:OWNED).add(this);
        }
    }
    public static final class Client {public static final Client INSTANCE=new Client();public final Renderer gameRenderer=new Renderer();public static Client getInstance(){return INSTANCE;}}
    public static final class Renderer {public Target mainRenderTarget(){return TARGET;}}
    public static final class Target {Image image;public GpuTexture getDepthTexture(){return image.texture;}public GpuTextureView getDepthTextureView(){return image.view;}}
    private static final Target TARGET=new Target();
    private static final CommandEncoder ENCODER=proxy(CommandEncoder.class,(o,m,a)->{
        if(m.getName().equals("clearColorTexture")){
            initialClearAttempts++;
            check(Arrays.equals(((IrisVulkanDepthSlotPlan)field("depthSlots").get(null)).logicalOwners(),new int[]{0,1,2}),"Initial aliases not committed inside encoder call");
            for(int slot=0;slot<3;slot++)check(invoke("depthSamplerView",new Class[]{String.class},"depthtex"+slot)==viewsBeforeClear[slot],"No alias publication before clear returns");
            if(failInitialClear)throw new IllegalStateException("Injected initial clear failure");
            Arrays.fill(TEXTURES.get(a[0]).data,((org.joml.Vector4fc)a[1]).x());initialClearSuccesses++;return null;
        }
        throw new AssertionError("Unexpected encoder method "+m.getName());
    });
    private static final GpuDevice DEVICE=proxy(GpuDevice.class,(o,m,a)->switch(m.getName()){
        case "createCommandEncoder"->ENCODER;
        case "createTexture"->{check(a[2]==GpuFormat.R32_FLOAT,"Owner format");check(((Supplier<?>)a[0]).get().toString().startsWith("Iris native depth owner "),"Owner label");yield new Image((int)a[3],(int)a[4],false).texture;}
        case "createTextureView"->TEXTURES.get(a[0]).view;
        default->throw new AssertionError("Unexpected device method "+m.getName());
    });
    public static GpuDevice getDevice(){return DEVICE;}
    public static void copy(CommandEncoder encoder,GpuTextureView source,GpuTextureView destination){
        Image input=VIEWS.get(source),output=VIEWS.get(destination);check(input.nativeImage&&!output.nativeImage,"Convert only native input into owned output");
        check(input.width==output.width&&input.height==output.height,"Dimensions match");
        for(int i=0;i<input.data.length;i++)output.data[i]=1f-input.data[i];copies++;
    }
    public static void mergeSolidHand(CommandEncoder encoder,GpuTextureView source,GpuTextureView before,GpuTextureView opaque,GpuTextureView destination){
        Image input=VIEWS.get(source),b=VIEWS.get(before),o=VIEWS.get(opaque),out=VIEWS.get(destination);
        check(out!=b&&out!=o,"Merge output must not alias either sampled input");
        for(int i=0;i<input.data.length;i++){float depth=1f-input.data[i];out.data[i]=depth<b.data[i]?depth:o.data[i];}merges++;
    }
    public static void main(String[] args)throws Exception{
        String owner="net/irisshaders/iris/vulkan/IrisVulkanGbufferTargets",generated=SELF+"$Subject";
        ClassNode source=new ClassNode();try(var input=IrisVulkanDepthOwnershipTest.class.getClassLoader().getResourceAsStream(owner+".class")){new ClassReader(input).accept(source,0);}
        ClassNode chosen=new ClassNode();chosen.version=source.version;chosen.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL;chosen.name=owner;chosen.superName="java/lang/Object";
        Set<String> fields=Set.of("depthTexture0","depthTexture1","depthTexture2","depthOwners","depthOwnerViews","depthSlots","frameOpen","frameActive");
        for(FieldNode field:source.fields)if(fields.contains(field.name))chosen.fields.add(field);
        check(chosen.fields.size()==fields.size(),"All actual owner/logical fields selected");
        verifyFrameClearCallsite(source,owner);
        Set<String> methods=Set.of("ensureDepthSnapshots","ensureDepthOwner","clearInitialDepths","publishDepthViews","snapshotOpaqueDepths","snapshotDepth","closeDepthSnapshots","captureSolidHandDepth","setDepthTextureViews","clearDepthTextureViews","depthSamplerView");
        for(MethodNode method:source.methods)if(methods.contains(method.name)||method.name.startsWith("lambda$ensureDepthOwner$"))chosen.methods.add(method);
        MethodNode init=new MethodNode(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);
        init.instructions.add(new InsnNode(Opcodes.ICONST_4));init.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"com/mojang/renderpearl/api/textures/GpuTexture"));init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,owner,"depthOwners","[Lcom/mojang/renderpearl/api/textures/GpuTexture;"));
        init.instructions.add(new InsnNode(Opcodes.ICONST_4));init.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"com/mojang/renderpearl/api/textures/GpuTextureView"));init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,owner,"depthOwnerViews","[Lcom/mojang/renderpearl/api/textures/GpuTextureView;"));
        String plan="net/irisshaders/iris/vulkan/IrisVulkanDepthSlotPlan";init.instructions.add(new TypeInsnNode(Opcodes.NEW,plan));init.instructions.add(new InsnNode(Opcodes.DUP));init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,plan,"<init>","()V",false));init.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,owner,"depthSlots","L"+plan+";"));init.instructions.add(new InsnNode(Opcodes.RETURN));chosen.methods.add(init);
        Map<String,String> mapping=Map.of(owner,generated,"com/mojang/blaze3d/systems/RenderSystem",SELF,"net/irisshaders/iris/vulkan/IrisVulkanDepthCopy",SELF,
            "net/minecraft/client/Minecraft",SELF+"$Client","net/minecraft/client/renderer/GameRenderer",SELF+"$Renderer","com/mojang/blaze3d/pipeline/RenderTarget",SELF+"$Target");
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);chosen.accept(new ClassRemapper(writer,new SimpleRemapper(mapping)));byte[] bytes=writer.toByteArray();
        subject=new ClassLoader(IrisVulkanDepthOwnershipTest.class.getClassLoader()){Class<?> define(){return defineClass(generated.replace('/','.'),bytes,0,bytes.length);}}.define();
        for(int resize=0;resize<12;resize++){
            int width=resize%2==0?17:9,height=resize%3+1;TARGET.image=new Image(width,height,true);
            invoke("ensureDepthSnapshots",new Class[]{GpuTexture.class},TARGET.image.texture);
            viewsBeforeClear=logicalViews();failInitialClear=true;int failedAttempts=initialClearAttempts;
            try{invoke("clearInitialDepths",new Class[]{CommandEncoder.class},ENCODER);throw new AssertionError("Injected clear failure was ignored");}
            catch(IllegalStateException expected){check(expected.getCause()!=null,"Actual encoder failure propagated");}
            finally{failInitialClear=false;}
            check(initialClearAttempts==failedAttempts+1,"One attempted clear on failure");
            check(Arrays.equals(((IrisVulkanDepthSlotPlan)field("depthSlots").get(null)).logicalOwners(),new int[]{0,1,2}),"Failed clear does not commit aliases");
            for(int slot=0;slot<3;slot++)check(invoke("depthSamplerView",new Class[]{String.class},"depthtex"+slot)==viewsBeforeClear[slot],"Failed clear does not publish aliases");
            for(int frame=0;frame<TRACES.size();frame++){
                field("frameOpen").setBoolean(null,true);field("frameActive").setBoolean(null,true);
                GpuTexture[] owners=(GpuTexture[])field("depthOwners").get(null);
                Arrays.fill(TEXTURES.get(owners[0]).data,-12345);Arrays.fill(TEXTURES.get(owners[2]).data,-12345);
                viewsBeforeClear=logicalViews();int previousClears=initialClearSuccesses;
                invoke("clearInitialDepths",new Class[]{CommandEncoder.class},ENCODER);
                check(initialClearSuccesses==previousClears+1,"Actual initial helper clears exactly one image");
                check(Arrays.equals(((IrisVulkanDepthSlotPlan)field("depthSlots").get(null)).logicalOwners(),new int[]{1,1,1}),"Successful initial clear commits aliases");
                for(int slot=0;slot<3;slot++){
                    GpuTextureView logical=(GpuTextureView)invoke("depthSamplerView",new Class[]{String.class},"depthtex"+slot);
                    check(logical.texture()==owners[1],"Initial views reference cleared owner1");
                    for(float value:VIEWS.get(logical).data)check(Float.floatToRawIntBits(value)==Float.floatToRawIntBits(1f),"Pre-opaque read must be exactly1");
                }
                check(TEXTURES.get(owners[0]).data[0]==-12345&&TEXTURES.get(owners[2]).data[0]==-12345,"Unused physical owners were not redundantly cleared");
                float[][] reference=new float[3][width*height];for(float[] image:reference)Arrays.fill(image,1);
                String trace=TRACES.get(frame);
                for(int step=0;step<trace.length();step++){
                    for(int p=0;p<width*height;p++)TARGET.image.data[p]=p==0?0:p==1?1:((p*7+step*19)%101)/100f;
                    char op=trace.charAt(step);
                    if(op=='O'){int previous=copies;invoke("snapshotOpaqueDepths",new Class[0]);check(copies==previous+1,"Actual opaque callback converts once");for(float[] image:reference)for(int p=0;p<image.length;p++)image[p]=1f-TARGET.image.data[p];}
                    else if(op=='H'){invoke("captureSolidHandDepth",new Class[0]);for(int p=0;p<reference[1].length;p++){float value=1f-TARGET.image.data[p];reference[1][p]=value<reference[2][p]?value:reference[1][p];}}
                    else{int slot=op=='P'?2:op=='D'?1:0;invoke("snapshotDepth",new Class[]{int.class},slot);for(int p=0;p<reference[slot].length;p++)reference[slot][p]=1f-TARGET.image.data[p];}
                    for(int slot=0;slot<3;slot++){GpuTextureView logical=(GpuTextureView)invoke("depthSamplerView",new Class[]{String.class},"depthtex"+slot);check(logical!=null,"Published logical view");float[] actual=VIEWS.get(logical).data;for(int p=0;p<actual.length;p++)check(Float.floatToRawIntBits(actual[p])==Float.floatToRawIntBits(reference[slot][p]),"Production depth image differs from old logical value");}
                    check(invoke("depthSamplerView",new Class[]{String.class},"gdepthtex")==invoke("depthSamplerView",new Class[]{String.class},"depthtex0"),"Legacy depth alias preserved");
                    if(trace.equals("OPHHF")&&step==3){
                        check(owners[3]!=null,"Repeated merge allocates the fourth stable owner");
                        check(((GpuTextureView)invoke("depthSamplerView",new Class[]{String.class},"depthtex1")).texture()==owners[3],"Second hand merge publishes owner3 while preserving other depths");
                        usedFourthOwner=true;
                    }
                }
                Set<GpuTexture> unique=Collections.newSetFromMap(new IdentityHashMap<>());int allocated=0;for(GpuTexture image:owners)if(image!=null){allocated++;check(unique.add(image),"Owners never alias");}
                check(allocated<=4,"At most four owner allocations");
            }
            // Closing while logical slots alias must retire each owned image/view once.
            invoke("closeDepthSnapshots",new Class[0]);invoke("closeDepthSnapshots",new Class[0]);
            for(Image image:OWNED)check(image.textureCloses==1&&image.viewCloses==1,"Owned images/views closed exactly once across resize generations");
            for(Image image:NATIVE)check(image.textureCloses==0&&image.viewCloses==0,"Never close engine-owned native depth");
            for(int slot=0;slot<3;slot++)check(invoke("depthSamplerView",new Class[]{String.class},"depthtex"+slot)==null,"No dangling logical view after close");
        }
        check(usedFourthOwner,"Fourth-owner repeated-merge case was exercised");
        System.out.println("PASS "+assertions+" actual compiled initial-clear/ownership/value checks; "+initialClearSuccesses+" successful single clears,12 failed-clear cases, "+copies+" conversions, "+merges+" hand merges;12 resize generations and"+(12*TRACES.size())+" frame traces; pre-opaque reads, all first-write indices, repeated merge owner3 and unique closes verified");
    }
    private static GpuTextureView[] logicalViews()throws Exception{GpuTextureView[] result=new GpuTextureView[3];for(int i=0;i<3;i++)result[i]=(GpuTextureView)invoke("depthSamplerView",new Class[]{String.class},"depthtex"+i);return result;}
    private static void verifyFrameClearCallsite(ClassNode source,String owner)throws Exception{
        MethodNode begin=source.methods.stream().filter(m->m.name.equals("beginFrameCapture")).findFirst().orElseThrow();
        int position=0,ensure=-1,clear=-1,clearCalls=0;
        for(var instruction:begin.instructions){if(instruction instanceof MethodInsnNode call){
            if(call.owner.equals(owner)&&call.name.equals("ensureDepthSnapshots"))ensure=position;
            if(call.owner.equals(owner)&&call.name.equals("clearInitialDepths")){clear=position;clearCalls++;}
            check(!call.name.equals("clearColorTexture"),"Frame body must not retain the old direct three-clear loop");
        }position++;}
        check(clearCalls==1&&ensure>=0&&clear>ensure,"Actual frame body calls the single-clear helper once after allocation");
        ClassNode nativeOwner=new ClassNode();try(var input=IrisVulkanDepthOwnershipTest.class.getClassLoader().getResourceAsStream("net/irisshaders/iris/vulkan/IrisNativeVulkan.class")){new ClassReader(input).accept(nativeOwner,0);}
        String callback=null;for(MethodNode m:nativeOwner.methods)if(m.name.equals("createWorldPipeline"))for(var i:m.instructions)
            if(i instanceof MethodInsnNode call&&call.name.equals("<init>")&&call.owner.startsWith("net/irisshaders/iris/vulkan/IrisNativeVulkan$"))callback=call.owner;
        check(callback!=null,"Native frame callbacks exist");ClassNode callbacks=new ClassNode();try(var input=IrisVulkanDepthOwnershipTest.class.getClassLoader().getResourceAsStream(callback+".class")){new ClassReader(input).accept(callbacks,0);}
        MethodNode frame=callbacks.methods.stream().filter(m->m.name.equals("beginFrame")).findFirst().orElseThrow();int capture=-1,sampling=-1;position=0;
        for(var i:frame.instructions){if(i instanceof MethodInsnNode call){if(call.owner.equals(owner)&&call.name.equals("beginFrameCapture"))capture=position;if(call.name.equals("beginWorldFrame"))sampling=position;}position++;}
        check(capture>=0&&sampling>capture,"Initial depth values are published before begin/prepare world sampling");
    }
    private static Field field(String name)throws Exception{Field f=subject.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=subject.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new IllegalStateException(name,e.getCause());}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
}
