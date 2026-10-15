package io.ivo.pdf;

import io.ivo.deflate.DeflateIrCodec;
import io.ivo.deflate.DeflateWrapper;
import io.ivo.jpeg.JpegDcExperiment;
import io.ivo.research.ResearchCordCodec;
import io.ivo.util.VarInt;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** PDF V2: exact stream isolation plus transversal JPEG/JPX/DEFLATE cord aggregation. */
public final class PdfV2Experiment {
    private static final byte[] MAGIC = {'P', 'D', 'V', '2'};
    private static final int SKELETON = 0, JPEG = 1, JPX = 2, DEFLATE = 3, OTHER = 4;
    private static final int CORD_COUNT = 14;
    private static final int C_SKELETON = 0, C_JPEG_LAYOUT = 1, C_JPEG_META = 2,
            C_JPEG_COEFFICIENTS = 3, C_JPEG_PATCH = 4, C_JPX = 5,
            C_DEFLATE_PREFIX = 6, C_DEFLATE_SUFFIX = 7,
            C_DEFLATE_META = 8, C_DEFLATE_RUNS = 9, C_DEFLATE_LITERALS = 10,
            C_DEFLATE_LENGTHS = 11, C_DEFLATE_DISTANCES = 12, C_OTHER = 13;

    private PdfV2Experiment() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("PDF file required");
        boolean inspectOnly = args[0].equals("--inspect");
        boolean deflateIr = args[0].equals("--deflate-ir");
        for (int argument = inspectOnly || deflateIr ? 1 : 0; argument < args.length; argument++) {
            String arg = args[argument];
            Path path = Path.of(arg);
            byte[] input = Files.readAllBytes(path);
            if (inspectOnly) {
                List<PdfStream> parsed = parseStreams(input);
                long jpeg = parsed.stream().filter(x -> x.type == StreamType.JPEG).count();
                long jpx = parsed.stream().filter(x -> x.type == StreamType.JPX).count();
                long deflate = parsed.stream().filter(x -> x.type == StreamType.DEFLATE).count();
                long other = parsed.size() - jpeg - jpx - deflate;
                System.out.printf("PDF_V2_INSPECT %s streams=%d jpeg=%d jpx=%d deflate=%d other=%d%n",
                        path.getFileName(), parsed.size(), jpeg, jpx, deflate, other);
                continue;
            }
            long started = System.nanoTime();
            Encoded encoded = deflateIr ? encodeWithDeflateIr(input) : encode(input);
            long encodedAt = System.nanoTime();
            byte[] restored = decode(encoded.payload);
            long decodedAt = System.nanoTime();
            if (!Arrays.equals(input, restored)) throw new AssertionError("PDF V2 mismatch: " + path);
            int ivoEstimate = encoded.payload.length + 64;
            System.out.printf("PDF_V2 %s profile=%s original=%d streams=%d jpeg=%d jpx=%d deflate=%d other=%d payload=%d ivo_estimate=%d gain=%.4f%% enc_ms=%.3f dec_ms=%.3f exact=true%n",
                    path.getFileName(), deflateIr ? "DEFLATE_IR" : "GROUPED_RAW", input.length, encoded.streams, encoded.jpegStreams,
                    encoded.jpxStreams, encoded.deflateStreams, encoded.otherStreams,
                    encoded.payload.length, ivoEstimate, 100.0 * (input.length - ivoEstimate) / input.length,
                    (encodedAt - started) / 1_000_000.0, (decodedAt - encodedAt) / 1_000_000.0);
        }
    }

    public static Encoded encode(byte[] pdf) {
        return encodeProfile(pdf, false);
    }

    /** Experimental profile retained for research; GROUPED_RAW is the measured default. */
    public static Encoded encodeWithDeflateIr(byte[] pdf) {
        return encodeProfile(pdf, true);
    }

    private static Encoded encodeProfile(byte[] pdf, boolean transformDeflate) {
        List<PdfStream> streams = parseStreams(pdf);
        List<TransformRange> ranges = new ArrayList<>();
        DeflateIrCodec deflateCodec = new DeflateIrCodec();
        int jpegCount = 0, jpxCount = 0, deflateCount = 0, otherCount = 0;
        for (PdfStream stream : streams) {
            if (stream.type == StreamType.JPEG) {
                Range jpeg = findSupportedJpeg(pdf, stream.start, stream.end);
                if (jpeg != null) {
                    ranges.add(new TransformRange(JPEG, jpeg.start, jpeg.end));
                    jpegCount++;
                    continue;
                }
            }
            if (stream.type == StreamType.JPX) {
                ranges.add(new TransformRange(JPX, stream.start, stream.end));
                jpxCount++;
            } else if (stream.type == StreamType.DEFLATE) {
                if (!transformDeflate) {
                    ranges.add(new TransformRange(OTHER, stream.start, stream.end));
                    otherCount++;
                    continue;
                }
                byte[] raw = Arrays.copyOfRange(pdf, stream.start, stream.end);
                try {
                    WrappedDeflate wrapped = splitDeflate(deflateCodec, raw);
                    if (!Arrays.equals(raw, joinDeflate(deflateCodec, wrapped)))
                        throw new AssertionError("PDF DEFLATE re-emission mismatch");
                    ranges.add(new TransformRange(DEFLATE, stream.start, stream.end));
                    deflateCount++;
                } catch (RuntimeException ex) {
                    ranges.add(new TransformRange(OTHER, stream.start, stream.end));
                    otherCount++;
                }
            } else {
                ranges.add(new TransformRange(OTHER, stream.start, stream.end));
                otherCount++;
            }
        }
        ranges.sort(Comparator.comparingInt(TransformRange::start));
        validateNonOverlapping(ranges);

        ByteArrayOutputStream layout = new ByteArrayOutputStream();
        ByteArrayOutputStream[] rawCords = streams(CORD_COUNT);
        List<Segment> segments = new ArrayList<>();
        int cursor = 0;
        for (TransformRange range : ranges) {
            if (range.start > cursor)
                appendRaw(pdf, cursor, range.start - cursor, SKELETON, rawCords[C_SKELETON], segments);
            byte[] source = Arrays.copyOfRange(pdf, range.start, range.end);
            if (range.kind == JPEG) {
                JpegDcExperiment.V61Parts parts = JpegDcExperiment.splitExactV61(source);
                byte[][] families = {parts.layout(), parts.metadata(), parts.coefficients(), parts.patch()};
                int[] lengths = new int[4];
                for (int i = 0; i < families.length; i++) {
                    lengths[i] = families[i].length;
                    rawCords[C_JPEG_LAYOUT + i].writeBytes(families[i]);
                }
                segments.add(new Segment(JPEG, source.length, 0, lengths));
            } else if (range.kind == DEFLATE) {
                WrappedDeflate wrapped = splitDeflate(deflateCodec, source);
                byte[][] sections = wrapped.sections.asArray();
                int[] lengths = new int[7];
                lengths[0] = wrapped.parts.prefix().length;
                lengths[1] = wrapped.parts.suffix().length;
                rawCords[C_DEFLATE_PREFIX].writeBytes(wrapped.parts.prefix());
                rawCords[C_DEFLATE_SUFFIX].writeBytes(wrapped.parts.suffix());
                for (int i = 0; i < sections.length; i++) {
                    lengths[i + 2] = sections[i].length;
                    rawCords[C_DEFLATE_META + i].writeBytes(sections[i]);
                }
                segments.add(new Segment(DEFLATE, source.length, wrapped.parts.type().ordinal(), lengths));
            } else {
                int cord = range.kind == JPX ? C_JPX : C_OTHER;
                appendRaw(pdf, range.start, source.length, range.kind, rawCords[cord], segments);
            }
            cursor = range.end;
        }
        if (cursor < pdf.length)
            appendRaw(pdf, cursor, pdf.length - cursor, SKELETON, rawCords[C_SKELETON], segments);

        VarInt.writeUnsigned(layout, segments.size());
        for (Segment segment : segments) {
            layout.write(segment.kind);
            VarInt.writeUnsigned(layout, segment.originalLength);
            if (segment.kind == DEFLATE) VarInt.writeUnsigned(layout, segment.aux);
            for (int length : segment.familyLengths) VarInt.writeUnsigned(layout, length);
        }

        ResearchCordCodec cordCodec = new ResearchCordCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeBytes(out, cordCodec.encode(layout.toByteArray()));
        for (ByteArrayOutputStream rawCord : rawCords)
            writeBytes(out, cordCodec.encode(rawCord.toByteArray()));
        return new Encoded(out.toByteArray(), streams.size(), jpegCount, jpxCount, deflateCount, otherCount);
    }

    public static byte[] decode(byte[] payload) {
        require(payload.length >= 4, "Short PDV2 payload");
        for (int i = 0; i < 4; i++) require(payload[i] == MAGIC[i], "Bad PDV2 magic");
        VarInt.IntRef p = new VarInt.IntRef(4);
        ResearchCordCodec cordCodec = new ResearchCordCodec();
        byte[] layout = cordCodec.decode(readBytes(payload, p));
        byte[][] cords = new byte[CORD_COUNT][];
        for (int i = 0; i < cords.length; i++) cords[i] = cordCodec.decode(readBytes(payload, p));
        require(p.value == payload.length, "Trailing PDV2 payload");

        int[] positions = new int[CORD_COUNT];
        VarInt.IntRef lp = new VarInt.IntRef(0);
        int count = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        DeflateIrCodec deflateCodec = new DeflateIrCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int segmentIndex = 0; segmentIndex < count; segmentIndex++) {
            require(lp.value < layout.length, "Truncated PDV2 layout");
            int kind = layout[lp.value++] & 0xFF;
            int originalLength = Math.toIntExact(VarInt.readUnsigned(layout, lp));
            if (kind == JPEG) {
                byte[][] family = takeFamilies(layout, lp, cords, positions, C_JPEG_LAYOUT, 4);
                byte[] jpeg = JpegDcExperiment.joinExactV61(new JpegDcExperiment.V61Parts(
                        family[0], family[1], family[2], family[3]));
                require(jpeg.length == originalLength, "PDV2 JPEG length mismatch");
                out.writeBytes(jpeg);
            } else if (kind == DEFLATE) {
                int wrapperType = Math.toIntExact(VarInt.readUnsigned(layout, lp));
                require(wrapperType >= 0 && wrapperType < DeflateWrapper.Type.values().length,
                        "Bad PDV2 DEFLATE wrapper type");
                byte[][] family = new byte[7][];
                family[0] = takeFamily(layout, lp, cords, positions, C_DEFLATE_PREFIX);
                family[1] = takeFamily(layout, lp, cords, positions, C_DEFLATE_SUFFIX);
                byte[][] sections = takeFamilies(layout, lp, cords, positions, C_DEFLATE_META, 5);
                System.arraycopy(sections, 0, family, 2, 5);
                byte[] rawDeflate = deflateCodec.fromSections(new DeflateIrCodec.Sections(
                        family[2], family[3], family[4], family[5], family[6]));
                DeflateWrapper.Parts parts = new DeflateWrapper.Parts(
                        DeflateWrapper.Type.values()[wrapperType], family[0], rawDeflate, family[1]);
                byte[] raw = DeflateWrapper.join(parts, rawDeflate);
                require(raw.length == originalLength, "PDV2 DEFLATE length mismatch");
                out.writeBytes(raw);
            } else {
                int cord = switch (kind) {
                    case SKELETON -> C_SKELETON;
                    case JPX -> C_JPX;
                    case OTHER -> C_OTHER;
                    default -> throw new IllegalArgumentException("Unknown PDV2 segment kind " + kind);
                };
                positions[cord] = appendDecoded(out, cords[cord], positions[cord], originalLength);
            }
        }
        require(lp.value == layout.length, "Trailing PDV2 layout");
        for (int i = 0; i < cords.length; i++)
            require(positions[i] == cords[i].length, "Unused PDV2 cord " + i);
        return out.toByteArray();
    }

    private static byte[][] takeFamilies(byte[] layout, VarInt.IntRef lp, byte[][] cords,
                                         int[] positions, int firstCord, int count) {
        byte[][] result = new byte[count][];
        for (int i = 0; i < count; i++)
            result[i] = takeFamily(layout, lp, cords, positions, firstCord + i);
        return result;
    }

    private static byte[] takeFamily(byte[] layout, VarInt.IntRef lp, byte[][] cords,
                                     int[] positions, int cord) {
        int length = Math.toIntExact(VarInt.readUnsigned(layout, lp));
        require(positions[cord] <= cords[cord].length - length, "Truncated PDV2 family cord");
        byte[] result = Arrays.copyOfRange(cords[cord], positions[cord], positions[cord] + length);
        positions[cord] += length;
        return result;
    }

    private static List<PdfStream> parseStreams(byte[] pdf) {
        List<PdfStream> result = new ArrayList<>();
        int search = 0;
        while (true) {
            int keyword = findAscii(pdf, search, "stream");
            if (keyword < 0) break;
            if (!isKeywordBoundary(pdf, keyword - 1) || !isKeywordBoundary(pdf, keyword + 6)) {
                search = keyword + 6;
                continue;
            }
            int dataStart = keyword + 6;
            if (dataStart < pdf.length && pdf[dataStart] == '\r') dataStart++;
            if (dataStart < pdf.length && pdf[dataStart] == '\n') dataStart++;
            else {
                search = keyword + 6;
                continue;
            }
            int windowStart = Math.max(0, keyword - 8192);
            int objectKeyword = lastKeyword(pdf, windowStart, keyword, "obj");
            int dictionaryStart = objectKeyword >= 0 ? objectKeyword + 3 : windowStart;
            int length = directLength(pdf, dictionaryStart, keyword);
            int dataEnd = -1, endstream = -1;
            if (length >= 0 && dataStart <= pdf.length - length) {
                int expected = dataStart + length;
                int probe = expected;
                if (probe < pdf.length && pdf[probe] == '\r') probe++;
                if (probe < pdf.length && pdf[probe] == '\n') probe++;
                if (matchesAscii(pdf, probe, "endstream")) {
                    dataEnd = expected;
                    endstream = probe;
                }
            }
            if (dataEnd < 0) {
                endstream = findAscii(pdf, dataStart, "endstream");
                if (endstream < 0) break;
                dataEnd = endstream;
                if (dataEnd > dataStart && pdf[dataEnd - 1] == '\n') dataEnd--;
                if (dataEnd > dataStart && pdf[dataEnd - 1] == '\r') dataEnd--;
            }
            StreamType type = classify(pdf, dictionaryStart, keyword);
            result.add(new PdfStream(dataStart, dataEnd, type));
            search = endstream + 9;
        }
        return List.copyOf(result);
    }

    private static StreamType classify(byte[] pdf, int start, int end) {
        if (containsAscii(pdf, start, end, "/DCTDecode")) return StreamType.JPEG;
        if (containsAscii(pdf, start, end, "/JPXDecode")) return StreamType.JPX;
        if (containsAscii(pdf, start, end, "/FlateDecode")) return StreamType.DEFLATE;
        return StreamType.OTHER;
    }

    private static int directLength(byte[] pdf, int start, int end) {
        int token = lastIndexOfAscii(pdf, start, end, "/Length");
        if (token < 0) return -1;
        int p = token + 7;
        while (p < end && isSpace(pdf[p])) p++;
        int digits = p;
        long value = 0;
        while (p < end && pdf[p] >= '0' && pdf[p] <= '9') {
            value = value * 10 + (pdf[p++] - '0');
            if (value > Integer.MAX_VALUE) return -1;
        }
        if (p == digits) return -1;
        int after = p;
        while (after < end && isSpace(pdf[after])) after++;
        if (after < end && pdf[after] >= '0' && pdf[after] <= '9') return -1; // indirect object reference
        return (int) value;
    }

    private static Range findSupportedJpeg(byte[] pdf, int start, int end) {
        int soi = -1;
        for (int p = start; p + 1 < end; p++)
            if ((pdf[p] & 0xFF) == 0xFF && (pdf[p + 1] & 0xFF) == 0xD8) { soi = p; break; }
        if (soi < 0) return null;
        for (int p = soi + 2; p + 1 < end; p++) {
            if ((pdf[p] & 0xFF) != 0xFF || (pdf[p + 1] & 0xFF) != 0xD9) continue;
            byte[] jpeg = Arrays.copyOfRange(pdf, soi, p + 2);
            try {
                JpegDcExperiment.V61Parts parts = JpegDcExperiment.splitExactV61(jpeg);
                if (Arrays.equals(jpeg, JpegDcExperiment.joinExactV61(parts))) return new Range(soi, p + 2);
            } catch (RuntimeException | AssertionError ignored) {
                // Continue to a later EOI marker if this was not the full JPEG.
            }
        }
        return null;
    }

    private static void validateNonOverlapping(List<TransformRange> ranges) {
        for (int i = 1; i < ranges.size(); i++)
            require(ranges.get(i - 1).end <= ranges.get(i).start, "Overlapping PDF stream ranges");
    }

    private static int appendDecoded(ByteArrayOutputStream out, byte[] source, int position, int length) {
        require(position <= source.length - length, "Truncated PDV2 raw cord");
        out.write(source, position, length);
        return position + length;
    }

    private static void appendRaw(byte[] input, int start, int length, int kind,
                                  ByteArrayOutputStream target, List<Segment> segments) {
        target.write(input, start, length);
        segments.add(new Segment(kind, length, 0, new int[0]));
    }

    private static WrappedDeflate splitDeflate(DeflateIrCodec codec, byte[] wrapped) {
        DeflateWrapper.Parts parts = DeflateWrapper.split(wrapped, "stream.zlib");
        DeflateIrCodec.Sections sections = codec.toSections(parts.rawDeflate());
        require(Arrays.equals(parts.rawDeflate(), codec.fromSections(sections)),
                "PDF raw DEFLATE re-emission mismatch");
        return new WrappedDeflate(parts, sections);
    }

    private static byte[] joinDeflate(DeflateIrCodec codec, WrappedDeflate wrapped) {
        byte[] rawDeflate = codec.fromSections(wrapped.sections);
        return DeflateWrapper.join(wrapped.parts, rawDeflate);
    }

    private static ByteArrayOutputStream[] streams(int count) {
        ByteArrayOutputStream[] result = new ByteArrayOutputStream[count];
        for (int i = 0; i < count; i++) result[i] = new ByteArrayOutputStream();
        return result;
    }

    private static int findAscii(byte[] data, int start, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer: for (int p = Math.max(0, start); p <= data.length - needle.length; p++) {
            for (int i = 0; i < needle.length; i++) if (data[p + i] != needle[i]) continue outer;
            return p;
        }
        return -1;
    }

    private static int lastIndexOfAscii(byte[] data, int start, int end, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer: for (int p = end - needle.length; p >= start; p--) {
            for (int i = 0; i < needle.length; i++) if (data[p + i] != needle[i]) continue outer;
            return p;
        }
        return -1;
    }

    private static int lastKeyword(byte[] data, int start, int end, String value) {
        int searchEnd = end;
        while (searchEnd > start) {
            int found = lastIndexOfAscii(data, start, searchEnd, value);
            if (found < 0) return -1;
            if (isKeywordBoundary(data, found - 1) && isKeywordBoundary(data, found + value.length())) return found;
            searchEnd = found;
        }
        return -1;
    }

    private static boolean containsAscii(byte[] data, int start, int end, String value) {
        int found = findAscii(data, start, value);
        return found >= 0 && found < end;
    }

    private static boolean matchesAscii(byte[] data, int start, String value) {
        if (start < 0 || start > data.length - value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (data[start + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static boolean isKeywordBoundary(byte[] data, int position) {
        if (position < 0 || position >= data.length) return true;
        int c = data[position] & 0xFF;
        return isSpace(data[position]) || c == '<' || c == '>' || c == '/' || c == '[' || c == ']' || c == '(' || c == ')';
    }

    private static boolean isSpace(byte value) {
        int c = value & 0xFF;
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        VarInt.writeUnsigned(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] input, VarInt.IntRef p) {
        int length = Math.toIntExact(VarInt.readUnsigned(input, p));
        require(length >= 0 && p.value <= input.length - length, "Truncated PDV2 field");
        byte[] result = Arrays.copyOfRange(input, p.value, p.value + length);
        p.value += length;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Encoded(byte[] payload, int streams, int jpegStreams, int jpxStreams,
                          int deflateStreams, int otherStreams) {
        public Encoded { payload = payload.clone(); }
    }
    private enum StreamType { JPEG, JPX, DEFLATE, OTHER }
    private record PdfStream(int start, int end, StreamType type) {}
    private record TransformRange(int kind, int start, int end) {}
    private record Segment(int kind, int originalLength, int aux, int[] familyLengths) {}
    private record WrappedDeflate(DeflateWrapper.Parts parts, DeflateIrCodec.Sections sections) {}
    private record Range(int start, int end) {}
}
