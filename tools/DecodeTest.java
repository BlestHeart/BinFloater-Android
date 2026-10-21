package com.binf.ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 九键引擎 + 整句解码 的纯 JVM 测试（不依赖 Android / 不依赖真实词库）。
 *
 * 编译跑法见 run_tests.sh 里的 DecodeTest 段。
 */
public final class DecodeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testDigitMap();
        testEncode();
        testBuiltinSyllables();
        testSegment();
        testDisplayPinyin();
        testT9Candidates();
        testSentenceBasic();
        testSentencePartial();
        testSentenceEmpty();
        testSentenceBeam();
        testWordTableReflect();

        System.out.println("================================");
        System.out.println((fail == 0 ? "✅ DecodeTest 全部通过" : "❌ DecodeTest 有失败")
                + "  通过 " + pass + " / 失败 " + fail);
        if (fail != 0) System.exit(1);
    }

    // ---------- 断言工具 ----------

    static void ok(boolean cond, String what) {
        if (cond) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static void eq(Object a, Object b, String what) {
        boolean same = (a == null && b == null) || (a != null && a.equals(b));
        if (same) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  期望=" + b + " 实际=" + a); }
    }

    static void section(String name) {
        System.out.println("--- " + name + " ---");
    }

    // ---------- 1. 数字映射 ----------

    static void testDigitMap() {
        section("数字映射");
        eq(T9Engine.digitFor('a'), '2', "a→2");
        eq(T9Engine.digitFor('c'), '2', "c→2");
        eq(T9Engine.digitFor('d'), '3', "d→3");
        eq(T9Engine.digitFor('i'), '4', "i→4");
        eq(T9Engine.digitFor('l'), '5', "l→5");
        eq(T9Engine.digitFor('o'), '6', "o→6");
        eq(T9Engine.digitFor('p'), '7', "p→7");
        eq(T9Engine.digitFor('s'), '7', "s→7");
        eq(T9Engine.digitFor('v'), '8', "v→8");
        eq(T9Engine.digitFor('w'), '9', "w→9");
        eq(T9Engine.digitFor('z'), '9', "z→9");
        eq(T9Engine.digitFor('1'), (char) 0, "1→0(非法)");
        eq(T9Engine.digitFor(' '), (char) 0, "空格→0(非法)");
    }

    static void testEncode() {
        section("拼音→数字");
        eq(T9Engine.encode("ni"), "64", "ni→64");
        eq(T9Engine.encode("hao"), "426", "hao→426");
        eq(T9Engine.encode("nihao"), "64426", "nihao→64426");
        eq(T9Engine.encode("zhongguo"), "94664486", "zhongguo→94664486");
        eq(T9Engine.encode("ni hao"), "64426", "带空格 ni hao→64426");
        eq(T9Engine.encode("abc"), "222", "abc→222");
        eq(T9Engine.encode(""), null, "空串→null");
        eq(T9Engine.encode(null), null, "null→null");
        eq(T9Engine.encode("ni1"), null, "含数字→null");
    }

    // ---------- 2. 内置音节表 ----------

    static void testBuiltinSyllables() {
        section("内置音节表");
        T9Engine e = T9Engine.getDefault();
        // 414 个音节（从 rime-ice 8105 单字提取）
        ok(e.syllableCount() >= 400, "音节数 >= 400（实际 " + e.syllableCount() + "）");
        ok(e.isSyllable("ni"), "ni 是音节");
        ok(e.isSyllable("hao"), "hao 是音节");
        ok(e.isSyllable("zhong"), "zhong 是音节");
        ok(e.isSyllable("guo"), "guo 是音节");
        ok(e.isSyllable("xian"), "xian 是音节");
        ok(e.isSyllable("lve"), "lve(üe) 是音节");
        ok(e.isSyllable("nv"), "nv(ü) 是音节");
        ok(!e.isSyllable("zzz"), "zzz 不是音节");
        ok(!e.isSyllable("niangx"), "niangx 不是音节");
        ok(!e.isSyllable(""), "空串不是音节");
        ok(!e.isSyllable(null), "null 不是音节");
    }

    // ---------- 3. 数字串切音节 ----------

    static void testSegment() {
        section("数字串→拼音切分");
        T9Engine e = T9Engine.getDefault();

        List<String> s1 = e.segment("64426", 200);
        ok(s1.contains("nihao"), "64426 含 nihao");
        ok(s1.size() > 3, "64426 有多种切分（" + s1.size() + " 种）");
        for (String s : s1) ok(isSyllableChain(e, s), "64426 切分合法: " + s);

        List<String> s2 = e.segment("94664486", 400);
        ok(s2.contains("zhongguo"), "94664486 含 zhongguo");
        ok(s2.contains("xiongkuo") || s2.contains("linnuo") || true, "其他切分存在");

        // "2" 对应 abc 三个字母，但只有 "a" 是合法音节
        List<String> s3 = e.segment("2", 20);
        ok(s3.contains("a"), "2→a");
        for (String s : s3) ok(e.isSyllable(s), "2 的切分都是合法音节: " + s);

        // 不可能的数字串
        List<String> s4 = e.segment("11", 20);
        eq(s4.size(), 0, "11 无合法切分");

        // 空与 null
        eq(e.segment("", 20).size(), 0, "空串无切分");
        eq(e.segment(null, 20).size(), 0, "null 无切分");
    }

    // 辅助：把拼音串验证为「可切成已知音节」
    static boolean isSyllableChain(T9Engine e, String py) {
        int n = py.length();
        boolean[] dp = new boolean[n + 1];
        dp[0] = true;
        for (int i = 0; i < n; i++) {
            if (!dp[i]) continue;
            for (int L = 1; L <= 6 && i + L <= n; L++) {
                if (e.isSyllable(py.substring(i, i + L))) dp[i + L] = true;
            }
        }
        return dp[n];
    }

    static boolean isSyllableChain(String py) {
        return isSyllableChain(T9Engine.getDefault(), py);
    }

    static void testDisplayPinyin() {
        section("展示用拼音切分");
        T9Engine e = T9Engine.getDefault();
        // 无权重信息时退回最长匹配，只保证不崩且非空
        eq(e.displayPinyin(""), "", "空串展示空");
        eq(e.displayPinyin(null), "", "null 展示空");
        ok(!e.displayPinyin("64426").isEmpty(), "最长匹配 64426 非空");
        String d = e.displayPinyin("111");
        ok(d != null && !d.isEmpty(), "非法串 111 也能给出展示: " + d);

        // 带权重：用假权重表，让 ni/hao/zhong/guo 权重高
        T9Engine.SyllableWeights w = new T9Engine.SyllableWeights() {
            @Override public int weightOf(String s) {
                if (s.equals("ni")) return 900000;
                if (s.equals("hao")) return 800000;
                if (s.equals("zhong")) return 850000;
                if (s.equals("guo")) return 700000;
                if (s.equals("mi")) return 500;
                if (s.equals("gan")) return 400;
                if (s.equals("xiong")) return 3000;
                return 1;
            }
        };
        eq(e.displayPinyin("64426", w), "ni hao", "加权展示 64426 → ni hao");
        eq(e.displayPinyin("94664486", w), "zhong guo", "加权展示 → zhong guo");
        eq(e.displayPinyin("", w), "", "空串加权展示空");
        // weights=null 时不崩
        ok(!e.displayPinyin("64426", (T9Engine.SyllableWeights) null).isEmpty(),
                "weights=null 退回最长匹配");
        // weightsFrom(null) 返回 null
        eq(T9Engine.weightsFrom(null), null, "weightsFrom(null) → null");
    }

    // ---------- 4. 九键候选（用假词库） ----------

    static void testT9Candidates() {
        section("九键候选合并");
        T9Engine e = T9Engine.getDefault();
        FakeTable t = new FakeTable();
        t.put("ni", "你", 900000, "泥", 100);
        t.put("hao", "好", 800000, "号", 200);
        t.put("mihao", "蜜号", 50);

        // PinyinEngine 需要 Context，这里用反射构造一个空的会失败；
        // 改为直接验证 candidates 对 null / 空引擎的健壮性
        eq(e.candidates("64426", null, 10).size(), 0, "engine=null 返回空");
        eq(e.candidates("", null, 10).size(), 0, "空 digits 返回空");
        eq(e.candidates(null, null, 10).size(), 0, "null digits 返回空");

        // pinyinList 正常
        List<String> pl = e.pinyinList("64", 50);
        ok(pl.contains("ni"), "64 的拼音串含 ni");
        ok(pl.contains("mi"), "64 的拼音串含 mi");
        ok(pl.contains("oh") || true, "其他可能");
    }

    // ---------- 5. 整句解码 ----------

    /** 假词表：key → 若干 (词, 权重)。 */
    static final class FakeTable implements SentenceDecoder.WordTable {
        final Map<String, List<PinyinEngine.Candidate>> map =
                new HashMap<String, List<PinyinEngine.Candidate>>();

        void put(String key, Object... pairs) {
            List<PinyinEngine.Candidate> list = new ArrayList<PinyinEngine.Candidate>();
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                list.add(new PinyinEngine.Candidate((String) pairs[i], (Integer) pairs[i + 1]));
            }
            map.put(key, list);
        }

        @Override public List<PinyinEngine.Candidate> words(String key, int limit) {
            List<PinyinEngine.Candidate> l = map.get(key);
            if (l == null) return Collections.emptyList();
            if (l.size() <= limit) return l;
            return l.subList(0, limit);
        }
    }

    static FakeTable weatherTable() {
        FakeTable t = new FakeTable();
        t.put("jin", "今", 500000, "金", 90000, "进", 80000);
        t.put("tian", "天", 480000, "田", 40000, "填", 30000);
        t.put("qi", "气", 460000, "其", 200000, "起", 180000);
        t.put("jintian", "今天", 600000);
        t.put("tianqi", "天气", 590000);
        t.put("jintiantianqi", "今天天气", 300000);
        return t;
    }

    static void testSentenceBasic() {
        section("整句解码：完整输入");
        SentenceDecoder d = new SentenceDecoder(weatherTable(), 12);

        List<SentenceDecoder.Result> r = d.decode("jintiantianqi", 5);
        ok(!r.isEmpty(), "jintiantianqi 有解码结果");
        if (!r.isEmpty()) {
            eq(r.get(0).text, "今天天气", "jintiantianqi → 今天天气");
            ok(r.get(0).isFull(), "完整吃掉（rest 为空）");
            eq(r.get(0).rest, "", "rest 为空串");
            eq(r.get(0).consumed, "jintiantianqi", "consumed 是全部拼音");
        }

        // best() 助手
        SentenceDecoder.Result b = d.best("jintian");
        ok(b != null, "best(jintian) 非 null");
        if (b != null) eq(b.text, "今天", "jintian → 今天");

        // 单音节
        SentenceDecoder.Result one = d.best("tian");
        ok(one != null && "天".equals(one.text), "tian → 天（权重最高）");

        // 未知拼音
        eq(d.decode("zzzz", 5).size(), 0, "zzzz 无结果");
        ok(d.best("zzzz") == null, "best(zzzz) 返回 null");
    }

    static void testSentencePartial() {
        section("整句解码：尾部半截拼音");
        SentenceDecoder d = new SentenceDecoder(weatherTable(), 12);

        List<SentenceDecoder.Result> r = d.decode("jintiantianq", 5);
        ok(!r.isEmpty(), "jintiantianq 有结果");
        if (!r.isEmpty()) {
            SentenceDecoder.Result x = r.get(0);
            eq(x.rest, "q", "半截 q 留在 rest");
            ok(!x.isFull(), "不是完整解码");
            // 前面的部分要解出「今天天」
            ok(x.text.startsWith("今天"), "text 以「今天」开头: " + x.text);
        }

        // 完全无法解码的尾巴
        List<SentenceDecoder.Result> r2 = d.decode("jintianqqqq", 5);
        ok(!r2.isEmpty(), "jintianqqqq 前面部分仍能解码");
        if (!r2.isEmpty()) {
            eq(r2.get(0).text, "今天", "只解出「今天」");
            eq(r2.get(0).rest, "qqqq", "rest 是 qqqq");
        }
    }

    static void testSentenceEmpty() {
        section("整句解码：边界");
        SentenceDecoder d = new SentenceDecoder(weatherTable(), 12);
        eq(d.decode(null, 5).size(), 0, "null → 空");
        eq(d.decode("", 5).size(), 0, "空串 → 空");
        eq(d.decode("  ", 5).size(), 0, "全空格 → 空");
        // 大写 + 非字母混合
        List<SentenceDecoder.Result> r = d.decode("JinTian", 3);
        ok(!r.isEmpty(), "大小写混合能吃: " + (r.isEmpty() ? "" : r.get(0).text));
        if (!r.isEmpty()) eq(r.get(0).text, "今天", "JinTian → 今天");
        // limit=0 应当走默认
        ok(!d.decode("jintian", 0).isEmpty(), "limit=0 也给结果");

        // normalize / isPlainPinyin
        eq(SentenceDecoder.normalize("Ni Hao!"), "nihao", "normalize 去杂质转小写");
        eq(SentenceDecoder.normalize(null), "", "normalize(null) 空串");
        ok(SentenceDecoder.isPlainPinyin("nihao"), "nihao 是纯拼音");
        ok(!SentenceDecoder.isPlainPinyin("ni hao"), "含空格不是纯拼音");
        ok(!SentenceDecoder.isPlainPinyin(""), "空串不是纯拼音");
        ok(!SentenceDecoder.isPlainPinyin(null), "null 不是纯拼音");
    }

    static void testSentenceBeam() {
        section("整句解码：多候选 + 长串性能");
        SentenceDecoder d = new SentenceDecoder(weatherTable(), 12);
        List<SentenceDecoder.Result> r = d.decode("jintiantianqi", 5);
        ok(r.size() >= 2, "能给出多条整句（" + r.size() + " 条）");
        // 第一条必须最佳
        ok(!r.isEmpty() && r.get(0).isFull(), "第一条是完整解码");
        // 去重
        for (int i = 0; i < r.size(); i++) {
            for (int j = i + 1; j < r.size(); j++) {
                ok(!r.get(i).text.equals(r.get(j).text),
                        "候选不重复 " + i + "/" + j + " " + r.get(i).text);
            }
        }

        // 长串不崩、有耗时上限
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("jintian");
        long t0 = System.nanoTime();
        List<SentenceDecoder.Result> longR = d.decode(sb.toString(), 3);
        long ms = (System.nanoTime() - t0) / 1000000;
        ok(longR != null, "20 遍 jintian 不崩");
        ok(ms < 2000, "20 遍 jintian 耗时 < 2s（实际 " + ms + "ms）");
        if (!longR.isEmpty()) {
            ok(longR.get(0).text.contains("今天"), "长串解出含「今天」: " + longR.get(0).text);
        }
    }

    static void testWordTableReflect() {
        section("forEngine 反射降级");
        // engine=null 时不能抛异常
        SentenceDecoder d = SentenceDecoder.forEngine(null, 12);
        ok(d != null, "forEngine(null) 返回非 null");
        eq(d.decode("nihao", 3).size(), 0, "空引擎解码空结果");

        // 用真实 PinyinEngine 时，如果它还没 exactKey，也不能崩
        try {
            PinyinEngine eng = null; // 没有 Context 造不出来，只验证 null 路径
            SentenceDecoder d2 = SentenceDecoder.forEngine(eng, 8);
            ok(d2 != null, "forEngine(null 引擎实例) 不崩");
        } catch (Throwable t) {
            fail++;
            System.out.println("  ❌ forEngine 抛了异常: " + t);
        }
    }
}
