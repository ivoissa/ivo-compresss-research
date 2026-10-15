package io.ivo.mp4;
import java.util.*;
/** Prescribed syntax paths; independent real-stream reference checks run separately. */
public final class CabacResidualTest {
    static final class Prescribed implements CabacResidual.Decisions {
        final ArrayDeque<int[]> regular=new ArrayDeque<>(); final ArrayDeque<Integer> bypass=new ArrayDeque<>();
        Prescribed r(int c,int b){regular.add(new int[]{c,b});return this;}
        Prescribed b(int... values){for(int v:values)bypass.add(v);return this;}
        public int regular(int c){var v=regular.remove();if(v[0]!=c)throw new AssertionError("context "+c+" expected "+v[0]);return v[1];}
        public int bypass(){return bypass.remove();}
        void done(){if(!regular.isEmpty()||!bypass.isEmpty())throw new AssertionError("unconsumed decisions");}
    }
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args) {
        int cases=0;
        for(int[] spec:new int[][]{{2,16,134,195,247},{3,4,149,210,257},{4,15,152,213,266},{5,64,402,417,426}}) {
            var p=new Prescribed().r(spec[2],1).r(spec[3],1).r(spec[4]+1,0).b(0);
            var c=CabacResidual.coefficients(p,spec[0],spec[1]);check(c[0]==1&&Arrays.stream(c).sum()==1);p.done();cases++;
        }
        var p=new Prescribed();for(int i=0;i<15;i++)p.r(134+i,0);
        p.r(248,0).b(1);var c=CabacResidual.coefficients(p,2,16);check(c[15]==-1&&Arrays.stream(c).sum()==-1);p.done();cases++;
        p=new Prescribed().r(134,1).r(195,0).r(135,0).r(136,1).r(197,1).r(248,0).r(249,1).r(252,0).b(0,1);
        c=CabacResidual.coefficients(p,2,16);check(c[0]==-2&&c[2]==1&&Arrays.stream(c).filter(v->v!=0).count()==2);p.done();cases++;
        p=new Prescribed().r(134,1).r(195,1).r(248,1);for(int i=0;i<13;i++)p.r(252,1);
        p.b(1,0,1,1);c=CabacResidual.coefficients(p,2,16);check(c[0]==-17);p.done();cases++;
        for(int neighbors:new int[]{0,15}) {
            p=new Prescribed();if(neighbors==0)for(int i=0;i<4;i++)p.r(76,0);else for(int i=0;i<4;i++)p.r(73+i,0);
            p.r(77,0);check(CabacResidual.cbp(p,neighbors,neighbors)==0);p.done();cases++;
        }
        p=new Prescribed().r(60,0);check(CabacResidual.deltaQp(p)==0);p.done();cases++;
        p=new Prescribed().r(60,1).r(62,0);check(CabacResidual.deltaQp(p)==1);p.done();cases++;
        p=new Prescribed().r(60,1).r(62,1).r(63,0);check(CabacResidual.deltaQp(p)==-1);p.done();cases++;
        p=new Prescribed().r(134,1).r(195,1).r(248,1);for(int i=0;i<13;i++)p.r(252,1);for(int i=0;i<24;i++)p.b(1);
        try{CabacResidual.coefficients(p,2,16);throw new AssertionError("overflow accepted");}catch(IllegalArgumentException expected){cases++;}
        try{CabacResidual.coefficients(new Prescribed(),2,15);throw new AssertionError("category accepted");}catch(IllegalArgumentException expected){cases++;}
        try{CabacResidual.coefficients(new Prescribed(),2,16);throw new AssertionError("truncated accepted");}catch(NoSuchElementException expected){cases++;}
        System.out.println("PASS residue_vectors="+cases+" contexts=true implicit_last=true reverse_levels=true signs=true escape=true boundaries=true malformed=true");
    }
}
