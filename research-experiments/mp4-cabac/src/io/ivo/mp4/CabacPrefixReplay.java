package io.ivo.mp4;

import java.math.BigInteger;

/** Arithmetic prefix reconstruction without reading source bits.
 * Diagnostic observer receives real bins and pre-decision probability states.
 * The final offset is necessary: bins alone do not select the original code value.
 * This is not yet an encoder from coefficient syntax, nor a compression format. */
final class CabacPrefixReplay implements CabacArithmetic.Observer {
    private BigInteger lower=BigInteger.ZERO;
    private int range=510,bits=9,decisions;
    private void normalize(){while(range<256){range*=2;lower=lower.shiftLeft(1);bits++;}}
    public void regular(int state,int mps,int bin) {
        int least=CabacTables.RANGE_LPS[state][range>>>6&3],major=range-least;
        if(bin==mps)range=major;
        else {lower=lower.add(BigInteger.valueOf(major));range=least;}
        normalize();decisions++;
    }
    public void bypass(int bin) {
        lower=lower.shiftLeft(1);bits++;
        if(bin==1)lower=lower.add(BigInteger.valueOf(range));decisions++;
    }
    public void terminate(int bin) {range-=2;if(bin==0)normalize();decisions++;}
    int verify(byte[] rbsp,int byteStart,CabacArithmetic decoder) {
        if(range!=decoder.range()||bits!=decoder.bitsRead()-byteStart*8)throw new AssertionError("Replay arithmetic boundary");
        BigInteger prefix=lower.add(BigInteger.valueOf(decoder.offset()));
        if(prefix.bitLength()>bits)throw new AssertionError("Replay prefix overflow");
        for(int i=0;i<bits;i++) {
            int actual=(rbsp[byteStart+i/8]>>>(7-i%8))&1;
            if(actual!=(prefix.testBit(bits-1-i)?1:0))throw new AssertionError("Replay differs at bit "+i);
        }
        return bits;
    }
    int decisions(){return decisions;}
}
