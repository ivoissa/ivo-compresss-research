package io.ivo.mp4;

import java.util.*;

/** Quantized syntax coefficients, before inverse scan, dequantization or pixel reconstruction.
 * First non-skipped INTER P macroblock only; all earlier neighbors are skipped.
 * Progressive eight-bit 4:2:0 only. Contexts survive across all blocks of this MB. */
final class CabacResidual {
    interface Decisions { int regular(int context); int bypass(); }
    static final class Input implements Decisions {
        private final CabacArithmetic arithmetic;
        private final CabacArithmetic.Context[] contexts;
        Input(CabacArithmetic arithmetic,int init,int qp) {
            this.arithmetic=arithmetic; contexts=CabacResidualContexts.initialize(init,qp);
        }
        public int regular(int c) {
            if(c<0||c>=contexts.length||contexts[c]==null) throw new IllegalArgumentException("residue context "+c);
            return arithmetic.regular(contexts[c]);
        }
        public int bypass(){return arithmetic.bypass();}
    }
    record Block(int category,int index,int[] coefficients) {}
    record Result(int cbp,int transform8,int deltaQp,int qp,List<Block> blocks) {}
    private static final int[] SIG={105,120,134,149,152,402};
    private static final int[] LAST={166,181,195,210,213,417};
    private static final int[] LEVEL={227,237,247,257,266,426};
    private static final int[] SIG8={0,1,2,3,4,5,5,4,4,3,3,4,4,4,5,5,4,4,4,4,3,3,6,7,7,7,8,9,10,9,8,7,7,6,11,12,13,11,6,7,8,9,14,10,9,8,6,11,12,13,11,6,9,14,10,9,11,12,13,11,14,10,12};
    private static final int[] LAST8={0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,3,3,3,3,3,3,3,3,4,4,4,4,4,4,4,4,5,5,5,5,6,6,6,6,7,7,7,7,8,8,8};
    static int[] coefficients(Decisions in,int cat,int size) {
        if(!((cat==2&&size==16)||(cat==3&&size==4)||(cat==4&&size==15)||(cat==5&&size==64)))
            throw new IllegalArgumentException("restricted coefficient category/size");
        int[] result=new int[size],positions=new int[size]; int count=0;
        boolean last=false;
        for(int p=0;p<size-1;p++) {
            if(in.regular(SIG[cat]+(cat==5?SIG8[p]:p))==1) {
                positions[count++]=p;
                if(in.regular(LAST[cat]+(cat==5?LAST8[p]:p))==1){last=true;break;}
            }
        }
        if(!last) positions[count++]=size-1;
        int ones=0,greater=0;
        while(count>0) {
            int p=positions[--count],value=1;
            int first=greater>0?0:Math.min(4,ones+1);
            if(in.regular(LEVEL[cat]+first)==1) {
                int context=LEVEL[cat]+5+Math.min(4,greater); value=2;
                while(value<15&&in.regular(context)==1)value++;
                if(value==15) {
                    int k=0;
                    while(in.bypass()==1){if(++k>23)throw new IllegalArgumentException("coefficient overflow");}
                    int suffix=1;
                    for(int i=0;i<k;i++)suffix=(suffix<<1)|in.bypass();
                    value=14+suffix;
                }
                greater++;
            } else ones++;
            result[p]=in.bypass()==1?-value:value;
        }
        return result;
    }
    static int cbp(Decisions in,int left,int top) {
        int value=0;
        value|=in.regular(73+((left&2)==0?1:0)+((top&4)==0?2:0));
        value|=in.regular(73+((value&1)==0?1:0)+((top&8)==0?2:0))<<1;
        value|=in.regular(73+((left&8)==0?1:0)+((value&1)==0?2:0))<<2;
        value|=in.regular(73+((value&4)==0?1:0)+((value&2)==0?2:0))<<3;
        int a=(left>>4)&3,b=(top>>4)&3;
        if(in.regular(77+(a>0?1:0)+(b>0?2:0))==1)
            value|=(1+in.regular(81+(a==2?1:0)+(b==2?2:0)))<<4;
        return value;
    }
    static int deltaQp(Decisions in) {
        if(in.regular(60)==0)return 0;
        int unary=1;
        while(in.regular(unary==1?62:63)==1)if(++unary>102)throw new IllegalArgumentException("QP delta overflow");
        return (unary&1)==1?(unary+1)/2:-(unary+1)/2;
    }
    static Result decode(Decisions in,int mb,int width,int qp,boolean allow8) {
        if(mb<0||width<1||qp<0||qp>51)throw new IllegalArgumentException("residue macroblock");
        int cbp=cbp(in,mb%width==0?15:0,mb<width?15:0);
        int transform=allow8&&(cbp&15)!=0?in.regular(399):0;
        int delta=cbp!=0?deltaQp(in):0;
        var blocks=new ArrayList<Block>(); int[][] nz=new int[4][4];
        for(int group=0;group<4;group++) {
            if((cbp&(1<<group))==0)continue;
            if(transform==1)blocks.add(new Block(5,4*group,coefficients(in,5,64)));
            else for(int sub=0;sub<4;sub++) {
                int x=2*(group%2)+sub%2,y=2*(group/2)+sub/2;
                int context=93+(x>0&&nz[y][x-1]>0?1:0)+(y>0&&nz[y-1][x]>0?2:0);
                int[] c=in.regular(context)==1?coefficients(in,2,16):new int[16];
                nz[y][x]=(int)Arrays.stream(c).filter(v->v!=0).count();blocks.add(new Block(2,4*group+sub,c));
            }
        }
        if((cbp&48)!=0)for(int plane=0;plane<2;plane++)
            blocks.add(new Block(3,49+plane,in.regular(97)==1?coefficients(in,3,4):new int[4]));
        if((cbp&32)!=0)for(int plane=0;plane<2;plane++) {
            int[][] chroma=new int[2][2];
            for(int b=0;b<4;b++) {
                int x=b%2,y=b/2,context=101+(x>0&&chroma[y][x-1]>0?1:0)+(y>0&&chroma[y-1][x]>0?2:0);
                int[] c=in.regular(context)==1?coefficients(in,4,15):new int[15];
                chroma[y][x]=(int)Arrays.stream(c).filter(v->v!=0).count();blocks.add(new Block(4,16+16*plane+b,c));
            }
        }
        return new Result(cbp,transform,delta,Math.floorMod(qp+delta,52),List.copyOf(blocks));
    }
}
