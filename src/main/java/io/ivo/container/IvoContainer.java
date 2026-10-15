package io.ivo.container;

import io.ivo.engine.EngineId;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** IVO1 container. Small, deterministic, and independent of engine internals. */
public final class IvoContainer {
    private static final byte[] MAGIC = {'I','V','O','1'};
    private IvoContainer() {}

    public static byte[] pack(IvoArchive archive) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(1); // container version
        out.write(archive.engineId().code());
        VarInt.writeUnsigned(out, archive.originalSize());
        byte[] name = archive.originalName().getBytes(StandardCharsets.UTF_8);
        VarInt.writeUnsigned(out, name.length);
        out.writeBytes(name);
        if (archive.sha256().length != 32) throw new IllegalArgumentException("SHA-256 must be 32 bytes");
        out.writeBytes(archive.sha256());
        VarInt.writeUnsigned(out, archive.payload().length);
        out.writeBytes(archive.payload());
        return out.toByteArray();
    }

    public static IvoArchive unpack(byte[] bytes) {
        if (bytes.length < 6) throw new IllegalArgumentException("Too short");
        for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) throw new IllegalArgumentException("Not an IVO1 file");
        VarInt.IntRef p = new VarInt.IntRef(4);
        int version = bytes[p.value++] & 0xFF;
        if (version != 1) throw new IllegalArgumentException("Unsupported IVO version " + version);
        EngineId engine = EngineId.fromCode(bytes[p.value++] & 0xFF);
        long originalSize = VarInt.readUnsigned(bytes, p);
        int nameLen = Math.toIntExact(VarInt.readUnsigned(bytes, p));
        if (p.value + nameLen + 32 > bytes.length) throw new IllegalArgumentException("Truncated IVO header");
        String name = new String(bytes, p.value, nameLen, StandardCharsets.UTF_8);
        p.value += nameLen;
        byte[] sha = Arrays.copyOfRange(bytes, p.value, p.value + 32);
        p.value += 32;
        int payloadLen = Math.toIntExact(VarInt.readUnsigned(bytes, p));
        if (p.value + payloadLen != bytes.length) throw new IllegalArgumentException("Bad IVO payload length");
        byte[] payload = Arrays.copyOfRange(bytes, p.value, bytes.length);
        return new IvoArchive(engine, originalSize, name, sha, payload);
    }
}
