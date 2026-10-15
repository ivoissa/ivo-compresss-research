package io.ivo.pdf;

import io.ivo.jpeg.JpegDcExperiment;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PDF-IMAGE V1 research codec.
 *
 * It treats the PDF as an exact byte sequence, replaces every supported embedded
 * JPEG by its exact V6.1 representation, and preserves all bytes between images
 * in one shared skeleton cord. It does not rewrite PDF objects or xref offsets.
 */
public final class PdfJpegV1Experiment {
    private static final byte[] MAGIC = {'P', 'D', 'J', '1'};
    private PdfJpegV1Experiment() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("PDF file required");
        for (String arg : args) {
            Path path = Path.of(arg);
            byte[] input = Files.readAllBytes(path);
            long started = System.nanoTime();
            Encoded encoded = encode(input);
            long encodedAt = System.nanoTime();
            byte[] restored = decode(encoded.payload);
            long decodedAt = System.nanoTime();
            if (!Arrays.equals(input, restored)) throw new AssertionError("PDF-IMAGE mismatch: " + path);
            int ivoEstimate = encoded.payload.length + 64;
            System.out.printf("PDF_IMAGE_V1 %s original=%d jpeg_streams=%d jpeg_bytes=%d payload=%d ivo_estimate=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                    path.getFileName(), input.length, encoded.jpegStreams, encoded.jpegBytes,
                    encoded.payload.length, ivoEstimate, 100.0 * (input.length - ivoEstimate) / input.length,
                    (encodedAt - started) / 1_000_000.0, (decodedAt - encodedAt) / 1_000_000.0);
        }
    }

    public static Encoded encode(byte[] pdf) {
        List<Range> images = findSupportedJpegs(pdf);
        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        ByteArrayOutputStream skeleton = new ByteArrayOutputStream();
        List<byte[]> jpegPayloads = new ArrayList<>();
        VarInt.writeUnsigned(layout, images.size());
        int cursor = 0, jpegBytes = 0;
        for (Range image : images) {
            int rawLength = image.start - cursor;
            VarInt.writeUnsigned(layout, rawLength);
            VarInt.writeUnsigned(layout, image.length());
            skeleton.write(pdf, cursor, rawLength);
            byte[] jpeg = Arrays.copyOfRange(pdf, image.start, image.end);
            byte[] payload = JpegDcExperiment.encodeExactV61(jpeg);
            if (!Arrays.equals(jpeg, JpegDcExperiment.decodeExactV61(payload)))
                throw new AssertionError("Embedded JPEG mismatch");
            jpegPayloads.add(payload);
            jpegBytes += jpeg.length;
            cursor = image.end;
        }
        VarInt.writeUnsigned(layout, pdf.length - cursor);
        skeleton.write(pdf, cursor, pdf.length - cursor);

        ResearchCordCodec cords = new ResearchCordCodec();
        byte[] layoutCord = cords.encode(layout.toByteArray());
        byte[] skeletonCord = cords.encode(skeleton.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeBytes(out, layoutCord);
        writeBytes(out, skeletonCord);
        for (byte[] jpegPayload : jpegPayloads) writeBytes(out, jpegPayload);
        return new Encoded(out.toByteArray(), images.size(), jpegBytes);
    }

    public static byte[] decode(byte[] payload) {
        require(payload.length >= MAGIC.length, "Short PDJ1 payload");
        for (int i = 0; i < MAGIC.length; i++) require(payload[i] == MAGIC[i], "Bad PDJ1 magic");
        VarInt.IntRef p = new VarInt.IntRef(MAGIC.length);
        ResearchCordCodec cords = new ResearchCordCodec();
        byte[] layout = cords.decode(readBytes(payload, p));
        byte[] skeleton = cords.decode(readBytes(payload, p));
        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        int[] rawLengths = new int[count + 1];
        int[] jpegLengths = new int[count];
        for (int i = 0; i < count; i++) {
            rawLengths[i] = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            jpegLengths[i] = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        }
        rawLengths[count] = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        require(lp.value == layout.length, "Trailing PDJ1 layout");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int rawPosition = 0;
        for (int i = 0; i < count; i++) {
            require(rawPosition + rawLengths[i] <= skeleton.length, "Truncated PDJ1 skeleton");
            out.write(skeleton, rawPosition, rawLengths[i]);
            rawPosition += rawLengths[i];
            byte[] jpeg = JpegDcExperiment.decodeExactV61(readBytes(payload, p));
            require(jpeg.length == jpegLengths[i], "PDJ1 JPEG length mismatch");
            out.writeBytes(jpeg);
        }
        require(rawPosition + rawLengths[count] == skeleton.length, "PDJ1 skeleton length mismatch");
        out.write(skeleton, rawPosition, rawLengths[count]);
        require(p.value == payload.length, "Trailing PDJ1 payload");
        return out.toByteArray();
    }

    private static List<Range> findSupportedJpegs(byte[] input) {
        List<Range> ranges = new ArrayList<>();
        int cursor = 0;
        while (cursor + 3 < input.length) {
            int start = findMarker(input, cursor, 0xD8);
            if (start < 0) break;
            int endMarker = findMarker(input, start + 2, 0xD9);
            if (endMarker < 0) break;
            int end = endMarker + 2;
            try {
                byte[] jpeg = Arrays.copyOfRange(input, start, end);
                byte[] encoded = JpegDcExperiment.encodeExactV61(jpeg);
                if (Arrays.equals(jpeg, JpegDcExperiment.decodeExactV61(encoded))) {
                    ranges.add(new Range(start, end));
                    cursor = end;
                    continue;
                }
            } catch (RuntimeException | AssertionError ignored) {
                // Not a supported baseline JPEG; preserve it in the skeleton.
            }
            cursor = start + 2;
        }
        return List.copyOf(ranges);
    }

    private static int findMarker(byte[] input, int start, int marker) {
        for (int i = Math.max(0, start); i + 1 < input.length; i++)
            if ((input[i] & 0xFF) == 0xFF && (input[i + 1] & 0xFF) == marker) return i;
        return -1;
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        VarInt.writeUnsigned(out, value.length);
        out.writeBytes(value);
    }

    private static byte[] readBytes(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(length >= 0 && p.value <= input.length - length, "Truncated PDJ1 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Encoded(byte[] payload, int jpegStreams, int jpegBytes) {
        public Encoded { payload = payload.clone(); }
    }
    private record Range(int start, int end) { int length() { return end - start; } }
}
