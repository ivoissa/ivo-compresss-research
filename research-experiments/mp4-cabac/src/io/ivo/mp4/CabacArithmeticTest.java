package io.ivo.mp4;
public final class CabacArithmeticTest {
 public static void main(String[] args){
  int cases=0;
  // One bypass step for every legal initialized offset and both possible next input bits.
  for(int initial=0;initial<510;initial++)for(int bit=0;bit<=1;bit++){
   byte[] source={(byte)(initial>>>1),(byte)((initial&1)<<7|bit<<6)};
   var d=new CabacArithmetic(source,0);int combined=2*initial+bit,expected=combined>=510?1:0;
   if(d.bypass()!=expected||d.offset()!=combined-expected*510||d.range()!=510||d.bitsRead()!=10)throw new AssertionError("Bypass primitive");cases++;
  }
  for(int initial:new int[]{507,508,509}){
   var d=new CabacArithmetic(new byte[]{(byte)(initial>>>1),(byte)((initial&1)<<7)},0);
   int terminal=d.terminate();if(terminal!=(initial>=508?1:0)||d.range()!=508||d.bitsRead()!=9)throw new AssertionError("Termination boundary");
   if(terminal==1){boolean fail=false;try{d.bypass();}catch(IllegalStateException ex){fail=true;}if(!fail)throw new AssertionError("Use after termination");}
  }
  var d=new CabacArithmetic(new byte[64],0);for(int i=0;i<128;i++)if(d.terminate()!=0)throw new AssertionError("Zero trace");
  if(d.range()!=508||d.bitsRead()!=10)throw new AssertionError("Renormalization boundary");
  for(int initial:new int[]{510,511}){boolean fail=false;try{new CabacArithmetic(new byte[]{(byte)(initial>>>1),(byte)((initial&1)<<7)},0);}catch(IllegalArgumentException ex){fail=true;}if(!fail)throw new AssertionError("Forbidden initialization");}
  System.out.println("PASS bypass_steps="+cases+" termination_boundaries=3 renormalization=true forbidden_offsets=2; not a full CABAC decoder/encoder");
 }
}
