package com.binf.ime;

import android.content.Context;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 拼音输入引擎（v2 词库）。
 *
 * 词典由 tools/build_dict.py 从 Rime 词库编译而来，格式见该脚本顶部注释。
 * v2 相比 v1 增加了合法音节表（sylPool），供九键（T9）把数字串展开成拼音用。
 *
 * 加载后常驻内存，前缀查找用二分。词条按 key 字典序排列，同 key 内按权重降序。
 */
public final class PinyinEngine {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 完全匹配时的权重加成：让 "ni" 优先出单字「你」，而不是 "nihao" 的词。 */
    private static final int EXACT_BOOST = 100000;
    /** 单字（1 个汉字）的额外加成：整句解码更倾向用单字组句。 */
    private static final int SINGLE_CHAR_BOOST = 20000;

    private static PinyinEngine instance;

    private final int count;
    private final int sylCount;
    private final int[] keyOff;
    private final int[] hzOff;
    private final int[] weight;
    private final byte[] keyPool;
    private final byte[] hzPool;
    private final int[] sylOff;
    private final byte[] sylPool;
    /** 词典里最长汉字词的长度（供整句解码设上限）。 */
    private final int maxWordChars;
    /** 词典里最长 key 的音节数估算（供九键限制展开深度）。 */
    private final int maxKeySyllables;
    /** 全库权重总和（整句解码的 unigram LM 归一化用）。 */
    private final long totalWeight;

    /** 候选词。 */
    public static final class Candidate {
        public final String text;
        public final int weight;
        public Candidate(String text, int weight) { this.text = text; this.weight = weight; }
        @Override public String toString() { return text; }
    }

    private PinyinEngine(int count, int sylCount,
                         int[] keyOff, int[] hzOff, int[] weight, byte[] keyPool, byte[] hzPool,
                         int[] sylOff, byte[] sylPool) {
        this.count = count;
        this.sylCount = sylCount;
        this.keyOff = keyOff;
        this.hzOff = hzOff;
        this.weight = weight;
        this.keyPool = keyPool;
        this.hzPool = hzPool;
        this.sylOff = sylOff;
        this.sylPool = sylPool;

        int maxWc = 1;
        // 抽样估算最长词长，避免全表扫描（全扫 54 万条约 50ms，只做一次可接受，但没必要）
        int step = Math.max(1, count / 4096);
        for (int i = 0; i < count; i += step) {
            int n = hzOff[i + 1] - hzOff[i] - 1;
            // UTF-8 汉字 3 字节，粗略换算成字数
            int chars = n / 3;
            if (chars > maxWc) maxWc = chars;
        }
        this.maxWordChars = Math.max(1, maxWc);

        // 最长 key 的音节数：用最长 key 长度 / 1 上限估算（保守取 8）
        int maxKl = 0;
        for (int i = 0; i < count; i += step) {
            int n = keyOff[i + 1] - keyOff[i] - 1;
            if (n > maxKl) maxKl = n;
        }
        this.maxKeySyllables = Math.max(1, Math.min(12, maxKl / 2 + 1));

        // 全库权重总和：54 万条累加约 1ms，只在加载时算一次。
        // 整句解码的 unigram LM 要它做归一化（见 SentenceDecoder.wordScore）。
        long sum = 0;
        for (int i = 0; i < count; i++) sum += Math.max(0, weight[i]);
        this.totalWeight = sum;
    }

    /** 全库权重总和；<=0 表示词库为空。 */
    public long totalWeight() { return totalWeight; }

    public static synchronized PinyinEngine get(Context ctx) {
        if (instance == null) {
            instance = load(ctx);
        }
        return instance;
    }

    /** 供测试注入：直接用现成实例。 */
    static synchronized void setInstance(PinyinEngine e) {
        instance = e;
    }

    /** 供测试用：从任意流加载（流里是 gzip 压缩的 v2 数据）。 */
    public static PinyinEngine loadForTest(java.io.InputStream rawIn) throws IOException {
        return loadFrom(rawIn);
    }

    /** 供测试用：从任意流加载（流里是 gzip 压缩的 v2 数据）。 */
    static PinyinEngine loadFrom(InputStream rawIn) throws IOException {
        DataInputStream din = new DataInputStream(new GZIPInputStream(rawIn, 1 << 16));
        try {
            return readV2(din);
        } finally {
            try { din.close(); } catch (IOException ignored) {}
        }
    }

