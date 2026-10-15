package io.ivo.zip;

import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.CompressionEngine;
import io.ivo.engine.EngineId;
import io.ivo.research.contrib.OwnerBayesianZipCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;

/**
 * Runtime adapter for the owner's exact Bayesian ZIP/DEFLATE contribution.
 *
 * <p>The payload stores the original length because {@link CompressionEngine}
 * decoders only receive their payload. The outer IVO container remains the
 * sole owner of the filename and SHA-256 integrity check.</p>
 */
public final class ZipBayesianEngine implements CompressionEngine {
    @Override public EngineId id() { return EngineId.ZIP_BAYES_V1; }
    @Override public String name() { return "ZIP Bayes V1 (DEFLATE exact)"; }

    @Override
    public boolean supports(byte[] input, String fileName) {
        return input.length >= 4 && input[0] == 'P' && input[1] == 'K'
                && (input[2] == 3 || input[2] == 5 || input[2] == 7)
                && (input[3] == 4 || input[3] == 6 || input[3] == 8);
    }

    @Override
    public CompressionCandidate compress(byte[] input, String fileName) {
        StringBuilder info = new StringBuilder();
        byte[] bayes = OwnerBayesianZipCodec.compressPayload(input, info);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.writeUnsigned(out, input.length);
        out.writeBytes(bayes);
        return new CompressionCandidate(id(), out.toByteArray(), info.toString());
    }

    @Override
    public byte[] decompress(byte[] payload) {
        VarInt.IntRef p = new VarInt.IntRef(0);
        int originalLength = Math.toIntExact(VarInt.readUnsigned(payload, p));
        if (p.value >= payload.length) throw new IllegalArgumentException("Payload ZIP Bayes tronque");
        byte[] bayes = java.util.Arrays.copyOfRange(payload, p.value, payload.length);
        return OwnerBayesianZipCodec.decompressPayload(bayes, originalLength);
    }
}
