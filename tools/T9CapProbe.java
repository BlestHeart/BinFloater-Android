package com.binf.ime;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * 诊断工具（非回归测试）：检查 segmentations 的 MAX_COMBOS=192 截断
 * 是否会漏掉「长音节切分」，即 94664486 这类串的 zhongguo 是否还排得进来。
 */
public final class T9CapProbe {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        PinyinEngine e = PinyinEngine.loadForTest(
                new java.io.FileInputStream(args[1]));
        T9Engine t9 = T9Engine.getDefault();

        String[] cases = {
                "64426",        // nihao
                "94664486",     // zhongguo
                "96 4 4 3 3",   // 占位
                "94433",        // xiexie? 9=wxyz 4=ghi 3=def
                "94 66 44 86",
                "9466448633",   // zhongguo + de?
                "96662664",     // woshishui? no
                "9664264486",   // wo? ...
                "9466448626643",// 更长
        };
        for (String c : cases) {
            String d = c.replace(" ", "");
            if (d.isEmpty()) continue;
            List<List<String>> cap192 = t9.segmentations(d, 192);
            List<List<String>> big = t9.segmentations(d, 100000);
            out.println("digits=" + d + "  切分数(<=192)=" + cap192.size()
                    + "  实际总数=" + big.size()
                    + (big.size() > 192 ? "  ★被截断" : ""));
            List<String> top = t9.pinyinListByScore(d, e, 8);
            out.println("  byScore(8) = " + top);
            // 看看如果放开截断，前 8 是什么
            List<String> all = t9.segment(d, 100000);
            out.println("  全量切分数=" + all.size());
            out.println();
        }
        out.close();
    }
}
