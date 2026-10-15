#include <stdio.h>
#include <stdlib.h>
#define MINIMP3_IMPLEMENTATION
#include "minimp3-oracle.h"
int main(int argc,char**argv){FILE*f=fopen(argv[1],"rb");if(!f)return 2;fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);unsigned char*b=calloc(n+16,1);if(fread(b,1,n,f)!=(size_t)n)return 3;fclose(f);mp3dec_t d;mp3dec_init(&d);mp3dec_frame_info_t info;mp3d_sample_t pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];long pos=0,frames=0;while(pos<n){mp3dec_decode_frame(&d,b+pos,n-pos,pcm,&info);if(info.frame_bytes<=0)break;pos+=info.frame_bytes;frames++;}printf("ORACLE frames=%ld parts=%llu pairs=%llu scalefactorBits=%llu bigHash=%016llx quads=%llu quadHash=%016llx\n",frames,(unsigned long long)ivo_parts,(unsigned long long)(ivo_values/2),(unsigned long long)ivo_scf_bits,(unsigned long long)ivo_hash,(unsigned long long)ivo_quads,(unsigned long long)ivo_quad_hash);free(b);}
