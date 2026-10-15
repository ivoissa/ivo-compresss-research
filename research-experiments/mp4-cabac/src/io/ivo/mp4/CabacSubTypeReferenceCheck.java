package io.ivo.mp4;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/** Compares selected Java subtypes to direct logs from the instrumented FFmpeg decoder. */
public final class CabacSubTypeReferenceCheck {
    record Key(int frame,int x,int y,int sub) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("subtypes.csv instrumented.log width_in_mbs");
        int width = Integer.parseInt(args[2]);
        Map<Key,Integer> required = new HashMap<>();
        List<String> rows = Files.readAllLines(Path.of(args[0]));
        if (rows.isEmpty() || !rows.get(0).startsWith("slice,macroblock,sub_index,sub_mb_type,"))
            throw new IllegalArgumentException("CSV schema");
        for (int i = 1; i < rows.size(); i++) {
            String[] cells = rows.get(i).split(","); int frame = Integer.parseInt(cells[0]);
            int mb = Integer.parseInt(cells[1]), sub = Integer.parseInt(cells[2]), value = Integer.parseInt(cells[3]);
            if (frame < 0 || mb < 0 || sub < 0 || sub > 3 || value < 0 || value > 3 || width <= 0)
                throw new IllegalArgumentException("CSV values");
            if (required.put(new Key(frame,mb%width,mb/width,sub),value) != null)
                throw new IllegalArgumentException("Duplicate Java key");
        }
        boolean active = false; int frame = -1, compared = 0;
        Set<Key> seen = new HashSet<>(); Map<Integer,Integer> counts = new TreeMap<>();
        Pattern pattern = Pattern.compile("IVO_SUB (\\d+) (\\d+) (\\d+) (\\d+)");
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (line.contains("Stream mapping:")) { active = true; continue; }
            if (!active) continue;
            if (line.contains("IVO_FRAME")) frame++;
            var m = pattern.matcher(line);
            if (!m.find()) continue;
            Key key = new Key(frame,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
            Integer value = required.get(key);
            if (value == null) continue;
            if (!seen.add(key)) throw new IllegalArgumentException("Duplicate reference key");
            int expected = Integer.parseInt(m.group(4));
            if (expected != value) throw new AssertionError("sub_mb_type differs at " + key + ": Java=" + value + " reference=" + expected);
            counts.merge(value,1,Integer::sum); compared++;
        }
        if (!active || compared != required.size() || compared == 0) throw new AssertionError("Missing reference comparisons");
        System.out.println("PASS direct_sub_mb_type_comparisons="+compared+" counts="+counts+" mismatches=0 reference_frames="+(frame+1));
    }
}
