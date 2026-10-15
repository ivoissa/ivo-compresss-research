package io.ivo.mp3;
import java.util.*;
final class TemporalScale {
    record Layout(int[] sizes,int[] counts) {}
    static Layout layout(SpectralIr.Part p){
        int kind=p.block()==2?(p.mixed()?1:2):0,offset=0;int[] sizes=new int[4];
        if(p.mpeg1()){int[] t={0,1,2,3,12,5,6,7,9,10,11,13,14,15,18,19};int v=t[p.scfc()];sizes[0]=sizes[1]=v>>>2;sizes[2]=sizes[3]=v&3;}
        else{int[] mod={5,5,4,4,5,5,4,1,4,3,1,1,5,6,6,1,4,4,4,1,4,3,1,1};int k=p.intensity()?12:0,v=p.scfc()>>(p.intensity()?1:0),product;do{if(k>=mod.length)throw new IllegalArgumentException("scale layout");product=1;for(int i=3;i>=0;i--){sizes[i]=v/product%mod[k+i];product*=mod[k+i];}v-=product;k+=4;}while(v>=0);offset=k;}
        return new Layout(sizes,Arrays.copyOfRange(SpectralTables.PARTITIONS[kind],offset,offset+4));
    }
    static int[] factors(SpectralIr.Part p,int[] transmitted,int[] reused){
        var l=layout(p);int[] raw=new int[40];int at=0,t=0;
        for(int group=0;group<4;group++)for(int j=0;j<l.counts()[group];j++,at++){
            if(at>=37)throw new IllegalArgumentException("scale count");
            if(p.mpeg1()&&(p.scfsi()&(8>>>group))!=0)raw[at]=reused[at];
            else if(l.sizes()[group]>0){if(t>=transmitted.length)throw new IllegalArgumentException("short transmitted factors");raw[at]=transmitted[t++];}
        }
        if(t!=transmitted.length)throw new IllegalArgumentException("extra factors");
        return raw;
    }
    static int[] exponents(SpectralIr.Part p,int[] raw){
        int[] result=new int[576];int position=0;int nlong=p.block()==2?(p.mixed()?(p.mpeg1()?8:6):0):22;
        int[] pre={1,1,1,1,2,2,3,3,3,2};
        for(int b=0;b<p.bands().length&&p.bands()[b]>0;b++){
            int v=raw[b],sub=0;
            if(p.block()==2&&b>=nlong)sub=p.subGain()[(b-nlong)%3]*8;
            else if(p.block()!=2&&p.preflag()&&b>=11&&b<21)v+=pre[b-11];
            int e=p.globalGain()-(v<<p.scaleShift())-sub-(p.midSide()?2:0);
            Arrays.fill(result,position,position+p.bands()[b],e);position+=p.bands()[b];
        }
        if(position!=576)throw new IllegalArgumentException("band coverage");return result;
    }
    static final double[] MULTIPLIERS=new double[511];
    static {for(int i=-255;i<=255;i++)MULTIPLIERS[i+255]=StrictMath.pow(2.0,i*3.0/16.0);}
    static int prediction(int scheme,int previous,int previousExponent,int exponent,boolean compatible){
        if(!compatible||previous==0)return 1;
        if(scheme==13)return previous;
        if(scheme!=14)return 1;
        int delta=previousExponent-exponent;
        if(delta>255)return 8206;if(delta< -255)return 1;
        return Math.max(1,Math.min(8206,(int)StrictMath.floor(previous*MULTIPLIERS[delta+255]+0.5)));
    }
    static int cord(int channel,int position,int exponent){
        int band=position<16?0:position<64?1:position<256?2:3;
        // Fixed before measurement. Quarter-log2 effective scale buckets.
        int c=exponent<140?0:exponent<170?1:exponent<200?2:3;
        return 40+(channel*4+band)*4+c;
    }
}
