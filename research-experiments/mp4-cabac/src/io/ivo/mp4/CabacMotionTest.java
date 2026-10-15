package io.ivo.mp4;
import java.util.*;
public final class CabacMotionTest {
    static class Prescribed implements CabacMotion.Decisions {
        final Queue<Integer> contexts=new ArrayDeque<>(),regular=new ArrayDeque<>(),bypass=new ArrayDeque<>();
        void add(int c,int b){contexts.add(c);regular.add(b);}
        public int regular(int c){if(contexts.remove()!=c)throw new AssertionError("context");return regular.remove();}
        public int bypass(){return bypass.remove();}
        void done(){if(!contexts.isEmpty()||!regular.isEmpty()||!bypass.isEmpty())throw new AssertionError("unconsumed");}
    }
    public static void main(String[] a){int tested=0;
        for(int sum:new int[]{0,2,3,32,33,140})for(int base:new int[]{40,47})for(int v:new int[]{0,1,2,3,4,8,9,10,16,17,40,70,1000})for(int sign:new int[]{0,1}){
            Prescribed p=new Prescribed();p.add(base+(sum>2?1:0)+(sum>32?1:0),v==0?0:1);
            if(v>0){int c=base+3;for(int i=1;i<Math.min(v,9);i++){p.add(c,1);if(i<4)c++;}if(v<9)p.add(c,0);else{int remain=v-9,k=3;while(remain>=(1<<k)){p.bypass.add(1);remain-=1<<k;k++;}p.bypass.add(0);for(int i=k-1;i>=0;i--)p.bypass.add((remain>>i)&1);}p.bypass.add(sign);}
            int actual=CabacMotion.difference(p,base,sum);if(actual!=(sign==1?-v:v))throw new AssertionError("value");p.done();tested++;
        }
        for(int left:new int[]{0,1})for(int top:new int[]{0,1})for(int ref=0;ref<4;ref++){Prescribed p=new Prescribed();int ctx=(left>0?1:0)+(top>0?2:0);for(int i=0;i<ref;i++){p.add(54+ctx,1);ctx=(ctx>>2)+4;}p.add(54+ctx,0);if(CabacMotion.reference(p,left,top,4)!=ref)throw new AssertionError("reference");p.done();}
        Prescribed p=new Prescribed();if(CabacMotion.reference(p,0,0,1)!=0)throw new AssertionError();p.done();
        System.out.println("PASS prescribed_mvd_vectors="+tested+" reference_vectors=17; independent real-video oracle required");
    }
}
