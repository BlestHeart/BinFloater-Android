package com.binf.ime;

/** 音节切分测试：不依赖 Android 的部分。 */
public class SegmentTest {
    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + label + "  " + detail); }
        else { fail++; System.out.println("  FAIL  " + label + "  " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 音节合法性 ===");
        String[] valid = {"ni", "hao", "zhong", "guo", "shi", "de", "a", "o", "e",
                          "zh", "ch", "sh", "ang", "er", "xian", "lian", "nv"};
        for (String s : valid) {
            check("合法音节 " + s, PinyinComposer.isSyllable(s), "");
        }
        String[] invalid = {"zz", "xx", "qq", "", "abc", "123", "ni3"};
        for (String s : invalid) {
            check("非法音节 " + s, !PinyinComposer.isSyllable(s), "");
        }

        System.out.println("=== 切分 ===");
        seg("nihao", 2);
        seg("zhongguo", 2);
        seg("beijing", 2);
        seg("woaini", 3);
        seg("xian", 1);      // xian 本身是合法音节，不该切成 xi+an
        seg("pengyou", 2);
    }

    static void seg(String s, int expectParts) {
        java.util.List<String> parts = PinyinComposer.segment(s);
        boolean ok = parts.size() == expectParts;
        check("切分 " + s + " -> " + parts + " (" + expectParts + " 段)", ok,
              parts.toString());
    }
}
