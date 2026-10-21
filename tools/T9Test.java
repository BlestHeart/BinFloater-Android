package com.binf.ime;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/**
 * 九键（T9）端到端测试：用**真实词库** + T9Engine，验证典型输入路径。
 *
 * 与 DecodeTest 的区别：DecodeTest 用假引擎测算法骨架，
 * 这里用 54 万条真词库测「用户实际按 9 个键会出什么」。
 *
 * 跑法：cd build/r_t9 && java -cp . T9Test pinyin.dict.gz
 */
public final class T9Test {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  FAIL  " + label + "  " + detail); }
    }

    /** 取某个数字键上的字母串（KEY_LETTERS[0] 对应数字键 1，[1] 对应 2 …）。 */
    private static String keyLetters(char digit) {
        if (digit < '1' || digit > '9') return "";
        return T9Engine.KEY_LETTERS[digit - '1'];
    }

    public static void main(String[] args) throws Exception {
        File dict = new File(args.length > 0 ? args[0] : "pinyin.dict.gz");
        if (!dict.exists()) {
            System.out.println("找不到词典: " + dict.getAbsolutePath());
            System.exit(1);
        }
        PinyinEngine eng = PinyinEngine.loadForTest(new FileInputStream(dict));
        T9Engine t9 = T9Engine.getDefault();
        System.out.println("词典 " + eng.size() + " 条，音节 " + eng.syllableCount());

        System.out.println("=== 1. 数字→字母映射 ===");
        check("2 -> abc", "abc".equals(keyLetters('2')), "");
        check("7 -> pqrs", "pqrs".equals(keyLetters('7')), "");
        check("9 -> wxyz", "wxyz".equals(keyLetters('9')), "");
        check("1 -> 空", "".equals(keyLetters('1')), "");
        check("0 -> 空", "".equals(keyLetters('0')), "");
        check("数字键有 9 个", T9Engine.KEY_LETTERS.length == 9, "" + T9Engine.KEY_LETTERS.length);

        System.out.println("=== 2. 编码 ===");
        // nihao -> 6 4 4 2 6
        check("nihao -> 64426", "64426".equals(T9Engine.encode("nihao")),
                "实际 " + T9Engine.encode("nihao"));
        check("wo -> 96", "96".equals(T9Engine.encode("wo")), "实际 " + T9Engine.encode("wo"));
        check("zhongguo -> 94664486", "94664486".equals(T9Engine.encode("zhongguo")),
                "实际 " + T9Engine.encode("zhongguo"));
        check("空串 -> null 或空", T9Engine.encode("") == null || T9Engine.encode("").isEmpty(), "");
        check("null 不崩", T9Engine.encode(null) == null, "");

        System.out.println("=== 3. 数字串展开成拼音 ===");
        List<String> py = t9.pinyinList("64426", 8);
        check("64426 能展开出 nihao", py.contains("nihao"), "展开: " + py);
        List<String> py2 = t9.pinyinList("96", 20);
        check("96 能展开出 wo", py2.contains("wo"), "展开: " + py2);

        System.out.println("=== 4. 按 64426 出「你好」 ===");
        List<PinyinEngine.Candidate> cs = t9.candidates("64426", eng, 20);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cs.get(i).text);
        }
        sb.append("]");
        System.out.println("       64426 -> " + sb);
        boolean hasNihao = false;
        for (PinyinEngine.Candidate c : cs) if ("你好".equals(c.text)) { hasNihao = true; break; }
        check("64426 候选含「你好」", hasNihao, "");

        System.out.println("=== 5. 按 94664486 出「中国」 ===");
        List<PinyinEngine.Candidate> cs2 = t9.candidates("94664486", eng, 20);
        StringBuilder sb2 = new StringBuilder("[");
        for (int i = 0; i < cs2.size(); i++) {
            if (i > 0) sb2.append(", ");
            sb2.append(cs2.get(i).text);
        }
        sb2.append("]");
        System.out.println("       94664486 -> " + sb2);
        boolean hasZg = false;
        for (PinyinEngine.Candidate c : cs2) if ("中国".equals(c.text)) { hasZg = true; break; }
        check("94664486 候选含「中国」", hasZg, "");

        System.out.println("=== 6. 展示用拼音 ===");
        String disp = t9.displayPinyin("64426", eng);
        check("displayPinyin(64426) 非空且是拼音", disp != null && disp.matches("[a-z\\s]+"),
                "实际 " + disp);
        check("displayPinyin(1) 不崩", t9.displayPinyin("1", eng) != null, "");

        System.out.println("=== 7. 非法输入 ===");
        check("0 开头不崩", t9.candidates("0", eng, 5) != null, "");
        check("1 开头不崩", t9.candidates("1", eng, 5) != null, "");
        check("空串返回空", t9.candidates("", eng, 5).isEmpty(), "");
        check("null 返回空", t9.candidates(null, eng, 5).isEmpty(), "");
        check("超长不崩", t9.candidates("6442664426644266442664426", eng, 5) != null, "");

        System.out.println("=== 8. 性能 ===");
        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) t9.candidates("64426", eng, 20);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("50 次九键查词 < 2000ms", ms < 2000, "实际 " + ms + "ms（平均 " + (ms / 50.0) + "ms）");

        System.out.println();
        System.out.println("结果：通过 " + pass + "，失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
