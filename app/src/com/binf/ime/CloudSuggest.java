package com.binf.ime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * 云端补词：本地词库不够用时，拿拼音去搜索引擎的 suggest 接口要候选。
 *
 * <p>设计约束（见 README / 任务规格）：
 * <ul>
 *   <li>只用 Android 自带 API（{@link HttpURLConnection}），不引入任何第三方库，不用 JSON 库——
 *       解析是手写扫描器，见 {@link #extract(String, String)}。</li>
 *   <li>网络在后台线程，结果回主线程；同一实例重复请求时，靠请求序号丢弃过期结果。</li>
 *   <li>任何异常（无网 / DNS 失败 / 超时 / 解析炸 / 服务端 5xx）都只回调空列表，绝不外抛。</li>
 *   <li>解析 + 过滤全部是 {@code static} 方法，不依赖 Android，可在纯 JVM 上单测
 *       （见 tools/CloudTest.java）。</li>
 * </ul>
 *
 * <p>用法：全局建一个实例，输入拼音变化时调 {@link #request(String, Callback)}
 * （回调的 pinyin 可再和当前输入比对一次），离开输入法时调 {@link #cancel()}。
 */
public final class CloudSuggest {

    /** 360 拼音提示接口（实测首选词非常准）。 */
    static final String URL_360 = "https://sug.so.360.cn/suggest";
    /** 百度提示接口，360 没结果时兜底。 */
    static final String URL_BAIDU = "https://www.baidu.com/sugrec";

    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14)";
    static final int CONNECT_TIMEOUT_MS = 1200;
    static final int READ_TIMEOUT_MS = 1500;
    /** 响应体上限，防止服务端抽风返回超大内容把内存吃掉。 */
    static final int MAX_BODY_BYTES = 256 * 1024;
    /** 拼音串长度上限；同时要求全是 a-z。 */
    static final int MAX_PINYIN_LEN = 24;
    /** 候选词汉字数下限 / 上限。 */
    static final int MIN_WORD_LEN = 2;
    static final int MAX_WORD_LEN = 8;
    /** 单次最多返回多少条。 */
    static final int MAX_RESULTS = 8;

    /** 回调在**主线程**触发。pinyin 是请求时传入的原始拼音串，供调用方判断是否过期。 */
    public interface Callback {
        void onResult(String pinyin, List<String> words);
    }

    /** 纯函数式网络取数口子，测试可注入假实现，避免真的联网。 */
    interface Transport {
        String get(String url) throws Exception;
    }

    /** 后台网络线程：全进程共享一条，避免反复 new 实例把线程撑爆。 */
    private static volatile ExecutorService sharedNetExecutor;

    private final Executor netExecutor;
    private final Executor mainExecutor;
    private final Transport transport;

    /** 已发起过的请求数；回主线程前比对，不一致说明有更新的请求，本次结果作废。 */
    private final AtomicLong seq = new AtomicLong(0);

    /** 设置页的「云端补词」总开关，默认开。关掉后 request() 不发网络、同步回调空列表。 */
    private volatile boolean enabled = true;

    /** Android 环境下的默认构造：后台单线程 + 主线程 Handler。 */
    public CloudSuggest() {
        this(null, null, null);
    }

    /**
     * 测试用构造。
     *
     * @param netExec  后台执行器，null 表示用共享单线程池
     * @param mainExec 回调执行器，null 表示用 Android 主线程 Handler
     * @param t        网络取数实现，null 表示真的走 HttpURLConnection
     */
    CloudSuggest(Executor netExec, Executor mainExec, Transport t) {
        this.netExecutor = netExec != null ? netExec : sharedNet();
        this.mainExecutor = mainExec != null ? mainExec : mainExecutorCompat();
        this.transport = t != null ? t : new Transport() {
            @Override public String get(String url) throws Exception {
                return httpGet(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            }
        };
    }

    // ────────────────────────────── 对外 API ──────────────────────────────

    /** 设置页开关：关掉后不再联网，request() 直接同步回调空列表。 */
    public void setEnabled(boolean on) {
        this.enabled = on;
        if (!on) {
            cancel(); // 顺手把在途结果作废，避免关了还冒候选
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 异步请求云端候选。pinyin 不合法（不是 [a-zA-Z]{1,24}）时不会发请求，直接回调空列表。
     * 同一实例的旧请求结果会被丢弃（按请求序号）。
     * 开关关闭时**同步**回调空列表，不发网络请求。
     */
    public void request(final String pinyin, final Callback cb) {
        if (cb == null) {
            return;
        }
        final String raw = pinyin == null ? "" : pinyin;
        if (!enabled) {
            safeCallback(cb, raw, Collections.<String>emptyList());
            return;
        }
        final long mySeq = seq.incrementAndGet();
        final String py = normalizePinyin(pinyin);
        if (py == null) {
            deliver(mySeq, raw, Collections.<String>emptyList(), cb);
            return;
        }
        try {
            netExecutor.execute(new Runnable() {
                @Override public void run() {
                    List<String> words = Collections.emptyList();
                    try {
                        if (enabled) {
                            words = fetchAndParse(py);
                        }
                    } catch (Throwable ignored) {
                        // 无网 / 超时 / DNS 失败 / 解析异常：一律降级成空结果，绝不崩
                        words = Collections.emptyList();
                    }
                    deliver(mySeq, py, words, cb);
                }
            });
        } catch (Throwable tr) {
            // 线程池已关等极端情况
            deliver(mySeq, py, Collections.<String>emptyList(), cb);
        }
    }

    /** 取消所有在途请求：序号自增，之后到达的结果全部作废。 */
    public void cancel() {
        seq.incrementAndGet();
    }

    /** 释放资源（可选）。取消在途请求并关掉本实例独占的后台线程；共享线程池不受影响。 */
    public void shutdown() {
        cancel();
    }

    // ────────────────────────────── 网络部分 ──────────────────────────────

    /** 后台线程里跑：先 360，失败或全被过滤掉再百度兜底。 */
    private List<String> fetchAndParse(String py) {
        List<String> out = fetchOne(url360(py), py, true);
        if (out.isEmpty()) {
            List<String> alt = fetchOne(urlBaidu(py), py, false);
            if (!alt.isEmpty()) {
                out = alt;
            }
        }
        return out;
    }

    private List<String> fetchOne(String url, String pinyin, boolean is360) {
        try {
            String body = transport.get(url);
            if (body == null || body.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> raw = extract(body, is360 ? "word" : "q");
            return filter(raw, pinyin);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    static String url360(String pinyin) {
        return URL_360 + "?word=" + pinyin + "&encodein=utf-8&encodeout=utf-8";
    }

    static String urlBaidu(String pinyin) {
        return URL_BAIDU + "?prod=pc&wd=" + pinyin;
    }

    /** 同步 GET，返回 UTF-8 文本；非 200 / 空体抛 IOException。 */
    static String httpGet(String url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            // 主动只要 identity；万一服务端还是给了 gzip，下面也兜得住
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            boolean gzip = false;
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
                gzip = true;
            }
            byte[] raw = readAll(in, MAX_BODY_BYTES);
            // 有些 CDN 不给 Content-Encoding 却直接吐 gzip 字节，嗅探魔数 1f 8b 兜一下
            if (!gzip && raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
                gzip = true;
            }
            if (gzip) {
                try {
                    raw = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)),
                            MAX_BODY_BYTES);
                } catch (Throwable t) {
                    // 魔数撞车 / 截断的 gzip：当作普通文本继续往下走
                }
            }
            return new String(raw, StandardCharsets.UTF_8);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException("响应过大 > " + max + "B");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ─────────────────────── 纯函数：解析 / 过滤（可单测） ───────────────────────

    /**
     * 手写极简 JSON 扫描：把 {@code "word":"..."} 的值全部抠出来再过滤。
     * 不引入 JSON 库；能处理引号、反斜杠、斜杠、换行、Unicode 转义（反斜杠 + u + 4 位十六进制），
     * 遇到半截 JSON 就停下不抛异常。
     */
    public static List<String> parse(String json) {
        return filter(extract(json, "word"), null);
    }

    /** 百度 sugrec 的字段是 "q"，解析后走同一套过滤。 */
    public static List<String> parseBaidu(String json) {
        return filter(extract(json, "q"), null);
    }

    /**
     * 从 JSON 文本里抠出某个字符串键的全部取值（原始顺序，不做业务过滤）。
     *
     * @param key 例如 "word" / "q"
     * @return 永不为 null；输入 null / 空 / 畸形时返回可能为空的 list
     */
    public static List<String> extract(String json, String key) {
        List<String> out = new ArrayList<String>();
        if (json == null || json.isEmpty() || key == null || key.isEmpty()) {
            return out;
        }
        final int n = json.length();
        final String needle = "\"" + key + "\"";
        int i = 0;
        while (i < n) {
            int k = json.indexOf(needle, i);
            if (k < 0) {
                break;
            }
            i = k + needle.length();

            // 跳过空白，期待 ':'
            int j = skipSpace(json, i);
            if (j >= n || json.charAt(j) != ':') {
                continue;
            }
            j = skipSpace(json, j + 1);
            if (j >= n || json.charAt(j) != '"') {
                // 值不是字符串（数字 / null / 对象），跳过这个键
                continue;
            }
            j++;

            StringBuilder sb = new StringBuilder();
            boolean closed = false;
            while (j < n) {
                char c = json.charAt(j++);
                if (c == '\\') {
                    if (j >= n) {
                        break; // 半截转义
                    }
                    char e = json.charAt(j++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (j + 4 <= n) {
                                String hex = json.substring(j, j + 4);
                                j += 4;
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException ignored) {
                                    // 坏转义就丢掉，不抛
                                }
                            }
                            break;
                        default:
                            sb.append(e);
                            break;
                    }
                } else if (c == '"') {
                    closed = true;
                    break;
                } else {
                    sb.append(c);
                }
            }
            i = j;
            if (closed) {
                out.add(sb.toString());
            }
        }
        return out;
    }

    /**
     * 业务过滤：只留「2~8 个纯汉字」、可选地与拼音长度对齐、去重、最多 8 条。
     *
     * @param raw    {@link #extract(String, String)} 的原始结果
     * @param pinyin 用于长度对齐的拼音；null / 空表示不做对齐
     */
    public static List<String> filter(List<String> raw, String pinyin) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String py = normalizePinyin(pinyin);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String w : raw) {
            if (w == null) {
                continue;
            }
            String c = w.trim();
            if (!isPlainHanzi(c, MIN_WORD_LEN, MAX_WORD_LEN)) {
                continue;
            }
            if (py != null && !aligns(c, py)) {
                continue;
            }
            if (seen.add(c) && seen.size() >= MAX_RESULTS) {
                break;
            }
        }
        out.addAll(seen);
        return out;
    }

    /** 是否恰好是 min..max 个「纯汉字」：无标点、无英文、无数字、无空格。 */
    public static boolean isPlainHanzi(String s, int min, int max) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < min || n > max) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN) {
                return false;
            }
            // 限定在 CJK 基本区（U+4E00..U+9FFF），把扩展区生僻字/部首挡掉
            if (c < 0x4E00 || c > 0x9FFF) {
                return false;
            }
        }
        return true;
    }

    /**
     * 拼音串合法性：{@code [a-zA-Z]{1,24}}（先 toLowerCase 再校验）。
     * 空 / null / 带空格 / 带数字 / 超过 24 位都算非法。
     */
    public static boolean isPinyin(String s) {
        if (s == null) {
            return false;
        }
        int n = s.length();
        if (n < 1 || n > MAX_PINYIN_LEN) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                continue; // 校验前先统一小写，见 normalizePinyin
            }
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /** 合法则返回小写拼音串，否则 null。 */
    public static String normalizePinyin(String s) {
        if (!isPinyin(s)) {
            return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * 候选词汉字数要和拼音长度对得上。
     * 一个汉字的拼音是 1~6 个字母（a / zhuang），所以 len 个字母的拼音，
     * 对应汉字数必然落在 [ceil(len/6), len] 区间内。
     */
    public static boolean aligns(String word, String pinyin) {
        if (word == null || pinyin == null || pinyin.isEmpty()) {
            return false;
        }
        int hz = word.length();
        int letters = pinyin.length();
        int minHz = (letters + 5) / 6; // ceil(len/6)
        return hz >= minHz && hz <= letters;
    }

    private static int skipSpace(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            i++;
        }
        return i;
    }

    // ────────────────────────────── 线程 / 回调 ──────────────────────────────

    /** 同步安全回调（开关关闭 / 参数非法时用），回调里炸了也不外抛。 */
    private static void safeCallback(Callback cb, String pinyin, List<String> words) {
        try {
            cb.onResult(pinyin, words == null ? Collections.<String>emptyList() : words);
        } catch (Throwable ignored) {
            // 调用方回调里炸了也不能影响输入法
        }
    }

    /** 回主线程前再校验一次序号：期间有更新的请求就直接丢掉。 */
    private void deliver(final long mySeq, final String pinyin,
                         final List<String> words, final Callback cb) {
        if (mySeq != seq.get()) {
            return;
        }
        final List<String> safe = Collections.unmodifiableList(
                words == null ? new ArrayList<String>() : new ArrayList<String>(words));
        try {
            mainExecutor.execute(new Runnable() {
                @Override public void run() {
                    if (mySeq != seq.get()) {
                        return;
                    }
                    try {
                        cb.onResult(pinyin, safe);
                    } catch (Throwable ignored) {
                        // 调用方回调里炸了也不能影响输入法
                    }
                }
            });
        } catch (Throwable ignored) {
            // 主线程执行器挂了：宁可丢结果，不抛
        }
    }

    private static ExecutorService sharedNet() {
        ExecutorService e = sharedNetExecutor;
        if (e == null) {
            synchronized (CloudSuggest.class) {
                e = sharedNetExecutor;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "cloud-suggest");
                            t.setDaemon(true);
                            t.setPriority(Thread.MIN_PRIORITY + 1);
                            return t;
                        }
                    });
                    sharedNetExecutor = e;
                }
            }
        }
        return e;
    }

    /**
     * 主线程执行器：Android 上用 Handler(Looper.getMainLooper()).post()。
     *
     * <p>这里**故意用反射**而不是直接 {@code new android.os.Handler(...)}：
     * 直接引用会让纯 JVM 单测（tools/CloudTest.java）编译时必须带上 android.jar，
     * 而现有 run_tests.sh 的测试组都只依赖 build/stub/。反射版本在纯 JVM 上
     * 拿不到 android.os.Handler 就退化成「直接在当前线程执行」，测试照样跑得起来。
     */
    private static Executor mainExecutorCompat() {
        try {
            Class<?> looperCls = Class.forName("android.os.Looper");
            Object mainLooper = looperCls.getMethod("getMainLooper").invoke(null);
            if (mainLooper == null) {
                return DIRECT_EXECUTOR;
            }
            Class<?> handlerCls = Class.forName("android.os.Handler");
            final Object handler = handlerCls
                    .getConstructor(looperCls)
                    .newInstance(mainLooper);
            final java.lang.reflect.Method post = handlerCls.getMethod("post", Runnable.class);
            return new Executor() {
                @Override public void execute(Runnable r) {
                    try {
                        Object ok = post.invoke(handler, r);
                        if (Boolean.FALSE.equals(ok)) {
                            r.run();
                        }
                    } catch (Throwable t) {
                        r.run();
                    }
                }
            };
        } catch (Throwable t) {
            return DIRECT_EXECUTOR;
        }
    }

    /** 纯 JVM 兜底：没有 Android 主线程就直接在当前线程跑。 */
    static final Executor DIRECT_EXECUTOR = new Executor() {
        @Override public void execute(Runnable r) {
            r.run();
        }
    };
}
