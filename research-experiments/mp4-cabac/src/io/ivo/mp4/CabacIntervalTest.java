package io.ivo.mp4;
import java.math.BigInteger;
import java.util.Random;
/** Independent whole-code interval representation: no incremental input-bit reader. */
public final class CabacIntervalTest {
 static final class Interval {
  final BigInteger code;BigInteger lower=BigInteger.ZERO,quantum;
  int range=510,bits=9;int[] states,mps;
  Interval(byte[] bytes,int[] state,int[] majority){code=new BigInteger(1,bytes);quantum=BigInteger.ONE.shiftLeft(bytes.length*8-9);states=state.clone();mps=majority.clone();}
  int offset(){return code.subtract(lower).divide(quantum).intValueExact();}
  void normalize(){int shift=0;while((range<<shift)<256)shift++;range<<=shift;quantum=quantum.shiftRight(shift);bits+=shift;}
  int regular(int id){
   int old=states[id],least=CabacTables.RANGE_LPS[old][range>>>6&3],major=range-least;
   BigInteger boundary=lower.add(quantum.multiply(BigInteger.valueOf(major)));
   if(code.compareTo(boundary)<0){range=major;states[id]=CabacTables.NEXT_MPS[old];int value=mps[id];normalize();return value;}
   lower=boundary;range=least;int value=1-mps[id];states[id]=CabacTables.NEXT_LPS[old];if(old==0)mps[id]^=1;normalize();return value;
  }
  int bypass(){quantum=quantum.shiftRight(1);bits++;BigInteger split=lower.add(quantum.multiply(BigInteger.valueOf(range)));if(code.compareTo(split)<0)return 0;lower=split;return 1;}
  int terminate(){range-=2;if(code.compareTo(lower.add(quantum.multiply(BigInteger.valueOf(range))))>=0)return 1;normalize();return 0;}
 }
 public static void main(String[] args){
  Random random=new Random(0x4341424143L);int decisions=0,regular=0,bypass=0,terminated=0;
  for(int stream=0;stream<2000;stream++){
   byte[] source=new byte[512];random.nextBytes(source);source[0]=(byte)((source[0]&255)%255);
   int[] states=new int[16],majority=new int[16];var contexts=new CabacArithmetic.Context[16];
   for(int i=0;i<16;i++){states[i]=random.nextInt(64);majority[i]=random.nextInt(2);contexts[i]=new CabacArithmetic.Context(states[i],majority[i]);}
   var d=new CabacArithmetic(source,0);var reference=new Interval(source,states,majority);
   for(int step=0;step<300;step++){
    int operation=step%13,id=random.nextInt(16),actual,expected;
    if(operation==12){actual=d.terminate();expected=reference.terminate();}
    else if(operation%4==3){actual=d.bypass();expected=reference.bypass();bypass++;}
    else{actual=d.regular(contexts[id]);expected=reference.regular(id);regular++;}
    decisions++;
    if(actual!=expected||d.range()!=reference.range||d.offset()!=reference.offset()||d.bitsRead()!=reference.bits)throw new AssertionError("Interval disagreement stream="+stream+" step="+step);
    for(int i=0;i<16;i++)if(contexts[i].state()!=reference.states[i]||contexts[i].mps()!=reference.mps[i])throw new AssertionError("Context disagreement");
    if(operation==12&&actual==1){terminated++;break;}
   }
  }
  System.out.println("PASS interval_streams=2000 decisions="+decisions+" regular="+regular+" bypass="+bypass+" terminated="+terminated+"; code-value oracle authored here, not external codec validation");
 }
}
