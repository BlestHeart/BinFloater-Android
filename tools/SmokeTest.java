package com.binf.ime;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端到端冒烟：用**真实词库**跑一遍输入法实际的取候选路径。
 *
 * 和 DecodeTest 的区别：DecodeTest 用假引擎喂人造词表，只验证算法；
 * 这里用真词典，验证「算法 + 真词库 + 真权重」合起来的结果是对的。
 * 输错一个候选就说明线上会打错字，属于必测项。
 */
public class SmokeTest {
    static int pass = 0, fail = 0;
    /** Windows 控制台是 GBK，直接 println 中文会乱码，统一用 UTF-8 流。 */
    static java.io.PrintStream out = System.out;
    /** 中文明细写 out（可能是 UTF-8 文件），控制台另发一行纯 ASCII 的 PASS/FAIL 供脚本计数。 */
    static boolean echoAscii = false;
    static int seq = 0;

    static void check(String label, boolean ok, String detail) {
        seq++;
        if (ok) { pass++; out.println("  PASS  " + label); }
        else { fail++; out.println("  FAIL  " + label + "  " + detail); }
        if (echoAscii) {
            // 不带中文，避免控制台 GBK 乱码；只给 run_tests.sh 数数用
            System.out.println((ok ? "  PASS  #" : "  FAIL  #") + seq);
        }
    }

    static void expectFirst(String pinyin, String expect, PinyinEngine e, SentenceDecoder d) {
        List<SentenceDecoder.Result> rs = d.decode(pinyin, 3);
        String got = "";
        for (SentenceDecoder.Result r : rs) {
            if (r.isFull()) { got = r.text; break; }
        }
        check(pinyin + " -> " + expect, expect.equals(got),
                expect.equals(got) ? "" : "实际 " + got);
    }

    static void expectContains(String label, List<PinyinEngine.Candidate> list, String want) {
        boolean ok = false;
        for (PinyinEngine.Candidate c : list) if (want.equals(c.text)) { ok = true; break; }
        check(label + " 含「" + want + "」", ok, ok ? "" : "实际 " + texts(list));
    }

    static List<String> texts(List<PinyinEngine.Candidate> list) {
        List<String> out = new java.util.ArrayList<String>();
        for (PinyinEngine.Candidate c : list) out.add(c.text);
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            out = new java.io.PrintStream(new java.io.FileOutputStream(args[1]), true, "UTF-8");
            // 中文明细进了文件，控制台只留 ASCII 计数行给 run_tests.sh
            echoAscii = true;
        }
        String dictPath = args.length > 0 ? args[0] : "app/assets/pinyin.dict.gz";
        InputStream in = new FileInputStream(dictPath);
        PinyinEngine e = PinyinEngine.loadForTest(in);
        in.close();
        check("词库加载", e.size() > 500000, "size=" + e.size());
        SentenceDecoder d = SentenceDecoder.forEngine(e, 20);
        T9Engine t9 = T9Engine.getDefault();

        out.println("=== 全键盘整句（真词库） ===");
        expectFirst("nihao", "你好", e, d);
        expectFirst("woxiangchifan", "我想吃饭", e, d);
        expectFirst("jintiantianqi", "今天天气", e, d);
        expectFirst("zhongguo", "中国", e, d);
        expectFirst("beijing", "北京", e, d);
        expectFirst("xiexie", "谢谢", e, d);
        expectFirst("zaijian", "再见", e, d);
        expectFirst("duibuqi", "对不起", e, d);
        expectFirst("meiguanxi", "没关系", e, d);
        expectFirst("wobuzhidao", "我不知道", e, d);

        out.println("=== 歧义切分 ===");
        // xian 既可是「先」也可是「西安」，看哪个词频高；只要是个合理词即可
        List<SentenceDecoder.Result> xian = d.decode("xian", 5);
        check("xian 有结果", !xian.isEmpty(), "空");
        if (!xian.isEmpty()) out.println("        xian 首选 = " + xian.get(0).text);
        // fangan：'fang an' 还是 'fan gan'，两种都要能出
        List<String> fangan = new java.util.ArrayList<String>();
        for (SentenceDecoder.Result r : d.decode("fangan", 8)) fangan.add(r.text);
        check("fangan 有结果", !fangan.isEmpty(), "");
        if (!fangan.isEmpty()) out.println("        fangan 首选 = " + fangan.get(0));

