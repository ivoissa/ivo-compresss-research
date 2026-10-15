package io.ivo.bench;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Summarises completed PerformanceMeasure runs using only the Java standard library. */
public final class PerformanceReport {
    private record Stats(int original, int packed, String engine, double enc, double dec,
                         double encMin, double encMax, double decMin, double decMax,
                         long encHeapMax, long decHeapMax) {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("measurement-directory");
        Path directory=Path.of(args[0]);
        List<Path> completed;
        try(Stream<Path> paths=Files.list(directory)) {
            completed=paths.filter(Files::isDirectory).filter(p->Files.isRegularFile(p.resolve("environment.txt"))).sorted().toList();
        }
        StringBuilder csv=new StringBuilder("source,mode,original_bytes,ivo_bytes,engine,compression_median_ms,compression_min_ms,compression_max_ms,decompression_median_ms,decompression_min_ms,decompression_max_ms,compression_mib_per_second,decompression_mib_per_second,compression_heap_pool_peak_sum_max_bytes,decompression_heap_pool_peak_sum_max_bytes,speedup_vs_enhanced,size_delta_vs_enhanced_bytes,same_archive\n");
        StringBuilder table=new StringBuilder("| Source | IVO exhaustif / rapide (octets) | Compression exhaustive / rapide (s) | Accélération | Décompression exhaustive / rapide (s) |\n|---|---:|---:|---:|---:|\n");
        for(Path run:completed) {
            List<String[]> rows=Files.readAllLines(run.resolve("samples.csv")).stream().skip(1).filter(s->!s.isBlank()).map(s->s.split(",",-1)).toList();
            Stats enhanced=stats(rows,"enhanced"),fast=stats(rows,"fast");
            if(enhanced.original!=fast.original) throw new IllegalArgumentException("Input mismatch");
            boolean same=Arrays.equals(Files.readAllBytes(run.resolve("enhanced.ivo")),Files.readAllBytes(run.resolve("fast.ivo")));
            double speedup=enhanced.enc/fast.enc;
            for(String mode:List.of("enhanced","fast")) {
                Stats s=mode.equals("enhanced")?enhanced:fast;
                csv.append('"').append(run.getFileName().toString().replace("\"","\"\"")).append("\",").append(mode).append(',');
                csv.append(String.format(Locale.ROOT,"%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%.6f,%d,%s%n",s.original,s.packed,s.engine,s.enc,s.encMin,s.encMax,s.dec,s.decMin,s.decMax,s.original*1000.0/(1048576*s.enc),s.original*1000.0/(1048576*s.dec),s.encHeapMax,s.decHeapMax,mode.equals("fast")?speedup:1.0,s.packed-enhanced.packed,same));
            }
            table.append(String.format(Locale.ROOT,"| %s | %d / %d | %.3f / %.3f | ×%.2f | %.3f / %.3f |%n",run.getFileName().toString().replace("|","\\|").replace("\n"," "),enhanced.packed,fast.packed,enhanced.enc/1000,fast.enc/1000,speedup,enhanced.dec/1000,fast.dec/1000));
        }
        Files.writeString(directory.resolve("summary.csv"),csv.toString());
        Files.writeString(directory.resolve("summary-table.md"),table.toString());
        System.out.println("Summarised "+completed.size()+" completed files. Compression includes candidate verification; heap pool peak sums are upper bounds.");
    }
    private static Stats stats(List<String[]> rows,String mode) {
        List<String[]> selected=rows.stream().filter(r->r[0].equals(mode)).toList();
        if(selected.size()<3) throw new IllegalArgumentException("At least three measured repetitions required");
        String[] first=selected.get(0);int original=Integer.parseInt(first[2]),packed=Integer.parseInt(first[3]);
        for(String[] row:selected) if(row.length!=12 || !row[2].equals(first[2]) || !row[3].equals(first[3]) || !row[4].equals(first[4]) || !row[11].equals("true")) throw new IllegalArgumentException("Invalid or inconsistent samples");
        double[] enc=selected.stream().mapToDouble(r->Double.parseDouble(r[5])).sorted().toArray(),dec=selected.stream().mapToDouble(r->Double.parseDouble(r[6])).sorted().toArray();
        return new Stats(original,packed,first[4],median(enc),median(dec),enc[0],enc[enc.length-1],dec[0],dec[dec.length-1],selected.stream().mapToLong(r->Long.parseLong(r[7])).max().orElseThrow(),selected.stream().mapToLong(r->Long.parseLong(r[8])).max().orElseThrow());
    }
    private static double median(double[] values) { int n=values.length;return n%2==1?values[n/2]:(values[n/2-1]+values[n/2])/2; }
}