    private static PinyinEngine load(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open("pinyin.dict.gz");
            return readV2(new DataInputStream(new GZIPInputStream(in, 1 << 16)));
        } catch (Exception e) {
            // 词典坏了也不能崩，退化成空引擎
            return empty();
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
        }
    }

    private static PinyinEngine empty() {
        return new PinyinEngine(0, 0, new int[]{0}, new int[]{0}, new int[0],
                new byte[0], new byte[0], new int[]{0}, new byte[0]);
    }

    /** 按 v2 格式读出全部数据。 */
    private static PinyinEngine readV2(DataInputStream din) throws IOException {
        byte[] magic = new byte[4];
        din.readFully(magic);
        if (magic[0] != 'B' || magic[1] != 'P' || magic[2] != 'Y' || magic[3] != '2') {
            throw new IOException("词典 magic 不匹配，期望 BPY2，实际 "
                    + new String(magic, UTF8));
        }
        int version = din.readInt();
        if (version != 2) throw new IOException("词典版本不支持: " + version);
        int count = din.readInt();
        int sylCount = din.readInt();
        if (count < 0 || sylCount < 0) throw new IOException("词典头损坏");

        int[] keyOff = new int[count + 1];
        for (int i = 0; i <= count; i++) keyOff[i] = din.readInt();
        int[] hzOff = new int[count + 1];
        for (int i = 0; i <= count; i++) hzOff[i] = din.readInt();
        int[] weight = new int[count];
        for (int i = 0; i < count; i++) weight[i] = din.readInt();
        int[] sylOff = new int[sylCount + 1];
        for (int i = 0; i <= sylCount; i++) sylOff[i] = din.readInt();

        byte[] keyPool = new byte[keyOff[count]];
        din.readFully(keyPool);
        byte[] hzPool = new byte[hzOff[count]];
        din.readFully(hzPool);
        byte[] sylPool = new byte[sylOff[sylCount]];
        din.readFully(sylPool);

        return new PinyinEngine(count, sylCount, keyOff, hzOff, weight,
                keyPool, hzPool, sylOff, sylPool);
    }

    public int size() { return count; }

    /** 合法音节数（九键展开用）。 */
    public int syllableCount() { return sylCount; }

    /** 词典里最长汉字词长度。 */
    public int maxWordChars() { return maxWordChars; }

    /** 词典里最长 key 的音节数（保守上限）。 */
    public int maxKeySyllables() { return maxKeySyllables; }

    private String keyAt(int i) {
        return new String(keyPool, keyOff[i], keyOff[i + 1] - keyOff[i] - 1, UTF8);
    }

    private String hzAt(int i) {
        return new String(hzPool, hzOff[i], hzOff[i + 1] - hzOff[i] - 1, UTF8);
    }

    private String sylAt(int i) {
        return new String(sylPool, sylOff[i], sylOff[i + 1] - sylOff[i] - 1, UTF8);
    }

    /** 全部合法音节（已排序）。每次新建数组，别在热路径频繁调。 */
    public String[] syllables() {
        String[] out = new String[sylCount];
        for (int i = 0; i < sylCount; i++) out[i] = sylAt(i);
        return out;
    }

    /** 某个 key 是否是合法拼音音节。 */
    public boolean hasSyllable(String s) {
        return indexOfSyllable(s) >= 0;
    }

    private int indexOfSyllable(String s) {
        int lo = 0, hi = sylCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = sylAt(mid).compareTo(s);
            if (c == 0) return mid;
            if (c < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /** 找到第一个 key >= target 的下标。 */
    private int lowerBound(String target) {
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keyAt(mid).compareTo(target) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 是否有这个 key 的词条。 */
    public boolean hasKey(String key) {
        if (count == 0 || key == null || key.isEmpty()) return false;
        int i = lowerBound(key);
        return i < count && keyAt(i).equals(key);
    }

    /**
     * 前缀查询。
     *
     * 实现要点：**不能按字典序截断**。
     *
     * 词条按 (key, -weight) 排序，而一个短前缀（"y" 匹配 4.7 万条、"zh" 3 万条）
     * 会横跨几千个不同的 key。早期版本有 maxScan=512 的扫描上限，扫到的是
     * 「字典序靠前的那些 key」，不是权重最高的那些 —— 实测输入 "y" 出来的是
     * 呀/雅虎/亚/牙齿，而「一」「有」「也」「要」全部没进来；输入 "zh" 出来的是
     * 债券/债务/摘要，「制度」「这」「中」全丢了。这是用户一眼能看见的错。
     *
     * 现在改成：先二分出前缀对应的连续区间 [lo, hi)，再在区间内做一次
     * **只保留 top-limit 的线性扫描**。扫描过程不构造任何字符串
     * （只读 weight[] 和 keyOff[] 两个 int 数组），最后才为入选的几条解码，
     * 所以 4.7 万条全扫也只有 1ms 量级。
     *
     * @param prefix 已去掉空格的拼音串，如 "nihao"
     * @return 按权重降序的候选，最多 limit 条
     */
    public List<Candidate> query(String prefix, int limit) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (count == 0 || prefix == null || prefix.isEmpty() || limit <= 0) return out;

        final int lo = lowerBound(prefix);
        final int hi = upperBoundPrefix(prefix);
        if (lo >= hi) return out;
        // 双保险：确认 lo 确实落在前缀里（upperBoundPrefix 正常时必然成立）
        if (!keyAt(lo).startsWith(prefix)) return out;

        final int plen = prefix.length();
        // 多取一些：同字多音会让去重吃掉几条（「这」有 zhe/zhei 两个 key），
        // 只取 limit 条的话去重后可能不够数。
        final int k = Math.min(limit * 2 + 8, hi - lo);
        int[] bestW = new int[k];
        int[] bestI = new int[k];
        int filled = 0;

        for (int i = lo; i < hi; i++) {
            int w = weight[i];
            // 完全匹配给额外加成，让 "ni" 优先出单字「你」而不是 "nihao" 的词
            if (keyOff[i + 1] - keyOff[i] - 1 == plen) w += EXACT_BOOST;
            if (filled == k && w <= bestW[k - 1]) continue;
            int p = filled < k ? filled : k - 1;
            while (p > 0 && bestW[p - 1] < w) {
                bestW[p] = bestW[p - 1];
                bestI[p] = bestI[p - 1];
                p--;
            }
            bestW[p] = w;
            bestI[p] = i;
            if (filled < k) filled++;
        }

        for (int j = 0; j < filled; j++) out.add(new Candidate(hzAt(bestI[j]), bestW[j]));
        sortDesc(out);
        // 同一个字可能挂在多个 key 下（「这」既有 zhe 也有 zhei 的读音），
        // 前缀查询会把它们都捞进来，显示成两个「这」。按文本去重，留权重高的。
        out = dedupeByText(out);
        if (out.size() > limit) return new ArrayList<Candidate>(out.subList(0, limit));
        return out;
    }

    /** 按候选文本去重（保持输入的顺序，即权重降序），保留首次出现的那个。 */
    private static List<Candidate> dedupeByText(List<Candidate> in) {
        if (in.size() <= 1) return in;
        java.util.LinkedHashMap<String, Candidate> m = new java.util.LinkedHashMap<String, Candidate>();
        for (Candidate c : in) {
            if (!m.containsKey(c.text)) m.put(c.text, c);
        }
        if (m.size() == in.size()) return in;
        return new ArrayList<Candidate>(m.values());
    }

    /**
     * 前缀区间的右边界：第一个「不以 prefix 开头」的 key 下标。
     *
     * 做法是把 prefix 最后一个字符加一（拼音 key 全是小写 a-z，加一后一定更大），
     * 再二分找第一个 >= 它的 key。例如 "y" → "z"，所有 y 开头的 key 都 < "z"。
     */
    private int upperBoundPrefix(String prefix) {
        char[] cs = prefix.toCharArray();
        int i = cs.length - 1;
        while (i >= 0 && cs[i] == '\uffff') i--;
        if (i < 0) return count;
        cs[i]++;
        return lowerBound(new String(cs, 0, i + 1));
    }

    /**
     * 严格 key 匹配：只返回 key 完全等于 pinyin 的词条。
     *
     * 这个方法对整句解码至关重要 —— 老的 exact() 实际是前缀匹配，
     * 拿 "shi" 去查会把 "shijian" 也带出来，切词边界就全错了。
     */
    public List<Candidate> exactKey(String pinyin, int limit) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (count == 0 || pinyin == null || pinyin.isEmpty() || limit <= 0) return out;

        int i = lowerBound(pinyin);
        while (i < count && out.size() < limit) {
            String k = keyAt(i);
            if (!k.equals(pinyin)) break;
            out.add(new Candidate(hzAt(i), weight[i]));
            i++;
        }
        sortDesc(out);
        return out;
    }

    /** 兼容老 API：等价于 exactKey（v1 时期是前缀匹配，这里顺手修正）。 */
    public List<Candidate> exact(String pinyin, int limit) {
        return exactKey(pinyin, limit);
    }

    private static void sortDesc(List<Candidate> out) {
        Collections.sort(out, BY_WEIGHT_DESC);
    }

    private static final Comparator<Candidate> BY_WEIGHT_DESC = new Comparator<Candidate>() {
        @Override public int compare(Candidate a, Candidate b) {
            if (a.weight != b.weight) return b.weight - a.weight;
            return a.text.compareTo(b.text);
        }
    };

    /**
     * 单字加权：整句解码时给单字加一点分。
     * 因为单字权重本来就被词组压着（词组有 EXACT_BOOST），
     * 不加这个会导致整句永远选不出单个字。
     */
    public List<Candidate> singleCharCandidates(String pinyin, int limit) {
        List<Candidate> raw = exactKey(pinyin, limit * 2);
        List<Candidate> out = new ArrayList<Candidate>(raw.size());
        for (Candidate c : raw) {
            if (c.text.length() == 1) {
                out.add(new Candidate(c.text, c.weight + SINGLE_CHAR_BOOST));
            } else {
                out.add(c);
            }
        }
        sortDesc(out);
        if (out.size() > limit) return new ArrayList<Candidate>(out.subList(0, limit));
        return out;
    }
}
