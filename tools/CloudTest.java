package com.binf.ime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * CloudSuggest 纯 JVM 测试：解析 / 过滤 / 拼音校验 / 畸形输入 / 线程与序号。
 *
 * <p>全部走注入的假 Transport + 手工 Executor，**默认不联网**；
 * 加 {@code --online} 参数才真的连 360 接口（用于人工验证线上返回）。
 *
 * <p>编译 &amp; 运行（**不需要 android.jar**，也不需要 build/stub/ —— CloudSuggest 里对
 * android.os.Handler 的引用是反射的，纯 JVM 上自动退化成当前线程执行）：
 * <pre>
 * javac -encoding UTF-8 --release 17 \
 *   -d build/r_cloud app/src/com/binf/ime/CloudSuggest.java tools/CloudTest.java
 * java -Dfile.encoding=UTF-8 -cp build/r_cloud com.binf.ime.CloudTest
 * </pre>
 */
public class CloudTest {

    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + label + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + label + "  " + detail);
        }
    }

    static void eq(String label, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        check(label, ok, "期望 " + expect + "，实际 " + actual);
    }

    /** 360 实测返回样本（原样，勿改）。 */
    static final String SAMPLE_360 =
            "{\"errorcode\":0,\"ext\":\"nlpv=test_403\",\"query\":\"woxiangchifan\","
            + "\"result\":[{\"face\":\"\",\"word\":\"我想吃饭\"},"
            + "{\"face\":\"\",\"word\":\"我想吃饭用英语怎么说\"}]}";

    static final String SAMPLE_BAIDU =
            "{\"q\":\"nihao\",\"g\":[{\"type\":\"sug\",\"q\":\"你好\"},"
            + "{\"type\":\"sug\",\"q\":\"你号\"}]}";

    public static void main(String[] args) throws Exception {
        boolean online = args.length > 0 && args[0].equals("--online");

        System.out.println("=== 1. 解析真实 360 样本 ===");
        List<String> r1 = CloudSuggest.parse(SAMPLE_360);
        System.out.println("       " + r1);
        check("解析出「我想吃饭」", r1.contains("我想吃饭"), r1.toString());
        check("长句被过滤掉", !r1.contains("我想吃饭用英语怎么说"), r1.toString());
        check("首选是「我想吃饭」", !r1.isEmpty() && r1.get(0).equals("我想吃饭"), r1.toString());

        System.out.println("=== 2. extract 原始抓取（不做业务过滤） ===");
        List<String> raw = CloudSuggest.extract(SAMPLE_360, "word");
        eq("抓到 2 个 word", 2, raw.size());
        eq("第 1 个是长句原文", "我想吃饭用英语怎么说", raw.get(1));

        System.out.println("=== 3. JSON 转义与畸形输入 ===");
        eq("\\u4f60\\u597d 反转义", Arrays.asList("你好"),
                CloudSuggest.extract("{\"word\":\"\\u4f60\\u597d\"}", "word"));
        eq("含 \\\" 转义不炸", Arrays.asList("a\"b"),
                CloudSuggest.extract("{\"word\":\"a\\\"b\"}", "word"));
        eq("冒号前后空白", Arrays.asList("中国"),
                CloudSuggest.extract("{ \"word\" : \"中国\" }", "word"));
        eq("半截 JSON 不炸", 0, CloudSuggest.parse("{\"result\":[{\"word\":\"半").size());
        eq("半截转义不炸", 0, CloudSuggest.extract("{\"word\":\"abc\\", "word").size());
        eq("null 输入", 0, CloudSuggest.parse(null).size());
        eq("空串输入", 0, CloudSuggest.parse("").size());
        eq("空 result", 0, CloudSuggest.parse("{\"errorcode\":0,\"result\":[]}").size());
        eq("垃圾串", 0, CloudSuggest.parse("}{[][]<html>500</html>").size());
        eq("word 是数字则跳过", 0,
                CloudSuggest.parse("{\"result\":[{\"word\":123}]}").size());
        eq("word 是 null 则跳过", 0,
                CloudSuggest.parse("{\"result\":[{\"word\":null}]}").size());
        eq("key 为 null 不炸", 0, CloudSuggest.extract(SAMPLE_360, null).size());
        eq("自己就写坏的 unicode 转义", Arrays.asList("好"),
                CloudSuggest.extract("{\"word\":\"\\uZZZZ好\"}", "word"));
        check("超长垃圾串不抛", CloudSuggest.parse(repeat("{\"word\":\"x\"}", 5000)).isEmpty(),
                "长度 " + repeat("{\"word\":\"x\"}", 5000).length());

        System.out.println("=== 4. 百度 fallback 解析 ===");
        List<String> rb = CloudSuggest.parseBaidu(SAMPLE_BAIDU);
        System.out.println("       " + rb);
        eq("百度 word 走同一套过滤", Arrays.asList("你好", "你号"), rb);
        check("百度的 q 字段含拼音也被滤掉", !rb.contains("nihao"), rb.toString());

        System.out.println("=== 5. isPlainHanzi ===");
        check("你好", CloudSuggest.isPlainHanzi("你好", 2, 8), "");
        check("我想吃饭", CloudSuggest.isPlainHanzi("我想吃饭", 2, 8), "");
        check("单个汉字低于下限", !CloudSuggest.isPlainHanzi("我", 2, 8), "");
        check("10 字长句超上限", !CloudSuggest.isPlainHanzi("我想吃饭用英语怎么说", 2, 8), "");
        check("英文", !CloudSuggest.isPlainHanzi("hello", 2, 8), "");
        check("中英混", !CloudSuggest.isPlainHanzi("你好abc", 2, 8), "");
        check("标点", !CloudSuggest.isPlainHanzi("你好！", 2, 8), "");
        check("空格", !CloudSuggest.isPlainHanzi("你好 ", 2, 8), "");
        check("数字", !CloudSuggest.isPlainHanzi("你好123", 2, 8), "");
        check("日文假名", !CloudSuggest.isPlainHanzi("あなた", 2, 8), "");
        check("null", !CloudSuggest.isPlainHanzi(null, 2, 8), "");
        check("min>max 不可能成立", !CloudSuggest.isPlainHanzi("你好", 5, 2), "");
        check("8 字刚好上限", CloudSuggest.isPlainHanzi("一二三四五六七八", 2, 8), "");
        check("9 字超上限", !CloudSuggest.isPlainHanzi("一二三四五六七八九", 2, 8), "");

        System.out.println("=== 6. 拼音合法性 ===");
        check("nihao", CloudSuggest.isPinyin("nihao"), "");
        check("NIHAO 也合法", CloudSuggest.isPinyin("NIHAO"), "");
        eq("NIHAO 归一化成小写", "nihao", CloudSuggest.normalizePinyin("NIHAO"));
        check("ni hao 非法", !CloudSuggest.isPinyin("ni hao"), "");
        check("nihao1 非法", !CloudSuggest.isPinyin("nihao1"), "");
        check("空串非法", !CloudSuggest.isPinyin(""), "");
        check("null 非法", !CloudSuggest.isPinyin(null), "");
        check("带声调非法", !CloudSuggest.isPinyin("nǐhǎo"), "");
        check("中文非法", !CloudSuggest.isPinyin("你好"), "");
        check("24 位合法", CloudSuggest.isPinyin(repeat("a", 24)), "");
        check("25 位非法", !CloudSuggest.isPinyin(repeat("a", 25)), "");
        eq("非法拼音归一化为 null", null, CloudSuggest.normalizePinyin("ni-hao"));

        System.out.println("=== 7. 拼音长度对齐 ===");
        check("我想吃饭/woxiangchifan", CloudSuggest.aligns("我想吃饭", "woxiangchifan"), "");
        check("今天天气/jintiantianqi", CloudSuggest.aligns("今天天气", "jintiantianqi"), "");
        check("7 字 > 5 字母", !CloudSuggest.aligns("今天天气不错啊", "nihao"), "");
        check("1 字母撑不起 2 字", !CloudSuggest.aligns("你好", "n"), "");
        check("null 词", !CloudSuggest.aligns(null, "nihao"), "");
        check("null 拼音", !CloudSuggest.aligns("你好", null), "");

        System.out.println("=== 8. filter 去重 / 上限 / 对齐 ===");
        List<String> dup = Arrays.asList("我想吃饭", "我想吃饭", "我想吃饭用英语怎么说", "我想吃面");
        eq("去重 + 过滤长句", Arrays.asList("我想吃饭", "我想吃面"),
                CloudSuggest.filter(dup, "woxiangchifan"));
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 30; i++) {
            many.add("中国" + (char) ('一' + i)); // 3 个字，不重复
        }
        eq("最多 8 条", 8, CloudSuggest.filter(many, "zhongguo").size());
        eq("raw 为 null", 0, CloudSuggest.filter(null, "nihao").size());
        eq("raw 为空", 0, CloudSuggest.filter(new ArrayList<String>(), "nihao").size());
        List<String> mixed = Arrays.asList(null, "", "  ", "你好", "HELLO", "世界");
        eq("混合垃圾只留纯中文", Arrays.asList("你好", "世界"),
                CloudSuggest.filter(mixed, null));
        check("pinyin=null 时不做长度对齐", !CloudSuggest.filter(
                Arrays.asList("今天天气不错啊"), null).isEmpty(), "7 字在 2~8 内应保留");
        eq("不对齐长句仍过不了长度关", 0,
                CloudSuggest.filter(Arrays.asList("今天天气不错啊今天"), null).size());

        System.out.println("=== 9. 请求流程（注入假网络，不联网） ===");
        FakeTransport ok = new FakeTransport(SAMPLE_360);
        DirectExecutor direct = new DirectExecutor();
        CloudSuggest cs = new CloudSuggest(direct, direct, ok);
        Recorder rec = new Recorder();
        cs.request("woxiangchifan", rec);
        eq("回调 1 次", 1, rec.count);
        eq("回调带回原始拼音", "woxiangchifan", rec.pinyin);
        eq("回调结果已过滤", Arrays.asList("我想吃饭"), rec.words);
        eq("只发了 1 次网络", 1, ok.calls);
        check("请求 URL 是 360 接口", ok.lastUrl.startsWith("https://sug.so.360.cn/suggest?word=woxiangchifan"),
                ok.lastUrl);
        check("URL 带 utf-8 参数", ok.lastUrl.contains("encodein=utf-8"), ok.lastUrl);

        System.out.println("=== 10. 非法 / 大写拼音 ===");
        FakeTransport t2 = new FakeTransport(SAMPLE_360);
        CloudSuggest cs2 = new CloudSuggest(direct, direct, t2);
        Recorder r2 = new Recorder();
        cs2.request("ni hao", r2);
        eq("非法拼音不发网络", 0, t2.calls);
        eq("非法拼音同步回空", 0, r2.words.size());
        eq("非法拼音仍回调", 1, r2.count);
        eq("非法拼音回原串", "ni hao", r2.pinyin);

        FakeTransport t3 = new FakeTransport(SAMPLE_360);
        CloudSuggest cs3 = new CloudSuggest(direct, direct, t3);
        Recorder r3 = new Recorder();
        cs3.request("Woxiangchifan", r3);
        check("大写拼音发的是小写 URL", t3.lastUrl.contains("word=woxiangchifan"), t3.lastUrl);

        Recorder r4 = new Recorder();
        cs3.request(null, r4);
        eq("null 拼音不发网络", 1, t3.calls); // 仍是上一次的 1 次
        eq("null 拼音回空", 0, r4.words.size());

        System.out.println("=== 11. 异常全部吞掉 ===");
        FakeTransport boom = new FakeTransport(null);
        boom.throwIt = true;
        CloudSuggest cs4 = new CloudSuggest(direct, direct, boom);
        Recorder r5 = new Recorder();
        cs4.request("nihao", r5);
        eq("网络抛异常仍回调", 1, r5.count);
        eq("异常回空列表", 0, r5.words.size());

        CloudSuggest cs5 = new CloudSuggest(direct, direct, new FakeTransport(""));
        Recorder r6 = new Recorder();
        cs5.request("nihao", r6);
        eq("空响应回空列表", 0, r6.words.size());

        CloudSuggest cs6 = new CloudSuggest(direct, direct, new FakeTransport("{{\"word\":\"半截"));
        Recorder r7 = new Recorder();
        cs6.request("nihao", r7);
        eq("畸形响应回空列表", 0, r7.words.size());

        Recorder rThrow = new Recorder();
        rThrow.throwInCallback = true;
        cs5.request("nihao", rThrow);
        eq("回调自己抛异常不外泄", 1, rThrow.count);

        cs5.request("nihao", null);
        check("回调为 null 不崩", true, "");

        System.out.println("=== 12. 请求序号：旧结果必须丢弃 ===");
        DeferredExecutor net = new DeferredExecutor();
        DirectExecutor mainX = new DirectExecutor();
        CloudSuggest cs7 = new CloudSuggest(net, mainX, new FakeTransport(SAMPLE_360));
        Recorder r8 = new Recorder();
        cs7.request("woxiangchifan", r8);
        cs7.request("jintiantianqi", r8);
        eq("两个任务都排进后台队列", 2, net.size());
        net.runNext();          // 执行旧请求
        eq("旧请求结果被丢弃", 0, r8.count);
        net.runNext();          // 执行新请求
        eq("新请求正常回调", 1, r8.count);
        eq("回调的是新拼音", "jintiantianqi", r8.pinyin);

        System.out.println("=== 13. cancel / 开关 ===");
        DeferredExecutor net2 = new DeferredExecutor();
        CloudSuggest cs8 = new CloudSuggest(net2, mainX, new FakeTransport(SAMPLE_360));
        Recorder r9 = new Recorder();
        cs8.request("nihao", r9);
        cs8.cancel();
        net2.runNext();
        eq("cancel 后不回调", 0, r9.count);

        FakeTransport t10 = new FakeTransport(SAMPLE_360);
        CloudSuggest cs10 = new CloudSuggest(direct, direct, t10);
        Recorder r10 = new Recorder();
        cs10.setEnabled(false);
        check("开关状态", !cs10.isEnabled(), "");
        cs10.request("woxiangchifan", r10);
        eq("开关关闭不发网络", 0, t10.calls);
        eq("开关关闭同步回空", 0, r10.words.size());
        cs10.setEnabled(true);
        cs10.request("woxiangchifan", r10);
        eq("开关打开后能取到结果", Arrays.asList("我想吃饭"), r10.words);

        System.out.println("=== 14. 360 空结果 → 百度兜底 ===");
        FakeTransport fallback = new FakeTransport(null);
        fallback.byUrl = new String[][] {
                {CloudSuggest.URL_360, "{\"errorcode\":0,\"result\":[]}"},
                {CloudSuggest.URL_BAIDU, SAMPLE_BAIDU}
        };
        CloudSuggest cs11 = new CloudSuggest(direct, direct, fallback);
        Recorder r11 = new Recorder();
        cs11.request("nihao", r11);
        eq("360 空则用百度结果", Arrays.asList("你好", "你号"), r11.words);
        check("两次请求都发了", fallback.urls.size() == 2, fallback.urls.toString());

        fallback.reset();
        Recorder r11b = new Recorder();
        cs11.request("woxiangchifan", r11b);
        eq("两次都空则回空", 0, r11b.words.size());

        System.out.println("=== 15. 常量约束 ===");
        eq("连接超时 1200ms", 1200, CloudSuggest.CONNECT_TIMEOUT_MS);
        eq("读超时 1500ms", 1500, CloudSuggest.READ_TIMEOUT_MS);
        eq("最多 8 条", 8, CloudSuggest.MAX_RESULTS);
        anyOrder();
        check("响应体上限存在", CloudSuggest.MAX_BODY_BYTES > 0,
                String.valueOf(CloudSuggest.MAX_BODY_BYTES));

        System.out.println("=== 16. httpGet 对不可达地址抛 IOException（调用方已 catch） ===");
        boolean threw = false;
        try {
            CloudSuggest.httpGet("http://127.0.0.1:1/nothing", 500, 500);
        } catch (Throwable t) {
            threw = true;
        }
        check("不可达地址抛异常而非静默返回", threw, "");

        if (online) {
            System.out.println();
            System.out.println("══════════ 真实联网测试 ══════════");
            for (String w : new String[] {"woxiangchifan", "jintiantianqi", "nihao"}) {
                String url = CloudSuggest.url360(w);
                String body = null;
                long t0 = System.currentTimeMillis();
                try {
                    body = CloudSuggest.httpGet(url, 4000, 5000);
                } catch (Throwable t) {
                    System.out.println("  " + w + " -> HTTP 失败: " + t);
                    continue;
                }
                long ms = System.currentTimeMillis() - t0;
                List<String> raw360 = CloudSuggest.extract(body, "word");
                List<String> filtered = CloudSuggest.filter(raw360, w);
                System.out.println("  ── " + w + " (" + ms + "ms, " + body.length() + "B) ──");
                System.out.println("     原始 JSON: " + clip(body, 500));
                System.out.println("     word 原文(" + raw360.size() + "): " + raw360);
                System.out.println("     过滤后(" + filtered.size() + "): " + filtered);
                check("联网 " + w + " 有结果", !filtered.isEmpty(), filtered.toString());
                System.out.println();
            }
        } else {
            System.out.println("（跳过真实联网；加 --online 参数可实测 360 接口）");
        }

        System.out.println();
        System.out.println("结果：通过 " + pass + "，失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 防止某些常量被优化掉的占位（同时断言过滤上下限符合规格）。 */
    static void anyOrder() {
        eq("最短 2 个汉字", 2, CloudSuggest.MIN_WORD_LEN);
        eq("最长 8 个汉字", 8, CloudSuggest.MAX_WORD_LEN);
    }

    static String clip(String s, int n) {
        if (s == null) {
            return "null";
        }
        String one = s.replace("\n", " ").replace("\r", " ");
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    // ────────────────────────────── 测试替身 ──────────────────────────────

    /** 记录回调的假 Callback。 */
    static class Recorder implements CloudSuggest.Callback {
        int count;
        String pinyin;
        List<String> words = new ArrayList<String>();
        boolean throwInCallback;

        @Override public void onResult(String pinyin, List<String> words) {
            count++;
            this.pinyin = pinyin;
            this.words = words == null ? new ArrayList<String>() : new ArrayList<String>(words);
            if (throwInCallback) {
                throw new RuntimeException("回调故意炸");
            }
        }
    }

    /** 直接在当前线程执行。 */
    static class DirectExecutor implements Executor {
        @Override public void execute(Runnable r) {
            r.run();
        }
    }

    /** 攒着不执行，由测试手动 runNext()，用来模拟慢网络。 */
    static class DeferredExecutor implements Executor {
        private final Deque<Runnable> q = new ArrayDeque<Runnable>();

        @Override public void execute(Runnable r) {
            q.add(r);
        }

        int size() {
            return q.size();
        }

        void runNext() {
            Runnable r = q.poll();
            if (r != null) {
                r.run();
            }
        }
    }

    /** 假网络：返回固定 JSON，可配置抛异常 / 按 URL 分别返回。 */
    static class FakeTransport implements CloudSuggest.Transport {
        int calls;
        String lastUrl;
        String body;
        boolean throwIt;
        String[][] byUrl;
        final List<String> urls = new ArrayList<String>();

        FakeTransport(String body) {
            this.body = body;
        }

        void reset() {
            calls = 0;
            lastUrl = null;
            urls.clear();
        }

        @Override public String get(String url) throws Exception {
            calls++;
            lastUrl = url;
            urls.add(url);
            if (throwIt) {
                throw new java.net.SocketTimeoutException("假超时");
            }
            if (byUrl != null) {
                for (String[] pair : byUrl) {
                    if (url.startsWith(pair[0])) {
                        return pair[1];
                    }
                }
                return "{\"errorcode\":0,\"result\":[]}";
            }
            return body;
        }
    }
}
