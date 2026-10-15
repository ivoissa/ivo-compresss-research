package io.ivo.usercode;

import io.ivo.engine.*;
import java.util.Arrays;

/** User-supplied prototypes with frozen default models. Decode registered; compression comparison is explicitly enabled by the caller. */
public final class UserCodecEngine implements CompressionEngine {
    public enum Kind { MP3, MP4, WEBP }
    private final Kind kind;
    private final boolean compare;
    private static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    public UserCodecEngine(Kind kind){this(kind, false);}
    public UserCodecEngine(Kind kind, boolean compare){this.kind=java.util.Objects.requireNonNull(kind);this.compare=compare;}
    public EngineId id(){return switch(kind){case MP3->EngineId.USER_MP3_RESEARCH;case MP4->EngineId.USER_MP4_CABAC_RESEARCH;case WEBP->EngineId.USER_WEBP_RESEARCH;};}
    public String name(){return "User "+kind+" frozen-model research";}
    public boolean supports(byte[] input,String name){return compare && eligible(input);}
    private boolean eligible(byte[] input) {
        if (input.length < 12 || input.length > MAX_INPUT_BYTES) return false;
        return switch (kind) {
            case MP3 -> input[0]=='I' && input[1]=='D' && input[2]=='3' || Mp3.frameLen(input,0)>0;
            case MP4 -> input[4]=='f' && input[5]=='t' && input[6]=='y' && input[7]=='p';
            case WEBP -> input[0]=='R' && input[1]=='I' && input[2]=='F' && input[3]=='F'
                    && input[8]=='W' && input[9]=='E' && input[10]=='B' && input[11]=='P';
        };
    }
    public CompressionCandidate compress(byte[] input,String name){
        if (!supports(input,name)) throw new IllegalArgumentException("User codec comparison disabled or source outside trial limits");
        return new CompressionCandidate(id(), encodePayload(input), "frozen profile; exactness checked by Router");
    }
    private byte[] encodePayload(byte[] input) {
        if (!eligible(input)) throw new IllegalArgumentException("Unsupported signature or input exceeds 32 MiB trial limit");
        try { return switch(kind){case MP3->Mp3.compress(input);case MP4->Mp4.compress(input);case WEBP->Ivo.compress(input);}; }
        catch(RuntimeException ex){throw ex;} catch(Exception ex){throw new IllegalArgumentException("User codec encode",ex);}
    }
    public byte[] encode(byte[] input) {
        try {
            byte[] payload=encodePayload(input);
            if(!Arrays.equals(input,decompress(payload)))throw new IllegalStateException("User codec whole-file roundtrip mismatch");
            return payload;
        } catch(RuntimeException ex){throw ex;} catch(Exception ex){throw new IllegalArgumentException("User codec encode",ex);}
    }
    public byte[] decompress(byte[] payload) {
        String expected=switch(kind){case MP3->"MP32";case MP4->"M4V2";case WEBP->"IVO1";};
        if(payload.length>UserCodecIO.MAX_BYTES)throw new IllegalArgumentException("User codec payload limit");
        if(payload.length<4)throw new IllegalArgumentException("Truncated user codec payload");
        for(int i=0;i<4;i++)if((payload[i]&255)!=expected.charAt(i))throw new IllegalArgumentException("Wrong user codec payload signature");
        try{return switch(kind){case MP3->Mp3.decompress(payload);case MP4->Mp4.decompress(payload);case WEBP->Ivo.decompress(payload);};}
        catch(RuntimeException ex){throw new IllegalArgumentException("Invalid "+kind+" payload",ex);}catch(Exception ex){throw new IllegalArgumentException("User codec decode",ex);}
    }
}
