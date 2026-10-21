import com.binf.ime.PinyinEngine;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/**
 * 拼音引擎离线测试：直接用 GZIP 读词典，不依赖 Android。
 *
 * 用引擎自己的 loadFrom（package-private）加载，保证**测的就是线上那条路径**，
 * 不再手工复制解析代码（那样两边会漂移，v1 时代就吃过这个亏）。
 */
public class PinyinTest {

    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  FAIL  " + label + "  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File f = new File(args.length > 0 ? args[0] : "app/assets/pinyin.dict.gz");
        if (!f.exists()) {
            System.out.println("找不到词典: " + f.getAbsolutePath());
            System.exit(1);
        }
        System.out.println("词典: " + f.getAbsolutePath()
                + "  (" + (f.length() / 1048576.0) + " MB)");

        long t0 = System.nanoTime();
        PinyinEngine eng = PinyinEngine.loadForTest(new FileInputStream(f));
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("加载耗时 " + loadMs + "ms，引擎 size = " + eng.size()
                + "，音节数 = " + eng.syllableCount()
                + "，最长词 = " + eng.maxWordChars());

        System.out.println("=== 0. 词典头 ===");
        check("条目数 > 500000", eng.size() > 500000, "实际 " + eng.size());
        check("音节数 > 400", eng.syllableCount() > 400, "实际 " + eng.syllableCount());
        check("加载耗时 < 3000ms", loadMs < 3000, "实际 " + loadMs + "ms");
        check("maxWordChars >= 2", eng.maxWordChars() >= 2, "实际 " + eng.maxWordChars());

        System.out.println("=== 1. 常用单字/词（严格 key） ===");
        check("ni -> 你", contains(eng.exactKey("ni", 5), "你"), "候选: " + names(eng.exactKey("ni", 5)));
        check("wo -> 我", contains(eng.exactKey("wo", 5), "我"), "候选: " + names(eng.exactKey("wo", 5)));
        check("de -> 的", contains(eng.exactKey("de", 5), "的"), "候选: " + names(eng.exactKey("de", 5)));
        check("shi -> 是", contains(eng.exactKey("shi", 5), "是"), "候选: " + names(eng.exactKey("shi", 5)));
        check("zhongguo -> 中国", contains(eng.exactKey("zhongguo", 5), "中国"),
                "候选: " + names(eng.exactKey("zhongguo", 5)));
        check("nihao -> 你好", contains(eng.exactKey("nihao", 5), "你好"),
                "候选: " + names(eng.exactKey("nihao", 5)));
        check("beijing -> 北京", contains(eng.exactKey("beijing", 5), "北京"),
                "候选: " + names(eng.exactKey("beijing", 5)));

        System.out.println("=== 2. exactKey 必须是严格匹配 ===");
        // 这是整句解码的命脉：查 shi 绝不能把 shijian 带出来
        List<PinyinEngine.Candidate> shi = eng.exactKey("shi", 20);
        boolean allShi = true;
        for (PinyinEngine.Candidate c : shi) { }
        check("exactKey(shi) 非空", !shi.isEmpty(), "数量 " + shi.size());
        check("exactKey 不混入前缀词", eng.exactKey("shi", 3).size() <= 3, "");
        // shijian 必须只在 exactKey("shijian") 里出现
        boolean shiHasShijian = false;
        for (PinyinEngine.Candidate c : eng.exactKey("shi", 200)) {
            if ("时间".equals(c.text)) { shiHasShijian = true; break; }
        }
        check("exactKey(shi) 不含「时间」", !shiHasShijian, "");
        check("exactKey(shijian) 含「时间」", contains(eng.exactKey("shijian", 5), "时间"),
                "候选: " + names(eng.exactKey("shijian", 5)));

        System.out.println("=== 3. 前缀补全 ===");
        List<PinyinEngine.Candidate> c2 = eng.query("zhong", 10);
        check("zhong 有候选", !c2.isEmpty(), "数量 " + c2.size());
        System.out.println("       zhong -> " + names(c2));

        List<PinyinEngine.Candidate> c3 = eng.query("zhongg", 8);
        check("zhongg 前缀能出中国相关", !c3.isEmpty(), names(c3));

        // 前缀截断回归：短前缀会横跨几千个 key（"y" 匹配 4.7 万条），
        // 早期版本按字典序扫前 512 条，结果「一」「有」「也」全被漏掉，
        // 出来的却是「呀」「雅虎」「牙齿」。这里直接卡首选。
        List<PinyinEngine.Candidate> cy = eng.query("y", 10);
        check("短前缀 y 首选高频字", !cy.isEmpty() && "一".equals(cy.get(0).text), names(cy));
        check("短前缀 y 含「有」", contains(cy, "有"), names(cy));
        check("短前缀 y 含「也」", contains(cy, "也"), names(cy));

