package io.ivo.mp4;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Compare raw quantized coefficients including every zero, not pixels or dequantized values. */
public final class CabacResidualReferenceCheck {
    record Mb(int frame,int x,int y) {}
    record Coefficient(Mb mb,int category,int block,int position) {}
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("residuals.csv reference.log width_mbs frames");
        Path csvPath=Path.of(args[0]),referencePath=Path.of(args[1]);
        int width=Integer.parseInt(args[2]),frames=Integer.parseInt(args[3]);
        if(width<=0||frames<=0)throw new IllegalArgumentException("dimensions/count");
        var rows=Files.readAllLines(csvPath);
        if(rows.isEmpty()||!rows.get(0).equals("slice,macroblock,cbp,transform8,delta_qp,qp_after,category,block,scan_position,level,end_of_slice_flag"))throw new IllegalArgumentException("CSV schema");
        Map<Mb,List<Integer>> summaries=new HashMap<>(); Map<Coefficient,Integer> coefficients=new HashMap<>();
        for(int i=1;i<rows.size();i++) {
            int[] a=Arrays.stream(rows.get(i).split(",")).mapToInt(Integer::parseInt).toArray();
            if(a.length!=11||a[0]<0||a[0]>=frames||a[1]<0||a[2]<0||a[2]>47||a[3]<0||a[3]>1||a[5]<0||a[5]>51||a[10]<0||a[10]>1)throw new IllegalArgumentException("CSV values");
            var mb=new Mb(a[0],a[1]%width,a[1]/width);
            if(a[6]==-1) {
                if(a[7]!=-1||a[8]!=-1||a[9]!=0||summaries.put(mb,List.of(a[2],a[3],a[5]))!=null)throw new IllegalArgumentException("Summary duplicate/values");
            } else {
                int size=switch(a[6]){case 2->16;case 3->4;case 4->15;case 5->64;default->throw new IllegalArgumentException("category");};
                if(a[8]<0||a[8]>=size||coefficients.put(new Coefficient(mb,a[6],a[7],a[8]),a[9])!=null)throw new IllegalArgumentException("Coefficient duplicate/position");
            }
        }
        if(summaries.isEmpty()||coefficients.isEmpty())throw new IllegalArgumentException("No Java evidence");
        Map<Coefficient,Integer> reference=new HashMap<>(); Set<Mb> seen=new HashSet<>();
        Pattern coeff=Pattern.compile("IVO_COEFF (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (-?\\d+)");
        Pattern summary=Pattern.compile("IVO_RESIDUAL (\\d+) (\\d+) (\\d+) (\\d+) (\\d+)");
        boolean active=false;int frame=-1;
        for(String line:Files.readAllLines(referencePath)) {
            if(line.contains("Stream mapping:")){active=true;continue;}if(!active)continue;
            if(line.contains("IVO_FRAME"))frame++;
            var c=coeff.matcher(line);
            if(c.find()) {
                var mb=new Mb(frame,Integer.parseInt(c.group(1)),Integer.parseInt(c.group(2)));
                if(!summaries.containsKey(mb))continue;
                var key=new Coefficient(mb,Integer.parseInt(c.group(3)),Integer.parseInt(c.group(4)),Integer.parseInt(c.group(5)));
                int level=Integer.parseInt(c.group(6));
                if(level==0||!coefficients.containsKey(key)||reference.put(key,level)!=null)throw new AssertionError("Unexpected reference coefficient "+key);
            }
            var m=summary.matcher(line);
            if(m.find()) {
                var mb=new Mb(frame,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)));
                if(!summaries.containsKey(mb))continue;
                var value=List.of(Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)),Integer.parseInt(m.group(5)));
                if(!seen.add(mb)||!summaries.get(mb).equals(value))throw new AssertionError("Residual summary differs at "+mb);
            }
        }
        if(frame+1!=frames||seen.size()!=summaries.size())throw new AssertionError("Reference frame/macroblock count");
        int nonzero=0;
        for(var e:coefficients.entrySet()) {
            if(!seen.contains(e.getKey().mb())||!e.getValue().equals(reference.getOrDefault(e.getKey(),0)))throw new AssertionError("Coefficient differs at "+e.getKey()+": Java="+e.getValue()+" reference="+reference.get(e.getKey()));
            if(e.getValue()!=0)nonzero++;
        }
        System.out.println("PASS residue_macroblocks="+seen.size()+" coefficient_slots="+coefficients.size()+" nonzero_coefficients="+nonzero+" zero_coefficients="+(coefficients.size()-nonzero)+" frames="+frames+" mismatches=0");
    }
}
