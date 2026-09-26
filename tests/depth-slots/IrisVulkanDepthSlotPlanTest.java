package net.irisshaders.iris.vulkan;

import java.util.*;

/** Compare logical values with the old independent-image implementation over bounded exhaustive traces. */
public final class IrisVulkanDepthSlotPlanTest {
    private static long checks, sequences, operations;
    private static final int PIXELS=7;
    private static final char[] OPS={'O','P','H','F','R','D'};
    private static final Set<String> states=new HashSet<>();
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Model {
        final IrisVulkanDepthSlotPlan plan=new IrisVulkanDepthSlotPlan();
        final float[][] logical=new float[3][PIXELS], physical=new float[4][];
        int conversions,oldConversions,merges;
        Model(){reset();}
        void reset(){
            plan.reset();for(int i=0;i<3;i++){if(physical[i]==null)physical[i]=new float[PIXELS];Arrays.fill(logical[i],1);}
            int owner=plan.chooseInitialClearOwner();Arrays.fill(physical[owner],1);plan.commitInitialClear(owner);compare();
        }
        void step(char op,int step){
            operations++;
            if(op=='R'){reset();compare();return;}
            float[] converted=new float[PIXELS];
            for(int p=0;p<PIXELS;p++){float raw=p==0?0:p==1?1:((step*19+p*7)%101)/100f;converted[p]=1.0f-raw;}
            if(op=='O'){
                int owner=plan.chooseOpaqueOwner();ensure(owner);System.arraycopy(converted,0,physical[owner],0,PIXELS);
                plan.commitOpaque(owner);for(float[] target:logical)System.arraycopy(converted,0,target,0,PIXELS);
                conversions++;oldConversions+=3;
            }else{
                int slot=op=='P'?2:op=='F'?0:1;
                int opaque=plan.readOwner(1),before=plan.readOwner(2);
                int sampled=op=='H'?(1<<opaque)|(1<<before):0;
                int[] original=plan.logicalOwners();int owner=plan.chooseWriteOwner(slot,sampled);
                check(Arrays.equals(original,plan.logicalOwners()),"Choosing a target must not publish before recording");
                for(int other=0;other<3;other++)if(other!=slot)check(owner!=plan.readOwner(other),"Write clobbers another logical depth");
                check((sampled&(1<<owner))==0,"Merge cannot sample its output image");ensure(owner);
                for(int p=0;p<PIXELS;p++)physical[owner][p]=op=='H'?(converted[p]<physical[before][p]?converted[p]:physical[opaque][p]):converted[p];
                plan.commitWrite(slot,owner,sampled);
                for(int p=0;p<PIXELS;p++)logical[slot][p]=op=='H'?(converted[p]<logical[2][p]?converted[p]:logical[1][p]):converted[p];
                if(op=='H')merges++;else{conversions++;oldConversions++;}
            }
            states.add(Arrays.toString(plan.logicalOwners()));compare();
        }
        void ensure(int owner){check(owner>=0&&owner<4,"At most four stable physical owners");if(physical[owner]==null)physical[owner]=new float[PIXELS];}
        void compare(){for(int slot=0;slot<3;slot++)for(int p=0;p<PIXELS;p++)check(Float.floatToRawIntBits(logical[slot][p])==Float.floatToRawIntBits(physical[plan.readOwner(slot)][p]),"Logical depth differs from full-copy reference: slot"+slot+" pixel"+p);}
    }
    public static void main(String[] args){
        int[] limits={1,6,36,216,1296,7776,46656};
        for(int length=0;length<=6;length++)for(int code=0;code<limits[length];code++){
            Model model=new Model();int value=code;
            for(int step=0;step<length;step++){model.step(OPS[value%6],step);value/=6;}
            sequences++;
        }
        for(String trace:List.of("","F","D","P","HF","HHHF","PHHHF","OF","OPF","OPHF","OPHHF","OPHHHHHHHHHF","OPHFRF","OHFPHROPHF")){
            Model model=new Model();for(int i=0;i<trace.length();i++)model.step(trace.charAt(i),i);
            check(model.oldConversions-model.conversions==2*trace.chars().filter(c->c=='O').count(),"Exactly two opaque conversions avoided");
        }
        Model repeated=new Model();for(int frame=0;frame<100;frame++){
            repeated.step('R',frame);for(int i=0;i<10;i++)repeated.step(i==0?'O':i==1?'P':i==9?'F':'H',frame*10+i);
        }
        IrisVulkanDepthSlotPlan plan=new IrisVulkanDepthSlotPlan();plan.commitOpaque(plan.chooseOpaqueOwner());int[] before=plan.logicalOwners();
        reject(()->plan.commitWrite(0,1,0),"Shared destination rejection");check(Arrays.equals(before,plan.logicalOwners()),"Rejected write leaves aliases unchanged");
        reject(()->plan.commitWrite(1,0,1),"Sampled destination rejection");
        reject(()->plan.readOwner(-1),"Logical lower bound");reject(()->plan.readOwner(3),"Logical upper bound");
        reject(()->plan.commitOpaque(4),"Owner upper bound");reject(()->plan.chooseWriteOwner(1,16),"Sample mask upper bound");
        reject(()->plan.chooseWriteOwner(1,15),"No free output image");
        plan.reset();check(Arrays.equals(plan.logicalOwners(),new int[]{0,1,2}),"Reset removes aliases for new frame/resize");
        int clearOwner=plan.chooseInitialClearOwner();check(clearOwner==1,"Stable initial clear owner");
        check(Arrays.equals(plan.logicalOwners(),new int[]{0,1,2}),"Choosing a clear target does not publish aliases");
        plan.commitInitialClear(clearOwner);check(Arrays.equals(plan.logicalOwners(),new int[]{1,1,1}),"Successful clear publishes equal logical initial values");
        System.out.println("PASS "+checks+" checks across "+sequences+" exhaustive traces (length0..6), "+operations+" operations, "+states.size()+" reached owner states; initial shared clear, all first-write indices, no-opaque hand/repeated merges preserve old logical values");
    }
    private static void reject(Runnable action,String message){boolean failed=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}check(failed,message);}
}
