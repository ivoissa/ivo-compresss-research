package io.ivo.engine;

public record CompressionCandidate(EngineId engineId, byte[] payload, String note) {
    public int payloadSize() { return payload.length; }
}
