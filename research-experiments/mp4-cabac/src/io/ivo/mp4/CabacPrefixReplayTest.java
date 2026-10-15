package io.ivo.mp4;
import java.util.*;
/** Exercise inversion on varied probability states, all three arithmetic primitives and termination. */
public final class CabacPrefixReplayTest {
    public static void main(String[] args) {
        Random random=new Random(0x52455349445545L);int streams=200,rejected=0,terminations=0,bits=0;
        for(int n=0;n<streams;n++) {
            byte[] source=new byte[512];random.nextBytes(source);source[0]=(byte)((source[0]&255)%255);
            var replay=new CabacPrefixReplay();var arithmetic=new CabacArithmetic(source,0,replay);
            var contexts=new CabacArithmetic.Context[16];for(int i=0;i<16;i++)contexts[i]=new CabacArithmetic.Context(random.nextInt(64),random.nextInt(2));
            for(int i=0;i<250;i++) {
                if(i%13==12){if(arithmetic.terminate()==1){terminations++;break;}}
                else if(i%4==3)arithmetic.bypass();
                else arithmetic.regular(contexts[random.nextInt(16)]);
            }
            bits+=replay.verify(source,0,arithmetic);
            source[0]^=(byte)128;
            try{replay.verify(source,0,arithmetic);throw new AssertionError("Altered prefix accepted");}catch(AssertionError expected){if(expected.getMessage().equals("Altered prefix accepted"))throw expected;rejected++;}
        }
        System.out.println("PASS prefix_replay_streams="+streams+" reconstructed_bits="+bits+" terminal_ones="+terminations+" altered_prefixes_rejected="+rejected+"; observer-state diagnostic, not syntax encoder");
    }
}
