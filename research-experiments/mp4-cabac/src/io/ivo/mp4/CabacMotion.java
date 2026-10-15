package io.ivo.mp4;
import java.util.*;
/** First non-skipped P macroblock only; all external neighbors are skipped or unavailable. */
final class CabacMotion {
    interface Decisions { int regular(int context); int bypass(); }
    static final class Input implements Decisions {
        private final CabacArithmetic arithmetic;
        private final CabacArithmetic.Context[] contexts=new CabacArithmetic.Context[20];
        private static final int[][][] COEFFICIENTS={{{-3,69},{-6,81},{-11,96},{6,55},{7,67},{-5,86},{2,88},{0,58},{-3,76},{-10,94},{5,54},{4,69},{-3,81},{0,88},{-7,67},{-5,74},{-4,74},{-5,80},{-7,72},{1,58}},{{-2,69},{-5,82},{-10,96},{2,59},{2,75},{-3,87},{-3,100},{1,56},{-3,74},{-6,85},{0,59},{-3,81},{-7,86},{-5,95},{-1,66},{-1,77},{1,70},{-2,86},{-5,72},{0,61}},{{-11,89},{-15,103},{-21,116},{19,57},{20,58},{4,84},{6,96},{1,63},{-5,85},{-13,106},{5,63},{6,75},{-3,90},{-1,101},{3,55},{-4,79},{-2,75},{-12,97},{-7,50},{1,60}}};
        Input(CabacArithmetic arithmetic,int init,int qp) {
            if(init<0||init>2||qp<0||qp>51) throw new IllegalArgumentException("motion initialization");
            this.arithmetic=arithmetic;
            for(int i=0;i<20;i++) contexts[i]=CabacArithmetic.Context.initialize(COEFFICIENTS[init][i][0],COEFFICIENTS[init][i][1],qp);
        }
        public int regular(int c) { if(c<40||c>59) throw new IllegalArgumentException("motion context"); return arithmetic.regular(contexts[c-40]); }
        public int bypass(){return arithmetic.bypass();}
    }
    static int reference(Decisions in,int left,int top,int count) {
        if(count<1||count>32) throw new IllegalArgumentException("reference count");
        if(count==1)return 0;
        int ctx=(left>0?1:0)+(top>0?2:0),ref=0;
        while(in.regular(54+ctx)==1){if(++ref>=count)throw new IllegalArgumentException("reference outside active list");ctx=(ctx>>2)+4;}
        return ref;
    }
    static int difference(Decisions in,int base,int neighborSum) {
        if((base!=40&&base!=47)||neighborSum<0)throw new IllegalArgumentException("mvd context");
        if(in.regular(base+(neighborSum>2?1:0)+(neighborSum>32?1:0))==0)return 0;
        int value=1,c=base+3;
        while(value<9&&in.regular(c)==1){if(value<4)c++;value++;}
        if(value>=9){int k=3;while(in.bypass()==1){value+=1<<k;if(++k>24)throw new IllegalArgumentException("mvd overflow");}while(k-->0)value+=in.bypass()<<k;}
        return in.bypass()==1?-value:value;
    }
    record Part(int n,int x,int y,int w,int h) {}
    record Motion(int n,int ref,int dx,int dy,int neighborX,int neighborY) {}
    static List<Motion> decode(Decisions in,int type,CabacSubType.Type[] subs,int count) {
        List<Part> refs=new ArrayList<>(),parts=new ArrayList<>();
        if(type==0)refs.add(new Part(0,0,0,4,4));
        else if(type==1){refs.add(new Part(0,0,0,4,2));refs.add(new Part(8,0,2,4,2));}
        else if(type==2){refs.add(new Part(0,0,0,2,4));refs.add(new Part(4,2,0,2,4));}
        else if(type==3){
            if(subs==null||subs.length!=4)throw new IllegalArgumentException("subtypes");
            for(int i=0;i<4;i++){
                int x=(i%2)*2,y=(i/2)*2;refs.add(new Part(4*i,x,y,2,2));
                int w=subs[i].width()/4,h=subs[i].height()/4;
                for(int yy=0;yy<2;yy+=h)for(int xx=0;xx<2;xx+=w)parts.add(new Part(4*i+xx+2*yy,x+xx,y+yy,w,h));
            }
        }else throw new IllegalArgumentException("inter macroblock type");
        if(type!=3)parts.addAll(refs);
        int[][] r=new int[4][4],mx=new int[4][4],my=new int[4][4];
        for(Part p:refs){int value=reference(in,p.x()==0?0:r[p.y()][p.x()-1],p.y()==0?0:r[p.y()-1][p.x()],count);fill(r,p,value);}
        List<Motion> result=new ArrayList<>();
        for(Part p:parts){int ax=(p.x()==0?0:mx[p.y()][p.x()-1])+(p.y()==0?0:mx[p.y()-1][p.x()]);int ay=(p.x()==0?0:my[p.y()][p.x()-1])+(p.y()==0?0:my[p.y()-1][p.x()]);
            int dx=difference(in,40,ax),dy=difference(in,47,ay);
            result.add(new Motion(p.n(),r[p.y()][p.x()],dx,dy,ax,ay));fill(mx,p,Math.min(70,Math.abs(dx)));fill(my,p,Math.min(70,Math.abs(dy)));
        }
        return result;
    }
    private static void fill(int[][] grid,Part p,int v){for(int y=p.y();y<p.y()+p.h();y++)for(int x=p.x();x<p.x()+p.w();x++)grid[y][x]=v;}
}
