package com.binf.ime;

import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/** 临时诊断：把真实词库的查询结果按 UTF-8 写到文件，避免 Windows 控制台乱码。 */
public class DictProbe {
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[1]), true, "UTF-8");
        InputStream in = new FileInputStream(args[0]);
        PinyinEngine e = PinyinEngine.loadForTest(in);
        in.close();
        out.println("size=" + e.size() + " syllables=" + e.syllableCount()
                + " maxWordChars=" + e.maxWordChars()
                + " maxKeySyllables=" + e.maxKeySyllables());
        for (String k : new String[]{"ni", "hao", "nihao", "zhong", "guo", "zhongguo",
                "bei", "jing", "beijing", "xie", "xiexie", "zai", "jian", "zaijian",
                "wo", "xiang", "chi", "fan", "duibuqi", "o", "哦"}) {
            out.print("exactKey(\"" + k + "\") = [");
            for (PinyinEngine.Candidate c : e.exactKey(k, 8)) {
                out.print(c.text + "(" + c.weight + ") ");
            }
            out.println("]");
        }
        out.println("--- query(\"zhongguo\") ---");
        for (PinyinEngine.Candidate c : e.query("zhongguo", 10)) {
            out.println("  " + c.text + " w=" + c.weight);
        }
        out.println("--- syllables ---");
        String[] syl = e.syllables();
        out.println("count=" + syl.length + " first10=" + java.util.Arrays.toString(
                java.util.Arrays.copyOf(syl, Math.min(10, syl.length))));
        boolean bad = false;
        for (String s : syl) if (s.indexOf('\0') >= 0 || s.isEmpty()) { bad = true; out.println("BAD SYL [" + s + "]"); break; }
        out.println("syllables 干净 = " + !bad);
        out.println("--- decoder ---");
        SentenceDecoder d = SentenceDecoder.forEngine(e, 20);
        for (String py : new String[]{"nihao", "zhongguo", "beijing", "xiexie", "zaijian", "woxiangchifan", "jintiantianqi"}) {
            out.print(py + " -> ");
            for (SentenceDecoder.Result r : d.decode(py, 3)) {
                out.print("[" + r.text + " full=" + r.isFull() + "] ");
            }
            out.println();
        }
        out.flush();
        out.close();
    }
}