        List<PinyinEngine.Candidate> czh = eng.query("zh", 10);
        check("短前缀 zh 首选「制度」", !czh.isEmpty() && "制度".equals(czh.get(0).text), names(czh));
        check("短前缀 zh 含「中」", contains(czh, "中"), names(czh));

        // 同字多音去重：「这」同时挂在 zhe 和 zhei 下，不该显示两遍
        List<PinyinEngine.Candidate> czhe = eng.query("zh", 40);
        int dupCount = 0;
        for (int i = 0; i < czhe.size(); i++) {
            for (int j = i + 1; j < czhe.size(); j++) {
                if (czhe.get(i).text.equals(czhe.get(j).text)) dupCount++;
            }
        }
        check("候选无重复字", dupCount == 0, "重复 " + dupCount + " 处");

        System.out.println("=== 4. 候选数与排序 ===");
        List<PinyinEngine.Candidate> c4 = eng.query("shi", 20);
        check("shi 有至少 10 个候选", c4.size() >= 10, "实际 " + c4.size());
        boolean sorted = true;
        for (int i = 1; i < c4.size(); i++) {
            if (c4.get(i - 1).weight < c4.get(i).weight) { sorted = false; break; }
        }
        check("候选按权重降序", sorted, "");

        System.out.println("=== 5. 音节表（九键要用） ===");
        String[] syl = eng.syllables();
        check("syllables 长度与 syllableCount 一致", syl.length == eng.syllableCount(),
                syl.length + " vs " + eng.syllableCount());
        check("含 ni", eng.hasSyllable("ni"), "");
        check("含 hao", eng.hasSyllable("hao"), "");
        check("含 zhong", eng.hasSyllable("zhong"), "");
        check("含 guo", eng.hasSyllable("guo"), "");
        check("含 xian", eng.hasSyllable("xian"), "");
        check("含 lv（ü 写法）", eng.hasSyllable("lv"), "");
        check("不含 zzz", !eng.hasSyllable("zzz"), "");

        System.out.println("=== 6. hasKey ===");
        check("hasKey(xian)", eng.hasKey("xian"), "");
        check("hasKey(zzz)", !eng.hasKey("zzz"), "");
        check("hasKey(null) 不崩", !eng.hasKey(null), "");
        check("hasKey(\"\") 不崩", !eng.hasKey(""), "");

        System.out.println("=== 7. 边界 ===");
        check("空串返回空", eng.query("", 10).isEmpty(), "");
        check("null 返回空", eng.query(null, 10).isEmpty(), "");
        check("不存在的拼音返回空", eng.query("zzzzzz", 10).isEmpty(), "");
        check("v 开头不崩", eng.query("v", 5) != null, "");
        check("exactKey 空串返回空", eng.exactKey("", 10).isEmpty(), "");
        check("limit<=0 返回空", eng.query("ni", 0).isEmpty(), "");

        System.out.println("=== 8. 单字覆盖（整句解码兜底用） ===");
        int single = 0;
        String[] common = {"的", "一", "是", "不", "我", "你", "他", "在", "有", "人"};
        String[] commonPy = {"de", "yi", "shi", "bu", "wo", "ni", "ta", "zai", "you", "ren"};
        for (int i = 0; i < common.length; i++) {
            boolean ok = contains(eng.exactKey(commonPy[i], 40), common[i]);
            if (ok) single++;
        }
        check("10 个高频字全部可查", single == 10, "命中 " + single + "/10");

        System.out.println("=== 9. 性能 ===");
        long t1 = System.nanoTime();
        for (int i = 0; i < 200; i++) eng.query("zhongguo", 20);
        long ms = (System.nanoTime() - t1) / 1_000_000;
        check("200 次 query < 500ms", ms < 500, "实际 " + ms + "ms");
        System.out.println("       平均 " + (ms / 200.0) + " ms/次");

        long t2 = System.nanoTime();
        for (int i = 0; i < 200; i++) eng.exactKey("zhongguo", 20);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;
        check("200 次 exactKey < 500ms", ms2 < 500, "实际 " + ms2 + "ms");

        System.out.println();
        System.out.println("结果：通过 " + pass + "，失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static boolean contains(List<PinyinEngine.Candidate> list, String text) {
        for (PinyinEngine.Candidate c : list) if (text.equals(c.text)) return true;
        return false;
    }

    static String names(List<PinyinEngine.Candidate> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).text);
        }
        return sb.append("]").toString();
    }
}
