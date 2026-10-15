package io.ivo;

import io.ivo.deflate.DeflateIrCodec;
import io.ivo.deflate.DeflateIrEngine;
import io.ivo.deflate.DeflateWrapper;
import io.ivo.engine.CompressionCandidate;
import io.ivo.engine.EngineId;
import io.ivo.engine.Router;
import io.ivo.nc.NcCodec;
import io.ivo.ref.RefTransform;
import io.ivo.jpeg.JpegIrCodec;
import io.ivo.jpeg.JpegIrEngine;
import io.ivo.jpeg.JpegV61Engine;
import io.ivo.pdf.PdfV2Engine;
import io.ivo.mp3.Mp3V1Engine;
import io.ivo.mp4.Mp4V1Engine;
import io.ivo.zip.ZipDocxV1Engine;
import io.ivo.zip.ZipDocxV3Engine;
import io.ivo.zip.ZipBayesianEngine;
import io.ivo.research.contrib.OwnerBayesianZipCodec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public final class SelfTest {
    public static void main(String[] args) throws Exception {
        testNc();
        testRef();
        testDeflateIrExact();
        testDeflateWrappers();
        testJpegIrExact();
        testMp3Runtime();
        testMp4Runtime();
        testPdfV2Runtime();
        testZipDocxRuntime();
        testStableEngineIds();
        testRouterText();
        testRouterJson();
        testRandom();
        System.out.println("ALL SELF TESTS OK");
    }

    private static void testNc() {
        byte[] data = "Bonjour bonjour. Nuage & Corde. Bonjour bonjour!".repeat(100).getBytes(StandardCharsets.UTF_8);
        NcCodec codec = new NcCodec();
        byte[] comp = codec.encode(data);
        assertEq(data, codec.decode(comp), "N&C");
    }

    private static void testRef() {
        byte[] data = "ABCDEFGH-ABCDEFGH-ABCDEFGH-ABCDEFGH-XYZ".repeat(200).getBytes(StandardCharsets.UTF_8);
        RefTransform ref = new RefTransform();
        byte[] transformed = ref.encode(data);
        assertEq(data, ref.decode(transformed), "REF");
    }

    private static void testDeflateIrExact() throws Exception {
        DeflateIrCodec codec = new DeflateIrCodec();
        byte[][] corpus = {
                "hello".getBytes(StandardCharsets.UTF_8),
                "hello hello hello hello".repeat(40).getBytes(StandardCharsets.UTF_8),
                ("public class Demo { int x; }\n").repeat(2_000).getBytes(StandardCharsets.UTF_8)
        };
        boolean sawStored = false, sawFixed = false, sawDynamic = false;
        for (byte[] data : corpus) {
            for (int level : new int[]{0, 1, 6, 9}) {
                for (int strategy : new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY}) {
                    byte[] raw = deflate(data, level, true, strategy);
                    byte[] ir = codec.toIrBytes(raw);
                    assertEq(raw, codec.fromIrBytes(ir), "DEFLATE IR raw exact");
                    DeflateIrCodec.Stats s = codec.inspect(raw);
                    sawStored |= s.storedBlocks() > 0;
                    sawFixed |= s.fixedBlocks() > 0;
                    sawDynamic |= s.dynamicBlocks() > 0;
                }
            }
        }
        if (!sawStored || !sawFixed || !sawDynamic)
            throw new AssertionError("DEFLATE test corpus did not cover stored/fixed/dynamic blocks");

        // RFC1951 ignores unused bits after the final block. IVO must nevertheless preserve them exactly.
        byte[] fixed = deflate("hello".getBytes(StandardCharsets.UTF_8), 9, true, Deflater.DEFAULT_STRATEGY);
        DeflateIrCodec.Stats fs = codec.inspect(fixed);
        if (fs.tailPadBits() > 0) {
            byte[] altered = fixed.clone();
            int used = 8 - fs.tailPadBits();
            int mask = ((1 << fs.tailPadBits()) - 1) << used;
            altered[altered.length - 1] ^= (byte) mask;
            byte[] ir = codec.toIrBytes(altered);
            assertEq(altered, codec.fromIrBytes(ir), "DEFLATE final padding exact");
        }
    }

    private static void testDeflateWrappers() throws Exception {
        byte[] data = ("wrapper test 0123456789 abcdefghijklmnopqrstuvwxyz\n").repeat(300).getBytes(StandardCharsets.UTF_8);
        DeflateIrEngine engine = new DeflateIrEngine();

        byte[] zlib = deflate(data, 6, false, Deflater.DEFAULT_STRATEGY);
        CompressionCandidate z = engine.compress(zlib, "sample.zlib");
        assertEq(zlib, engine.decompress(z.payload()), "zlib wrapper exact");
        DeflateWrapper.split(zlib, "sample.zlib");

        ByteArrayOutputStream gout = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gout)) { gz.write(data); }
        byte[] gzip = gout.toByteArray();
        CompressionCandidate g = engine.compress(gzip, "sample.gz");
        assertEq(gzip, engine.decompress(g.payload()), "gzip wrapper exact");
    }

    private static void testJpegIrExact() throws Exception {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, ((x * 4) << 16) | ((y * 5) << 8) | ((x * 3 + y * 2) & 255));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) throw new AssertionError("No JPEG ImageIO writer");
        byte[] jpeg = out.toByteArray();
        JpegIrCodec codec = new JpegIrCodec();
        assertEq(jpeg, codec.fromIrBytes(codec.toIrBytes(jpeg)), "JPEG IR exact");
        JpegIrEngine engine = new JpegIrEngine();
        CompressionCandidate candidate = engine.compress(jpeg, "generated.jpg");
        assertEq(jpeg, engine.decompress(candidate.payload()), "JPEG engine exact");
        JpegV61Engine v61 = new JpegV61Engine();
        CompressionCandidate v61Candidate = v61.compress(jpeg, "generated.jpg");
        assertEq(jpeg, v61.decompress(v61Candidate.payload()), "JPEG V6.1 engine exact");
    }

    private static void testPdfV2Runtime() {
        byte[] pdf = ("%PDF-1.4\n1 0 obj\n<< /Length 5 >>\nstream\nhello\nendstream\nendobj\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        PdfV2Engine engine = new PdfV2Engine();
        if (!engine.supports(pdf, "sample.pdf")) throw new AssertionError("PDF V2 probe");
        CompressionCandidate candidate = engine.compress(pdf, "sample.pdf");
        assertEq(pdf, engine.decompress(candidate.payload()), "PDF V2 runtime");
    }

    private static void testMp3Runtime() {
        byte[] mp3 = new byte[417 * 2];
        for (int frame = 0; frame < 2; frame++) {
            int p = frame * 417;
            mp3[p] = (byte) 0xFF; mp3[p + 1] = (byte) 0xFB;
            mp3[p + 2] = (byte) 0x90; mp3[p + 3] = 0;
            for (int i = 4; i < 417; i++) mp3[p + i] = (byte) (i * 31 + frame);
        }
        Mp3V1Engine engine = new Mp3V1Engine();
        CompressionCandidate candidate = engine.compress(mp3, "sample.mp3");
        assertEq(mp3, engine.decompress(candidate.payload()), "MP3 V1 runtime");
    }

    private static void testMp4Runtime() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMp4Box(out, "ftyp", "isom\u0000\u0000\u0002\u0000isom".getBytes(StandardCharsets.ISO_8859_1));
        byte[] media = new byte[4096];
        new Random(7).nextBytes(media);
        writeMp4Box(out, "mdat", media);
        byte[] mp4 = out.toByteArray();
        Mp4V1Engine engine = new Mp4V1Engine();
        CompressionCandidate candidate = engine.compress(mp4, "sample.mp4");
        assertEq(mp4, engine.decompress(candidate.payload()), "MP4 V1 runtime");
    }

    private static void writeMp4Box(ByteArrayOutputStream out, String type, byte[] payload) {
        int size = payload.length + 8;
        out.write((size >>> 24) & 0xFF); out.write((size >>> 16) & 0xFF);
        out.write((size >>> 8) & 0xFF); out.write(size & 0xFF);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
    }

    private static void testZipDocxRuntime() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putStoredEntry(zip, "[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
            putStoredEntry(zip, "word/document.xml",
                    "<document><p>IVO exact</p></document>".repeat(20).getBytes(StandardCharsets.UTF_8));
        }
        byte[] docx = bytes.toByteArray();
        ZipDocxV1Engine engine = new ZipDocxV1Engine();
        if (!engine.supports(docx, "sample.docx")) throw new AssertionError("DOCX V1 probe");
        CompressionCandidate candidate = engine.compress(docx, "sample.docx");
        assertEq(docx, engine.decompress(candidate.payload()), "DOCX V1 runtime");
        ZipDocxV3Engine v3 = new ZipDocxV3Engine();
        CompressionCandidate v3Candidate = v3.compress(docx, "sample.docx");
        assertEq(docx, v3.decompress(v3Candidate.payload()), "DOCX V3 runtime");
        byte[] contributed = OwnerBayesianZipCodec.compress(docx, new StringBuilder());
        assertEq(docx, OwnerBayesianZipCodec.decompress(contributed), "DOCX Bayesian contribution");
        ZipBayesianEngine bayes = new ZipBayesianEngine();
        CompressionCandidate bayesCandidate = bayes.compress(docx, "sample.docx");
        assertEq(docx, bayes.decompress(bayesCandidate.payload()), "ZIP Bayesian runtime");
        roundTrip(docx, "sample.docx");
    }

    private static void putStoredEntry(ZipOutputStream zip, String name, byte[] data) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void testStableEngineIds() {
        if (EngineId.fromCode(6) != EngineId.PDF_V2 || EngineId.fromCode(7) != EngineId.ZIP_DOCX_V1
                || EngineId.fromCode(8) != EngineId.ZIP_DOCX_V3
                || EngineId.fromCode(9) != EngineId.JPEG_V61
                || EngineId.fromCode(10) != EngineId.MP3_V1
                || EngineId.fromCode(11) != EngineId.MP4_V1
                || EngineId.fromCode(12) != EngineId.MP4_V2
                || EngineId.fromCode(13) != EngineId.MP4_V3
                || EngineId.fromCode(14) != EngineId.ZIP_BAYES_V1)
            throw new AssertionError("Specialized engine IDs changed");
    }

    private static byte[] deflate(byte[] data, int level, boolean nowrap, int strategy) {
        Deflater d = new Deflater(level, nowrap);
        d.setStrategy(strategy);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            int n = d.deflate(buf);
            if (n == 0 && d.needsInput()) break;
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    private static void testRouterText() {
        byte[] data = ("public record Point(int x, int y) {}\n" +
                "public final class Demo { static int sum(int a,int b){return a+b;} }\n").repeat(200)
                .getBytes(StandardCharsets.UTF_8);
        roundTrip(data, "Demo.java");
    }

    private static void testRouterJson() {
        byte[] data = ("{\n  \"id\": 123, \"name\": \"Alice\\nTest\", \"ok\": true, \"v\": -12.5e+2\n}\n").repeat(100)
                .getBytes(StandardCharsets.UTF_8);
        roundTrip(data, "data.json");
    }

    private static void testRandom() {
        byte[] data = new byte[32_768];
        new Random(42).nextBytes(data);
        roundTrip(data, "random.bin");
    }

    private static void roundTrip(byte[] data, String name) {
        Router router = new Router();
        Router.Routed r = router.compress(data, name);
        assertEq(data, router.decompress(r.bytes()), "router " + name);
    }

    private static void assertEq(byte[] a, byte[] b, String what) {
        if (!Arrays.equals(a, b)) throw new AssertionError(what + " mismatch");
    }
}
