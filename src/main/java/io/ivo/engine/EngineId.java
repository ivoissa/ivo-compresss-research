package io.ivo.engine;

public enum EngineId {
    STORE(0),
    NC_CONTEXT(1),
    REF_NC(2),
    JSON_TOKEN_NC(3),
    DEFLATE_IR(4),
    JPEG_IR(5),
    PDF_V2(6),
    ZIP_DOCX_V1(7),
    ZIP_DOCX_V3(8),
    JPEG_V61(9),
    MP3_V1(10),
    MP4_V1(11),
    MP4_V2(12),
    MP4_V3(13),
    ZIP_BAYES_V1(14),
    MP3_SPECTRAL_RESEARCH(17),
    MP3_SPECTRAL_CORDS_RESEARCH(18),
    MP3_CONTEXT_RESEARCH(19),
    MP4_NAL_RESEARCH(20),
    MP4_NAL_TYPES_RESEARCH(21),
    MP4_SLICE_HEADERS_RESEARCH(22),
    MP3_TEMPORAL_RESEARCH(23),
    JPEG_NEIGHBOUR_RESEARCH(24),
    JPEG_SCAN_RESEARCH(25),
    MP3_ENERGY_RESEARCH(26),
    MP4_VIDEO_BODY_RESEARCH(27),
    USER_MP3_RESEARCH(28),
    USER_MP4_CABAC_RESEARCH(29),
    USER_WEBP_RESEARCH(30),
    MP4_AUDIO_RESEARCH(31);

    private final int code;
    EngineId(int code) { this.code = code; }
    public int code() { return code; }
    public static EngineId fromCode(int code) {
        for (EngineId id : values()) if (id.code == code) return id;
        throw new IllegalArgumentException("Unknown engine code: " + code);
    }
}
