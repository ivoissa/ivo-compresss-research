package io.ivo.mp4;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Narrow real-syntax probe, externally traced headers; not a complete H.264 parser. */
public final class CabacFirstSkip {
    record Field(int position, String name, String bits, int value) {}
    record Header(String kind, List<Field> fields) {
        int value(String name) {
            return fields.stream().filter(f -> f.name.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing " + name)).value;
        }
        int end() { return fields.stream().mapToInt(f -> f.position + f.bits.length()).max().orElseThrow(); }
        void verify(byte[] rbsp) {
            for (Field f : fields) for (int i = 0; i < f.bits.length(); i++) {
                int p = f.position + i;
                if (p < 0 || p >= rbsp.length * 8L || ((rbsp[p / 8] >>> (7 - p % 8)) & 1) != f.bits.charAt(i) - '0')
                    throw new IllegalArgumentException("Trace/source mismatch: " + f.name);
            }
        }
    }
    record Frame(char type, char first, String markers, String partitions) {}
    static List<Header> headers(Path trace) throws Exception {
        Pattern pattern = Pattern.compile("\\]\\s+(\\d+)\\s+(\\S+)\\s+([01]+) = (-?\\d+)");
        var result = new ArrayList<Header>(); String kind = null; var fields = new ArrayList<Field>();
        for (String line : Files.readAllLines(trace)) {
            String next = line.contains("Sequence Parameter Set") ? "SPS" :
                    line.contains("Picture Parameter Set") ? "PPS" : line.contains("Slice Header") ? "SLICE" : null;
            if (next != null || line.contains("Packet:")) {
                if (kind != null) result.add(new Header(kind, List.copyOf(fields)));
                kind = next; fields.clear();
            }
            if (kind != null) {
                var m = pattern.matcher(line);
                if (m.find()) fields.add(new Field(Integer.parseInt(m.group(1)), m.group(2), m.group(3), Integer.parseInt(m.group(4))));
            }
        }
        if (kind != null) result.add(new Header(kind, List.copyOf(fields)));
        return result;
    }
    static List<Frame> frames(Path debug, int width, int height) throws Exception {
        var result = new ArrayList<Frame>(); boolean active = false; char type = 0, first = 0; int rows = 0; var markers = new StringBuilder(); var partitions = new StringBuilder();
        Pattern newFrame = Pattern.compile("New frame, type: ([IPB])");
        Pattern grid = Pattern.compile("\\[h264 @ [^]]+\\] ([iIPASdDgG><X][ +|?\\-= ][ = ](?:[iIPASdDgG><X][ +|?\\-= ][ = ])*)");
        // find_stream_info may decode a probe frame. Only the actual decoding run is evidence.
        for (String line : Files.readAllLines(debug)) {
            if (line.contains("Stream mapping:")) { active = true; continue; }
            if (!active) continue;
            var m = newFrame.matcher(line);
            if (m.find()) {
                if (type != 0) { if (rows != height) throw new IllegalArgumentException("Incomplete debug grid"); result.add(new Frame(type, first, markers.toString(), partitions.toString())); }
                type = m.group(1).charAt(0); rows = 0; first = 0; markers.setLength(0); partitions.setLength(0);
            } else if (type != 0) {
                var g = grid.matcher(line);
                if (g.matches()) {
                    String row = g.group(1);
                    if (row.length() != width * 3) throw new IllegalArgumentException("Debug grid width");
                    if (rows++ == 0) first = row.charAt(0);
                    for (int x = 0; x < width; x++) { markers.append(row.charAt(x * 3)); partitions.append(row.charAt(x * 3 + 1)); }
                }
            }
        }
        if (type != 0) { if (rows != height) throw new IllegalArgumentException("Incomplete final debug grid"); result.add(new Frame(type, first, markers.toString(), partitions.toString())); }
        if (result.isEmpty()) throw new IllegalArgumentException("No reference frames");
        return result;
    }
    static void require(int actual, int expected, String name) {
        if (actual != expected) throw new IllegalArgumentException("Unsupported " + name + "=" + actual);
    }
    public static void main(String[] args) throws Exception {
        boolean residuals = args.length == 5 && args[4].equals("--residuals");
        boolean motion = residuals || args.length == 5 && args[4].equals("--motion");
        boolean subTypes = motion || args.length == 5 && args[4].equals("--sub-types");
        boolean mbTypes = subTypes || args.length == 5 && args[4].equals("--mb-types");
        boolean prefix = mbTypes || args.length == 5 && args[4].equals("--prefix");
        if (args.length != 4 && !prefix) throw new IllegalArgumentException("AnnexB trace_headers.log mb_type.log output.csv [--prefix|--mb-types|--sub-types|--motion|--residuals]");
        var all = headers(Path.of(args[1]));
        var sps = all.stream().filter(h -> h.kind.equals("SPS")).findFirst().orElseThrow();
        var pps = all.stream().filter(h -> h.kind.equals("PPS")).findFirst().orElseThrow();
        // Deliberately restrict parameter changes; no implicit fallback to the wrong set.
        for (Header h : all) if ((h.kind.equals("SPS") && !h.equals(sps)) || (h.kind.equals("PPS") && !h.equals(pps)))
            throw new IllegalArgumentException("Parameter-set changes unsupported");
        require(sps.value("seq_parameter_set_id"), 0, "SPS id");
        require(sps.value("frame_mbs_only_flag"), 1, "progressive frame");
        require(sps.value("bit_depth_luma_minus8"), 0, "luma bit depth");
        require(pps.value("pic_parameter_set_id"), 0, "PPS id");
        require(pps.value("seq_parameter_set_id"), 0, "PPS/SPS association");
        require(pps.value("entropy_coding_mode_flag"), 1, "CABAC");
        require(pps.value("num_slice_groups_minus1"), 0, "slice groups");
        if(residuals) {
            require(sps.value("chroma_format_idc"),1,"residue 4:2:0");
            require(sps.value("bit_depth_chroma_minus8"),0,"chroma bit depth");
            require(sps.value("qpprime_y_zero_transform_bypass_flag"),0,"transform bypass");
        }
        int width = sps.value("pic_width_in_mbs_minus1") + 1;
        int height = sps.value("pic_height_in_map_units_minus1") + 1;
        var reference = frames(Path.of(args[2]), width, height);
        var slices = all.stream().filter(h -> h.kind.equals("SLICE")).toList();
        byte[] source = Files.readAllBytes(Path.of(args[0])); int slice = 0, tested = 0, skipped = 0, terminations = 0, terminalOnes = 0, longestRun = 0, typesTested = 0;
        boolean sawSps = false, sawPps = false;
        int residueMbs=0,replayBits=0,replayDecisions=0;
        int subTypesRead = 0; var subCounts = new TreeMap<Integer,Integer>();
        var residueCsv = new StringBuilder("slice,macroblock,cbp,transform8,delta_qp,qp_after,category,block,scan_position,level,end_of_slice_flag\n");
        var motionCsv = new StringBuilder("slice,macroblock,block,ref,dx,dy,neighbor_x,neighbor_y\n");
        var subCsv = new StringBuilder("slice,macroblock,sub_index,sub_mb_type,width,height,x,y,bins_through_subtype,range_after,offset_after,bits_read\n");
        var typeCounts = new TreeMap<String,Integer>();
        var typesCsv = new StringBuilder("slice,macroblock,qp,init_idc,mb_type,category,reference_marker,reference_partition,bins,range_after,offset_after,bits_read\n");
        var csv = new StringBuilder("slice,macroblock,qp,cabac_init_idc,ctx_idx,initial_state,initial_mps,java_skip,ffmpeg_skip,range_after,offset_after,bits_read,end_of_slice_flag\n");
        for (var unit : CabacPreflight.units(source)) {
            byte[] nal = Arrays.copyOfRange(source, unit.start(), unit.end());
            byte[] rbsp = CabacPreflight.unescape(nal).bytes(); int type = nal[0] & 31;
            if (type == 7) { sps.verify(rbsp); sawSps = true; }
            if (type == 8) { pps.verify(rbsp); sawPps = true; }
            if (type != 1 && type != 5) continue;
            if (!sawSps || !sawPps || slice >= slices.size() || slice >= reference.size())
                throw new IllegalArgumentException("Source/header/reference association");
            Header h = slices.get(slice); h.verify(rbsp);
            require(h.value("first_mb_in_slice"), 0, "first macroblock");
            require(h.value("pic_parameter_set_id"), 0, "slice/PPS association");
            int sliceType = h.value("slice_type") % 5; Frame frame = reference.get(slice);
            if (sliceType == 2) { if (frame.type != 'I') throw new AssertionError("I frame association"); }
            else {
                require(sliceType, 0, "P slice only");
                if (frame.type != 'P') throw new AssertionError("P frame association");
                int initIdc = h.value("cabac_init_idc");
                if (!prefix) require(initIdc, 1, "initialization table");
                if (initIdc < 0 || initIdc > 2) throw new IllegalArgumentException("Initialization table");
                if (h.end() % 8 != 0) throw new IllegalArgumentException("Unaligned header");
                int qp = 26 + pps.value("pic_init_qp_minus26") + h.value("slice_qp_delta");
                if (qp < 0 || qp > 51) throw new IllegalArgumentException("Invalid QP");
                // Every previously decoded MB in this prefix is skipped. Available left/top
                // neighbors therefore contribute zero; unavailable neighbors also contribute zero.
                // Stop at the first non-skipped MB before any of its syntax is read.
                int[][] coefficients = {{23, 33}, {22, 25}, {29, 16}};
                var context = CabacArithmetic.Context.initialize(coefficients[initIdc][0], coefficients[initIdc][1], qp);
                var replay = residuals ? new CabacPrefixReplay() : null;
                var arithmetic = new CabacArithmetic(rbsp, h.end() / 8, replay);
                int run = 0;
                for (int mb = 0; mb < frame.markers.length(); mb++) {
                    int state = context.state(), mps = context.mps();
                    int bin = arithmetic.regular(context), expected = frame.markers.charAt(mb) == 'S' ? 1 : 0;
                    if (bin != expected) throw new AssertionError("mb_skip_flag differs at slice " + slice + " MB " + mb);
                    int termination = -1;
                    if (prefix && bin == 1) {
                        termination = arithmetic.terminate(); terminations++; run++;
                        int expectedEnd = mb == frame.markers.length() - 1 ? 1 : 0;
                        if (termination != expectedEnd) throw new AssertionError("end_of_slice_flag differs at slice " + slice + " MB " + mb);
                        terminalOnes += termination;
                    }
                    csv.append(slice).append(',').append(mb).append(',').append(qp).append(',').append(initIdc)
                            .append(",11,").append(state).append(',').append(mps).append(',').append(bin).append(',').append(expected)
                            .append(',').append(arithmetic.range()).append(',').append(arithmetic.offset())
                            .append(',').append(arithmetic.bitsRead()).append(',').append(termination).append('\n');
                    tested++; skipped += bin;
                    if (mbTypes && bin == 0) {
                        var typeInput = new CabacMbType.Input(arithmetic, initIdc, qp);
                        var decoded = CabacMbType.decode(typeInput);
                        if (decoded.marker() != frame.markers.charAt(mb) || decoded.partition() != frame.partitions.charAt(mb))
                            throw new AssertionError("mb_type category/partition differs at slice " + slice + " MB " + mb + " decoded=" + decoded);
                        typesTested++; typeCounts.merge(decoded.category(), 1, Integer::sum);
                        typesCsv.append(slice).append(',').append(mb).append(',').append(qp).append(',').append(initIdc)
                                .append(',').append(decoded.value()).append(',').append(decoded.category()).append(',')
                                .append(frame.markers.charAt(mb)).append(',').append(frame.partitions.charAt(mb)).append(',')
                                .append(typeInput.trace()).append(',').append(arithmetic.range()).append(',').append(arithmetic.offset())
                                .append(',').append(arithmetic.bitsRead()).append('\n');
                        CabacSubType.Type[] subs = new CabacSubType.Type[4];
                        if (subTypes && decoded.value() == 3) {
                            for (int sub = 0; sub < 4; sub++) {
                                var subType = CabacSubType.decode(typeInput); subs[sub] = subType;
                                int x = (mb % width) * 16 + (sub % 2) * 8;
                                int y = (mb / width) * 16 + (sub / 2) * 8;
                                subCounts.merge(subType.value(), 1, Integer::sum); subTypesRead++;
                                subCsv.append(slice).append(',').append(mb).append(',').append(sub).append(',').append(subType.value())
                                        .append(',').append(subType.width()).append(',').append(subType.height()).append(',').append(x)
                                        .append(',').append(y).append(',').append(typeInput.trace()).append(',').append(arithmetic.range())
                                        .append(',').append(arithmetic.offset()).append(',').append(arithmetic.bitsRead()).append('\n');
                            }
                        }
                        if(motion && decoded.value()<=3){
                            int count=pps.value("num_ref_idx_l0_default_active_minus1")+1;
                            if(h.value("num_ref_idx_active_override_flag")==1)count=h.value("num_ref_idx_l0_active_minus1")+1;
                            for(var mv:CabacMotion.decode(new CabacMotion.Input(arithmetic,initIdc,qp),decoded.value(),subs,count))
                                motionCsv.append(slice).append(',').append(mb).append(',').append(mv.n()).append(',').append(mv.ref()).append(',').append(mv.dx()).append(',').append(mv.dy()).append(',').append(mv.neighborX()).append(',').append(mv.neighborY()).append('\n');
                            if(residuals) {
                                boolean allow8=pps.value("transform_8x8_mode_flag")==1;
                                if(decoded.value()==3)for(var sub:subs)allow8 &= sub.value()==0;
                                var residue=CabacResidual.decode(new CabacResidual.Input(arithmetic,initIdc,qp),mb,width,qp,allow8);
                                int end=arithmetic.terminate();
                                if(end!=(mb==frame.markers.length()-1?1:0))throw new AssertionError("residue termination mismatch");
                                replayBits+=replay.verify(rbsp,h.end()/8,arithmetic);replayDecisions+=replay.decisions();residueMbs++;
                                var prefixRow=slice+","+mb+","+residue.cbp()+","+residue.transform8()+","+residue.deltaQp()+","+residue.qp()+",";
                                residueCsv.append(prefixRow).append("-1,-1,-1,0,").append(end).append('\n');
                                for(var block:residue.blocks())for(int pos=0;pos<block.coefficients().length;pos++)
                                    residueCsv.append(prefixRow).append(block.category()).append(',').append(block.index()).append(',').append(pos).append(',').append(block.coefficients()[pos]).append(',').append(end).append('\n');
                            }

                        }
                    }
                    if (!prefix || bin == 0 || termination == 1) break;
                }
                longestRun = Math.max(longestRun, run);
            }
            slice++;
        }
        if (slice != slices.size() || slice != reference.size() || tested == 0) throw new AssertionError("Counts/one slice per frame");
        Files.writeString(Path.of(args[3]), csv.toString());
        if(residuals) System.out.println("PASS residue_prefix_replay macroblocks="+residueMbs+" bits="+replayBits+" decisions="+replayDecisions+"; uses observed probability states and final offset; not a coefficient encoder");
        if(residuals) Files.writeString(Path.of(args[3] + ".residuals.csv"), residueCsv.toString());
        if (motion) Files.writeString(Path.of(args[3] + ".motion.csv"), motionCsv.toString());
        if (mbTypes) Files.writeString(Path.of(args[3] + ".types.csv"), typesCsv.toString());
        if (subTypes) { Files.writeString(Path.of(args[3] + ".subtypes.csv"), subCsv.toString());
            System.out.println("DECODED sub_mb_types=" + subTypesRead + " counts=" + subCounts + "; external_geometry_comparison_required"); }
        if (mbTypes) System.out.println("PASS mb_type_categories=" + typesTested + " counts=" + typeCounts + " mismatches=0; intra16 prediction/cbp not independently checked");
        System.out.println("PASS frames=" + slice + " skip_flags=" + tested + " skip_ones=" + skipped + " termination_flags=" + terminations + " terminal_ones=" + terminalOnes + " longest_skip_run=" + longestRun + " mismatches=0 external_reference=FFmpeg_mb_type prefix=" + prefix);
        System.out.println("NOT_DONE: remaining macroblocks, CABAC encoder, byte-exact CABAC roundtrip, new compression measurement");
    }
}
