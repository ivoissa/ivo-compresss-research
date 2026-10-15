package io.ivo.mp3;
/** Exact canonical emission codes derived from the Layer III decoding tables. */
final class HuffmanCodes {
 record Code(int bits,int width){}
 static Code[] codes(int table){Code[] a=new Code[256];walk(a,HuffmanLookup.INDEX[table],0,5,0,0);return a;}
 static void walk(Code[] a,int base,int offset,int width,int prefix,int used){
  if(used+width>24)throw new AssertionError("Tree depth");
  for(int i=0;i<(1<<width);i++){
   int leaf=HuffmanLookup.TABS[base+offset+i];
   if(leaf<0)walk(a,base,-(leaf>>3),leaf&7,(prefix<<width)|i,used+width);
   else{int n=leaf>>8,symbol=leaf&255;Code c=new Code((prefix<<n)|(i>>>(width-n)),used+n);
    if(a[symbol]!=null&&!a[symbol].equals(c))throw new AssertionError("Ambiguous symbol");a[symbol]=c;}
  }
 }
}