        out.println("=== 九键（真词库） ===");
        List<PinyinEngine.Candidate> c1 = t9.candidates("64426", e, 12);
        expectContains("64426", c1, "你好");
        out.println("        64426 = " + texts(c1));
        List<PinyinEngine.Candidate> c2 = t9.candidates("94664486", e, 12);
        expectContains("94664486", c2, "中国");
        out.println("        94664486 = " + texts(c2));
        List<PinyinEngine.Candidate> c3 = t9.candidates("94", e, 12);
        check("94 有候选", !c3.isEmpty(), "");
        out.println("        94 = " + texts(c3));
        List<PinyinEngine.Candidate> c4 = t9.candidates("2", e, 8);
        check("单数字 2 有候选", !c4.isEmpty(), "");
        out.println("        2 = " + texts(c4));

        out.println("=== 九键整句解码（真词库，照抄 BinImeService.buildCandidates） ===");
        // 严格镜像服务里的九键分支：展开 → 逐个整句解码 → 按分数统一排序 → 去重收集
        String digits = "64426";
        List<SentenceDecoder.Result> allSent = new ArrayList<SentenceDecoder.Result>();
        for (String py : t9.pinyinListByScore(digits, e, 8)) {
            for (SentenceDecoder.Result r : d.decode(py, 3)) {
                if (r.isFull() && !r.text.isEmpty()) allSent.add(r);
            }
        }
        Collections.sort(allSent, SentenceDecoder.BY_SCORE);
        java.util.LinkedHashSet<String> sentSet = new java.util.LinkedHashSet<String>();
        for (SentenceDecoder.Result r : allSent) {
            if (sentSet.add(r.text) && sentSet.size() >= 12) break;
        }
        check("64426 能整句解出非空", !sentSet.isEmpty(), "");
        check("64426 整句候选含「你好」", sentSet.contains("你好"),
                "实际 " + sentSet);
        out.println("        64426 整句候选 = " + sentSet);
        // 「中国」要压过「中火」：音节排序把 zhonghuo 排在前面，
        // 但整句分数 zhongguo(-12) > zhonghuo(-13)，必须按分数合并
        String dg = "94664486";
        List<SentenceDecoder.Result> allSent2 = new ArrayList<SentenceDecoder.Result>();
        for (String py : t9.pinyinListByScore(dg, e, 8)) {
            for (SentenceDecoder.Result r : d.decode(py, 3)) {
                if (r.isFull() && !r.text.isEmpty()) allSent2.add(r);
            }
        }
        Collections.sort(allSent2, SentenceDecoder.BY_SCORE);
        String first2 = allSent2.isEmpty() ? "" : allSent2.get(0).text;
        check("94664486 整句首选「中国」", "中国".equals(first2), "实际 " + first2);
        out.println("        94664486 整句首选 = " + first2);

        out.println("=== 九键展示拼音切分 ===");
        String disp = t9.displayPinyin("64426", e);
        check("displayPinyin 不为空", disp != null && !disp.isEmpty(), disp);
        out.println("        displayPinyin(64426) = " + disp);
        check("displayPinyin 不是逐字母", !disp.replace(" ", "").equals("nihao")
                || disp.contains(" "), disp);

        out.println("=== 数字误触：0/1 开头 ===");
        check("0 开头无候选", t9.candidates("0", e, 5).isEmpty(), "");
        check("1 开头无候选", t9.candidates("1", e, 5).isEmpty(), "");
        check("01 无候选", t9.candidates("01", e, 5).isEmpty(), "");

        out.println();
        out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        out.flush();
        if (fail > 0) System.exit(1);
    }
}
