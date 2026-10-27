package com.binf.floater;

/**
 * 版本号比较。纯字符串处理，**不 import 任何 android.***，
 * 所以能像 BinCore 一样直接在桌面 JVM 上跑单测。
 *
 * 为什么不用 versionCode 比：Release 的 tag 是 `v2.4` 这种形式，
 * 里面只有 versionName，没有 versionCode。两个都得能比。
 */
public final class VersionUtil {

    private VersionUtil() {
    }

    /**
     * `v2.4` / `2.4` / `release-2.4.1` → {2,4} / {2,4} / {2,4,1}。
     * 一个数字都抠不出来就返回 null（调用方按「无法判断」处理，不弹更新）。
     */
    public static int[] parse(String tag) {
        if (tag == null) return null;
        String s = tag.trim();
        if (s.isEmpty()) return null;
        // 跳过前缀（v / V / release- / 任意非数字开头）
        int i = 0;
        while (i < s.length() && !isDigit(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        // 从第一个数字起，只接受数字和点，遇到别的字符就停（`2.4-beta` → 2.4）
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (isDigit(c)) sb.append(c);
            else if (c == '.') sb.append('.');
            else break;
        }
        if (sb.length() == 0) return null;
        String[] parts = sb.toString().split("\\.");
        int[] out = new int[parts.length];
        for (int k = 0; k < parts.length; k++) {
            if (parts[k].isEmpty()) return null;
            try {
                out[k] = Integer.parseInt(parts[k]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /**
     * a 比 b 新返回 1，旧返回 -1，一样返回 0。
     * 任一侧解析失败也返回 0 —— 拿不准就当没更新，宁可不提示也不误报。
     * 缺的位按 0 补齐，所以 `2.4` 和 `2.4.0` 相等，`2.10` 比 `2.9` 新。
     */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) return 0;
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    /** a 是不是比 b 新。 */
    public static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
