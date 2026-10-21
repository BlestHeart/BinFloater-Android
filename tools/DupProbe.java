package com.binf.ime;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 诊断：词典里同 key 同词的重复条目有多少。 */
public final class DupProbe {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        PinyinEngine e = PinyinEngine.loadForTest(new java.io.FileInputStream(args[1]));
        out.println("总条数 = " + e.size());

        String[] keys = {"zh", "zhe", "zhei", "zhei", "zhong", "ni", "hao", "y", "shi", "yi"};
        for (String k : keys) {
            List<PinyinEngine.Candidate> cs = e.exactKey(k, 1000);
            Map<String, Integer> seen = new HashMap<String, Integer>();
            List<String> dups = new ArrayList<String>();
            for (PinyinEngine.Candidate c : cs) {
                Integer n = seen.get(c.text);
                if (n == null) seen.put(c.text, 1);
                else { seen.put(c.text, n + 1); if (n == 1) dups.add(c.text); }
            }
            out.println("key=\"" + k + "\" 条数=" + cs.size() + " 唯一=" + seen.size()
                    + (dups.isEmpty() ? "" : "  重复: " + dups));
            if (!dups.isEmpty()) {
                for (PinyinEngine.Candidate c : cs) {
                    if (dups.contains(c.text)) out.println("    " + c.text + " w=" + c.weight);
                }
            }
        }
        out.close();
    }
}
