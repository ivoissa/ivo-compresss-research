package io.ivo.mp4;
/** Fixed integer goldens evaluated from the normative interval boundaries, not encoder round-trips. */
public final class CabacRegularTest {
 static byte[] source(int offset,String suffix){
  String bits=String.format("%9s",Integer.toBinaryString(offset)).replace(' ','0')+suffix;
  byte[] out=new byte[(bits.length()+7)/8];for(int i=0;i<bits.length();i++)if(bits.charAt(i)=='1')out[i/8]|=1<<(7-i%8);return out;
 }
 static void check(int state,int mps,int initial,String suffix,int bin,int nextState,int nextMps,int range,int offset,int bits){
  var d=new CabacArithmetic(source(initial,suffix),0);var c=new CabacArithmetic.Context(state,mps);
  if(d.regular(c)!=bin||c.state()!=nextState||c.mps()!=nextMps||d.range()!=range||d.offset()!=offset||d.bitsRead()!=bits)throw new AssertionError("regular golden state="+state+" initial="+initial);
 }
 static void init(int m,int n,int qp,int state,int mps){var c=CabacArithmetic.Context.initialize(m,n,qp);if(c.state()!=state||c.mps()!=mps)throw new AssertionError("Context initialization");}
 public static void main(String[] args){
  // State 0 at range 510: MPS interval [0,270), LPS [270,510); LPS renormalizes once.
  check(0,0,0,"00000000",0,1,0,270,0,9);
  check(0,0,269,"00000000",0,1,0,270,269,9);
  check(0,0,270,"00000000",1,0,1,480,0,10);
  check(0,1,509,"10000000",0,0,0,480,479,10);
  // State 62: LPS=9; five renormalization bits. MPS state saturates at 62.
  check(62,1,500,"00000000",1,62,1,501,500,9);
  check(62,1,501,"10101000",0,38,1,288,21,14);
  check(62,0,509,"10101000",1,38,0,288,277,14);
  // State 63 table entry exercises seven-bit renormalization; not ordinary initialized contexts.
  check(63,0,508,"11111110",1,63,0,256,127,16);
  check(63,0,507,"00000000",0,63,0,508,507,9);
  init(0,63,22,0,0);init(0,64,22,0,1);init(22,25,22,8,0);
  init(-28,127,51,26,0);init(-128,-128,51,62,0);init(127,127,51,62,1);
  init(22,25,-10,38,0);init(22,25,100,31,1);
  boolean rejected=false;try{new CabacArithmetic.Context(-1,0);}catch(IllegalArgumentException ex){rejected=true;}if(!rejected)throw new AssertionError("Invalid state");
  System.out.println("PASS regular_goldens=9 context_initialization_goldens=8 invalid_state=1; no complete slice claim");
 }
}
