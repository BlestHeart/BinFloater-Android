package com.binf.ime;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.List;

/** 诊断：segmentations 在长数字串上的规模与耗时，判断 MAX_COMBOS=192 是否该调。 */
public final class T9ScaleProbe {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        T9Engine t9 = T9Engine.getDefault();
        String[] cases = {
                "64426", "94664486", "9466448626643",
                "94464946432664486",                 // 17 位
                "94464946432664486944",              // 20 位
                "944649464326644869444944",          // 24 位（上限）
                "999999999999999999999999",          // 24 位 9
        };
        for (String d : cases) {
            long t0 = System.nanoTime();
            List<List<String>> cap = t9.segmentations(d, 192);
            long t1 = System.nanoTime();
            List<List<String>> full = t9.segmentations(d, 500000);
            long t2 = System.nanoTime();
            out.println("digits=" + d + " (len=" + d.length() + ")");
            out.println("  cap192: " + cap.size() + " 条, " + (t1 - t0) / 1000 + "us");
            out.println("  全量  : " + full.size() + " 条, " + (t2 - t1) / 1000 + "us");
            out.println();
        }
        out.close();
    }
}
