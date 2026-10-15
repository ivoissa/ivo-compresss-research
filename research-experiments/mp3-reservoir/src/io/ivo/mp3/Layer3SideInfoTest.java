package io.ivo.mp3;
import java.util.*;
/** Independent fixed-offset fixtures, covering both side-info branches and channel counts. */
public class Layer3SideInfoTest {
    static void put(byte[] bytes,int pos,int width,int value){for(int b=0;b<width;b++){int p=pos+b;bytes[p/8]=(byte)((bytes[p/8]&~(1<<(7-p%8)))|(((value>>>(width-1-b))&1)<<(7-p%8)));}}
    static byte[] fixture(int version,boolean mono,int back,boolean switched){
        boolean one=version==1;int channels=mono?1:2;byte[] side=new byte[one?(mono?17:32):(mono?9:17)];new Random(17+version).nextBytes(side);put(side,0,one?9:8,back);
        int start=one?(mono?18:20):(mono?9:10),stride=one?59:63;
        for(int i=0;i<(one?2:1)*channels;i++){
            int p=start+i*stride;put(side,p,12,37+i*13);put(side,p+12,9,17+i);put(side,p+(one?33:38),1,switched?1:0);
            if(switched)put(side,p+(one?34:39),2,2);
        }return side;
    }
    static void reject(Runnable call){try{call.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){int cases=0;for(int v:new int[]{1,2,25})for(boolean mono:new boolean[]{false,true})for(boolean switched:new boolean[]{false,true})for(int back:new int[]{0,v==1?511:255}){
        byte[] f=fixture(v,mono,back,switched);var r=Layer3SideInfo.read(f,0,f.length,v,mono);int count=(v==1?2:1)*(mono?1:2),sum=37*count+13*count*(count-1)/2;
        if(r.mainDataBegin()!=back||r.part23Bits()!=sum)throw new AssertionError("Usage fields mismatch");
        byte[] padded=new byte[f.length+7];System.arraycopy(f,0,padded,3,f.length);if(!r.equals(Layer3SideInfo.read(padded,3,f.length,v,mono)))throw new AssertionError("Offset");cases++;
    }
    byte[] f=fixture(2,true,0,false);reject(()->Layer3SideInfo.read(f,0,8,2,true));reject(()->Layer3SideInfo.read(f,1,9,2,true));reject(()->Layer3SideInfo.read(f,0,9,3,true));
    byte[] invalid=f.clone();put(invalid,9+12,9,511);reject(()->Layer3SideInfo.read(invalid,0,9,2,true));
    byte[] switched=fixture(25,true,0,true);put(switched,9+39,2,0);reject(()->Layer3SideInfo.read(switched,0,9,25,true));
    System.out.println("PASS "+cases+" known-offset side-info cases, offset cases and 5 rejection cases");}
}
