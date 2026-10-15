package io.ivo.container;

import io.ivo.engine.EngineId;

public record IvoArchive(
        EngineId engineId,
        long originalSize,
        String originalName,
        byte[] sha256,
        byte[] payload) {
}
