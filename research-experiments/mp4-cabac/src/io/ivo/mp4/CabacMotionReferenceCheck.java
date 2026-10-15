package io.ivo.mp4;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/** Exact direct comparison of ref_idx_l0, both MVD components and their causal sums. */
public final class CabacMotionReferenceCheck {
    record Key(int frame,int x,int y,int block) {}
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("motion.csv reference.log width_in_mbs");
        int width=Integer.parseInt(args[2]);if(width<=0)throw new IllegalArgumentException("width");
        Map<Key,List<Integer>> required=new HashMap<>();List<String> rows=Files.readAllLines(Path.of(args[0]));
        if(rows.isEmpty()||!rows.get(0).equals("slice,macroblock,block,ref,dx,dy,neighbor_x,neighbor_y"))throw new IllegalArgumentException("CSV schema");
        for(int i=1;i<rows.size();i++){var a=Arrays.stream(rows.get(i).split(",")).map(Integer::parseInt).toList();if(a.size()!=8||a.get(0)<0||a.get(1)<0||a.get(2)<0||a.get(2)>15||a.get(3)<0||a.get(3)>31||a.get(6)<0||a.get(7)<0)throw new IllegalArgumentException("CSV values");
            Key k=new Key(a.get(0),a.get(1)%width,a.get(1)/width,a.get(2));if(required.put(k,a.subList(3,8))!=null)throw new IllegalArgumentException("Duplicate Java key");}
        Set<Key> seen=new HashSet<>();boolean active=false;int frame=-1,nonzero=0;Map<Integer,Integer> refs=new TreeMap<>();
        Pattern p=Pattern.compile("IVO_MOTION (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (-?\\d+) (-?\\d+) (\\d+) (\\d+)");
        for(String l:Files.readAllLines(Path.of(args[1]))){if(l.contains("Stream mapping:")){active=true;continue;}if(!active)continue;if(l.contains("IVO_FRAME"))frame++;var m=p.matcher(l);if(!m.find()||!m.group(3).equals("0"))continue;
            Key k=new Key(frame,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(4)));var value=required.get(k);if(value==null)continue;if(!seen.add(k))throw new IllegalArgumentException("Duplicate reference key");List<Integer> expected=new ArrayList<>();for(int j=5;j<=9;j++)expected.add(Integer.parseInt(m.group(j)));if(!value.equals(expected))throw new AssertionError("Motion differs at "+k+": Java="+value+" reference="+expected);refs.merge(value.get(0),1,Integer::sum);if(value.get(1)!=0||value.get(2)!=0)nonzero++;
        }
        if(!active||seen.isEmpty()||seen.size()!=required.size())throw new AssertionError("Missing reference values");
        System.out.println("PASS motion_pairs="+seen.size()+" components="+2*seen.size()+" refs="+refs+" nonzero_pairs="+nonzero+" mismatches=0");
    }
}
