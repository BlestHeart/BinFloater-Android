package com.binf.ime;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 诊断：前缀查询的 maxScan 截断会不会漏掉高权重候选。
 *
 * query() 里有 maxScan = max(limit*8, 512) 的扫描上限，而词条是按 (key, -weight) 排的，
 * 前缀匹配会横跨很多 key。所以扫到的前 512 条是「字典序靠前的那些 key」，
 * 不一定是权重最高的那些 —— 需要验证。
 */
public final class QueryProbe {

    static final class C {
        final String text; final int w;
        C(String t, int wt) { text = t; w = wt; }
        public String toString() { return text + "(" + w + ")"; }
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(args[0]), true, "UTF-8");
        PinyinEngine e = PinyinEngine.loadForTest(new java.io.FileInputStream(args[1]));

        String[] prefixes = {"y", "yi", "yo", "zh", "zho", "shi", "ni", "hao", "zhong", "jintian"};
        for (String p : prefixes) {
            List<C> all = asC(e.query(p, 1000000));   // 大 limit → maxScan 也放大 → 近似全扫
            List<C> fast = asC(e.query(p, 10));       // 线上真实调用
            List<C> brute = topN(all, 10);
            boolean same = fast.toString().equals(brute.toString());
            out.println("prefix=\"" + p + "\" 匹配=" + all.size()
                    + (same ? "  ✔ 一致" : "  ✗ 不一致"));
            out.println("    query(10)  = " + fast);
            if (!same) {
                out.println("    全扫 top10 = " + brute);
            }
        }
        out.close();
    }

    static List<C> asC(List<PinyinEngine.Candidate> in) {
        List<C> o = new ArrayList<C>();
        for (PinyinEngine.Candidate c : in) o.add(new C(c.text, c.weight));
        return o;
    }

    static List<C> topN(List<C> in, int n) {
        List<C> o = new ArrayList<C>(in);
        Collections.sort(o, new Comparator<C>() {
            public int compare(C a, C b) {
                if (a.w != b.w) return b.w - a.w;
                return a.text.compareTo(b.text);
            }
        });
        return o.size() > n ? new ArrayList<C>(o.subList(0, n)) : o;
    }
}
