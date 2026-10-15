package io.ivo.usercode;

import java.nio.file.*;
import java.util.*;

/** Diagnostic only: count rejected granules with an exact prefix and 1..32 residual bits. */
public final class Mp3CoverageAudit {
    public static void main(String[] args) throws Exception {
        Path inputPath=Path.of(args[0]); byte[] data=Files.readAllBytes(inputPath);
        int start=0;
        if(data.length>10 && data[0]=='I' && data[1]=='D' && data[2]=='3')
            start=10+((data[6]&127)<<21)+((data[7]&127)<<14)+((data[8]&127)<<7)+(data[9]&127)+((data[5]&16)!=0?10:0);
        var frames=Mp3.findFrames(data,start,new int[1]); int length=0;
        for(var frame:frames){frame.pStart=length;length+=frame.len-frame.hdrLen;frame.pEnd=length;}
        byte[] main=new byte[length+8];
        for(var frame:frames)System.arraycopy(data,frame.off+frame.hdrLen,main,frame.pStart,frame.pEnd-frame.pStart);
        int exact=0,rejected=0,shortRejected=0,emptyRejected=0,prefixCandidates=0,residualBits=0;
        var histogram=new TreeMap<Integer,Integer>();
        for(var frame:frames){
            Mp3.parseSide(frame,Arrays.copyOfRange(data,frame.off,frame.off+frame.hdrLen));
            long position=((long)frame.pStart-frame.mdb)*8;
            for(var row:frame.gr)for(var granule:row){
                granule.start=position;position+=granule.len;
                if(granule.start<0 || granule.start+granule.len>(long)frame.pEnd*8){rejected++;continue;}
                if(exactPrefix(granule,main)){exact++;continue;}
                rejected++;if(granule.len==0)emptyRejected++;if(granule.ws==1 && granule.bt==2)shortRejected++;
                int original=granule.len;
                for(int tail=1;tail<=32 && tail<original;tail++){
                    granule.len=original-tail;Arrays.fill(granule.ix,0);Arrays.fill(granule.sf,0);
                    if(exactPrefix(granule,main)){prefixCandidates++;residualBits+=tail;histogram.merge(tail,1,Integer::sum);break;}
                }
                granule.len=original;
            }
        }
        System.out.printf("frames=%d exact=%d rejected=%d shortRejected=%d emptyRejected=%d exact_prefix_candidates=%d residual_bits=%d tails=%s%n",frames.size(),exact,rejected,shortRejected,emptyRejected,prefixCandidates,residualBits,histogram);
        System.out.println("Diagnostic only: metadata cost, overlaps, full-file coding and independent sources remain unmeasured.");
    }
    private static boolean exactPrefix(Mp3.Gr granule,byte[] main){
        try{
            if(granule.len<=0 || !Mp3.parse(granule,main) || Mp3.computeUsed(granule)!=granule.len)return false;
            byte[] rebuilt=new byte[(granule.len+7)/8+8];Mp3.encodeBits(granule,rebuilt,0);
            var original=new Mp3.BR(main,granule.start);var check=new Mp3.BR(rebuilt,0);
            for(int bit=0;bit<granule.len;bit++)if(original.bit()!=check.bit())return false;
            return true;
        }catch(RuntimeException unsupported){return false;}
    }
}
