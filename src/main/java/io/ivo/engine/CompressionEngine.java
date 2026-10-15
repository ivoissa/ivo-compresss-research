package io.ivo.engine;

public interface CompressionEngine {
    EngineId id();
    String name();
    boolean supports(byte[] input, String fileName);
    CompressionCandidate compress(byte[] input, String fileName);
    byte[] decompress(byte[] payload);
}
