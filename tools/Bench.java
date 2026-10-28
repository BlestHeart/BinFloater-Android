package com.binf.floater;

import java.util.Locale;

/**
 * 性能基准：在主线程最热的几条路径上量耗时。
 *
 * 不是单元测试，不参与 `run_tests.sh` 的断言计数 —— 它只回答「改完到底快了没有」。
 * 用法（项目根目录）：
 *   javac -encoding UTF-8 -d build/r_bench app/src/com/binf/floater/BinCore.java tools/Bench.java
 *   java -Dfile.encoding=UTF-8 -cp build/r_bench Bench
 */
public final class Bench {

    private static final int REPS = 200;

    public static void main(String[] args) {
        StringBuilder cn = new StringBuilder();
        for (int i = 0; i < 400; i++) cn.append("二进制悬浮窗测试文本");
        String text = cn.toString();                       // 4000 字符 / 12000 字节
        String bits = BinCore.textToBinary(text, true);     // 96000 位（超过上限，只测函数本身）
        String bits20k = bits.substring(0, 20000);
        String grouped = BinCore.groupBits(bits20k, 8, " ");
        String b64 = BinCore.textToBase64(text);

        System.out.println("输入规模：文本 " + text.length() + " 字符 / "
                + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " 字节，位串 "
                + bits20k.length() + " 位，Base64 " + b64.length() + " 字符");
        System.out.println();

        row("textToBinary(4000字, 连写)", REPS, () -> BinCore.textToBinary(text, true));
        row("binaryToText(20000位)", REPS / 4, () -> BinCore.binaryToText(bits20k));
        row("renderBits(20000位, hex)", REPS / 4, () -> BinCore.renderBits(bits20k, "hex"));
        row("renderBits(20000位, group8)", REPS, () -> BinCore.renderBits(bits20k, "group8"));
        row("cleanBits(20000位, 带空格)", REPS, () -> BinCore.cleanBits(grouped));
        row("autoDetect(位串)", REPS, () -> BinCore.autoDetect(bits20k));
        row("autoDetect(Base64)", REPS, () -> BinCore.autoDetect(b64));
        row("autoDetect(中文正文)", REPS, () -> BinCore.autoDetect(text));
        row("base64ToText(16000字符)", REPS / 4, () -> BinCore.base64ToText(b64));
        row("describe(4000字)", REPS, () -> BinCore.describe(text));
    }

    private interface Job { Object run(); }

    private static void row(String name, int reps, Job job) {
        for (int i = 0; i < Math.max(20, reps / 2); i++) job.run();   // 预热
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) job.run();
        long dt = System.nanoTime() - t0;
        double ms = dt / 1e6 / reps;
        System.out.println(String.format(Locale.ROOT, "%-28s %8.3f ms/次  (x%d)", name, ms, reps));
    }
}
