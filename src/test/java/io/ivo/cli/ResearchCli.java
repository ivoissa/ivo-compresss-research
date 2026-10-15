package io.ivo.cli;

import io.ivo.SelfTest;
import io.ivo.jpeg.JpegDcExperiment;
import io.ivo.mp3.Mp3V0Experiment;
import io.ivo.mp4.Mp4V0Experiment;
import io.ivo.pdf.PdfJpegV1Experiment;
import io.ivo.pdf.PdfV2Experiment;
import io.ivo.zip.ZipDocxV1Experiment;
import io.ivo.zip.ZipDocxV2Experiment;
import io.ivo.zip.ZipDocxV3Experiment;
import io.ivo.research.contrib.OwnerBayesianZipCodec;

import java.util.Arrays;

/** Entry point for the reproducible IVO research models. */
public final class ResearchCli {
    private ResearchCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        String[] forwarded = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "jpeg-v61" -> JpegDcExperiment.main(prepend("--v61-only", forwarded));
            case "mp3" -> Mp3V0Experiment.main(forwarded);
            case "mp3-ref" -> Mp3V0Experiment.main(prepend("--test-ref-semantic", forwarded));
            case "mp3-side" -> Mp3V0Experiment.main(prepend("--test-side-semantic", forwarded));
            case "mp4" -> Mp4V0Experiment.main(prepend("--samples", forwarded));
            case "pdf-image" -> PdfJpegV1Experiment.main(forwarded);
            case "pdf-v2" -> PdfV2Experiment.main(forwarded);
            case "zip-docx" -> ZipDocxV1Experiment.main(forwarded);
            case "zip-docx-v2" -> ZipDocxV2Experiment.main(forwarded);
            case "zip-docx-v3" -> ZipDocxV3Experiment.main(forwarded);
            case "zip-docx-bayes" -> OwnerBayesianZipCodec.main(prepend("bench", forwarded));
            case "selftest" -> SelfTest.main(forwarded);
            default -> usage();
        }
    }

    private static String[] prepend(String value, String[] input) {
        String[] result = new String[input.length + 1];
        result[0] = value;
        System.arraycopy(input, 0, result, 1, input.length);
        return result;
    }

    private static void usage() {
        throw new IllegalArgumentException("Usage: java -jar ivo-research.jar " +
                "<jpeg-v61|mp3|mp3-ref|mp3-side|mp4|pdf-image|pdf-v2|zip-docx|zip-docx-v2|zip-docx-v3|zip-docx-bayes|selftest> <file...>");
    }
}
