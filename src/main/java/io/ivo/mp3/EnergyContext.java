package io.ivo.mp3;
/** Raw amplitudes, contexts derived from completed 16-coefficient groups. No amplitude prediction. */
final class EnergyContext {
 static int level(int[] magnitudes,int first){if(first<0)return 0;long square=0;for(int i=first;i<first+16;i++)square+=(long)magnitudes[i]*magnitudes[i];return square<16?0:square<256?1:square<4096?2:3;}
 static int cord(int scheme,int channel,int position,int[] current,int[] previous,boolean compatible){
  if(scheme<15||scheme>18||position<0||position>=576)throw new IllegalArgumentException("Energy context");
  int start=position/16*16;
  int bucket=switch(scheme){case 15->level(current,start-16);case 16->compatible?level(previous,start):0;case 17->(position/16)%4;case 18->0;default->throw new AssertionError();};
  int band=position<16?0:position<64?1:position<256?2:3;
  return 40+(channel*4+band)*4+bucket;
 }
}
