package com.binf.ime;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.List;

/** 临时诊断：九键数字串展开成多种拼音后，各自整句解码的分数是否可比。 */
public class T9Probe {
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[1]), true, "UTF-8");
        InputStream in = new FileInputStream(args[0]);
        PinyinEngine e = PinyinEngine.loadForTest(in);
        in.close();
        SentenceDecoder d = SentenceDecoder.forEngine(e, 20);
        T9Engine t9 = T9Engine.getDefault();
        out.println("totalWeight=" + e.totalWeight());
        for (String digits : new String[]{"64426", "94664486", "94", "2", "644986"}) {
            out.println("=== " + digits + " ===");
            List<String> pys = t9.pinyinListByScore(digits, e, 8);
            out.println("  pinyinListByScore(" + digits + ") = " + pys);
            out.println("  pinyinList(DFS) = " + t9.pinyinList(digits, 8));
            for (String py : pys) {
                out.print("  " + py + ": ");
                for (SentenceDecoder.Result r : d.decode(py, 3)) {
                    out.print("[" + r.text + " s=" + r.score + " full=" + r.isFull() + "] ");
                }
                out.println();
            }
            out.print("  t9.candidates: ");
            for (PinyinEngine.Candidate c : t9.candidates(digits, e, 10)) {
                out.print(c.text + "(" + c.weight + ") ");
            }
            out.println();
        }
        out.flush();
        out.close();
    }
}
