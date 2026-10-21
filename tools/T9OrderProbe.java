package com.binf.ime;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.List;

/** 诊断：DFS 截断在长数字串上会丢掉长音节读法，验证「长音节优先」能否救回来。 */
public final class T9OrderProbe {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        PinyinEngine e = PinyinEngine.loadForTest(new java.io.FileInputStream(args[1]));
        T9Engine t9 = T9Engine.getDefault();

        String[] cases = {"9466448626643", "94464946432664486", "94464946432664486944"};
        for (String d : cases) {
            out.println("digits=" + d + " len=" + d.length());
            out.println("  byScore(8) 截断192 = " + t9.pinyinListByScore(d, e, 8));
            // 全量枚举后自己按分数排序，看看「理想结果」是什么
            List<List<String>> all = t9.segmentations(d, 500000);
            out.println("  全量切分数 = " + all.size());
            out.println("  segment(8) 截断192 = " + t9.segment(d, 8));
            out.println();
        }
        out.close();
    }
}
