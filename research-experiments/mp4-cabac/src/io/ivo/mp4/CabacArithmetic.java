package io.ivo.mp4;
/** H.264 arithmetic primitives only. Caller must supply the real syntax schedule. */
final class CabacArithmetic {
 interface Observer { void regular(int state,int mps,int bin); void bypass(int bin); void terminate(int bin); }
 private final Observer observer;
 private final byte[] bytes;private int position;private int range=510,offset;private boolean terminated;
 CabacArithmetic(byte[] rbsp,int byteStart){this(rbsp,byteStart,null);}
 CabacArithmetic(byte[] rbsp,int byteStart,Observer observer){
  if(byteStart<0||byteStart>rbsp.length)throw new IllegalArgumentException("CABAC start");
  this.observer=observer;bytes=rbsp.clone();position=Math.multiplyExact(byteStart,8);
  for(int i=0;i<9;i++)offset=offset*2+readBit();
  if(offset>=510)throw new IllegalArgumentException("CABAC initial offset");
 }
 private int readBit(){if(position>=bytes.length*8L)throw new IllegalArgumentException("Truncated CABAC");int b=(bytes[position/8]>>>(7-position%8))&1;position++;return b;}
 private void active(){if(terminated)throw new IllegalStateException("CABAC terminated");}
 int bypass(){active();offset=offset*2+readBit();int bin=0;if(offset>=range){offset-=range;bin=1;}if(observer!=null)observer.bypass(bin);return bin;}
 int terminate(){active();range-=2;if(offset>=range){terminated=true;if(observer!=null)observer.terminate(1);return 1;}while(range<256){range*=2;offset=offset*2+readBit();}if(observer!=null)observer.terminate(0);return 0;}
 static final class Context {
  private int state,mps;
  Context(int state,int mps){if(state<0||state>63||mps<0||mps>1)throw new IllegalArgumentException("CABAC context");this.state=state;this.mps=mps;}
  static Context initialize(int m,int n,int sliceQp){
   if(m<-128||m>127||n<-128||n>127)throw new IllegalArgumentException("CABAC initialization coefficients");
   int qp=Math.max(0,Math.min(51,sliceQp));
   int pre=Math.max(1,Math.min(126,((m*qp)>>4)+n));
   return pre<=63?new Context(63-pre,0):new Context(pre-64,1);
  }
  int state(){return state;}int mps(){return mps;}
 }
 int regular(Context context){
  active();int old=context.state,majority=context.mps,least=CabacTables.RANGE_LPS[old][range>>>6&3];
  range-=least;int bin;
  if(offset>=range){
   offset-=range;range=least;bin=1-context.mps;
   if(old==0)context.mps^=1;
   context.state=CabacTables.NEXT_LPS[old];
  }else{bin=context.mps;context.state=CabacTables.NEXT_MPS[old];}
  while(range<256){range*=2;offset=offset*2+readBit();}
  if(observer!=null)observer.regular(old,majority,bin);return bin;
 }
 int range(){return range;}int offset(){return offset;}int bitsRead(){return position;}
}
