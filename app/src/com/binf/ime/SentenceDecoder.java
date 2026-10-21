package com.binf.ime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 整句解码：把一串连续拼音（如 "jintiantianqi"）解成整句汉字。
 *
 * 做法是词图 Viterbi + 小 beam：
 *   1. 在拼音串的每个位置 i，枚举所有可能的「词 key」pinyin[i, i+L)
 *   2. 每个 key 去词库取候选词（严格 key 相等）
 *   3. dp[i] = 到位置 i 的最佳分数，转移 dp[i+L] = dp[i] + 词分
 *   4. 每个位置保留前 BEAM 条路径，最后按分数产出多条整句
 *
 * 词分用**一元语言模型**（unigram LM）：
 *
 *     score = Σ log( weight(词) / totalWeight )
 *
 * 这个式子里的 `- log(totalWeight)` 是每个词都要付一次的固定代价，
 * 所以切得越碎、付的次数越多、总分越低 —— 「少切」是模型自然给出的，
 * 不需要手调常数。
 *
 * 曾经用的是「加法制」：score += log(w) + WORD_BONUS + CHAR_BONUS*字数，
 * 结果**越长越碎片分越高**，`nihao` 被解成「你哈哦」而不是「你好」，
 * `woxiangchifan` 被解成「我洗昂吃翻」。加法式无法表达
 * 「多一个词是有代价的」这件事，所以换成了概率式的减法。
 *
 * 关键点：用户还没打完时，尾部会有半截拼音（"jintiantianq"），
 * 所以要在「能完整解码到的最远位置」处截断，剩下的拼音留作待续输入。
 *
 * 不依赖 Context，可在 JVM 上单测。
 */
public final class SentenceDecoder {

    /** 词表接口：给 pinyin key，返回按权重降序的候选词。 */
    public interface WordTable {
        /** @return 严格 pinyin key 对应的候选；可能为空，不返回 null */
        List<PinyinEngine.Candidate> words(String key, int limit);

        /**
         * 全库权重总和，用于 unigram LM 的归一化。
         * 返回 <= 0 表示不知道（测试假表），此时退回旧的加法制。
         */
        default double totalWeight() { return 0; }
    }

    /** 一条解码结果。 */
    public static final class Result {
        public final String text;       // 解码出的汉字
        public final String consumed;   // 吃掉的拼音（前缀）
        public final String rest;       // 没吃掉的尾部拼音
        public final int score;         // 分数（越大越好）
        public final int words;         // 词数

        Result(String text, String consumed, String rest, int score, int words) {
            this.text = text == null ? "" : text;
            this.consumed = consumed == null ? "" : consumed;
            this.rest = rest == null ? "" : rest;
            this.score = score;
            this.words = words;
        }

        public boolean isFull() {
            return rest.isEmpty();
        }

        @Override public String toString() {
            return text + (rest.isEmpty() ? "" : "|" + rest) + "(" + score + ")";
        }
    }

    /** 每个拼音 key 取几个候选词参与解码。 */
    private static final int CAND_PER_KEY = 3;
    /** 单个词长度上限（拼音字符数），防止 substring 爆炸。 */
    private static final int MAX_KEY_CHARS = 24;
    /** 每个位置保留几条路径。 */
    private static final int BEAM = 6;
    /** 第 c 个候选的惩罚（同 key 内排序用，不影响切分粒度）。 */
    private static final double CAND_PENALTY = 0.55;
    /** 未知总权重时的兜底：每个词付一次的固定代价（旧的加法制）。 */
    private static final double FALLBACK_WORD_COST = 8.0;

    private final WordTable table;
    private final int maxKeyChars;
    /** 全库权重总和；<=0 表示未知，退回兜底模式。 */
    private final double logTotal;

    public SentenceDecoder(WordTable table, int maxKeyChars) {
        this.table = table;
        this.maxKeyChars = Math.max(2, Math.min(MAX_KEY_CHARS, maxKeyChars));
        double total = 0;
        if (table != null) {
            try {
                total = table.totalWeight();
            } catch (Throwable ignored) {
                total = 0;
            }
        }
        this.logTotal = total > 0 ? Math.log(total) : 0;
    }

    /**
     * 从 PinyinEngine 造解码器。
     * 优先用 v2 的 exactKey()（严格 key 匹配）；老版本没有就退回 exact()。
     *
     * 同时把全库权重总和接进来，让 decode 能用真正的 unigram LM 打分。
     * 没有 totalWeight()（老引擎）就退回旧的加法制。
     */
    public static SentenceDecoder forEngine(final PinyinEngine engine, int maxKeyChars) {
        if (engine == null) {
            return new SentenceDecoder(new WordTable() {
                @Override public List<PinyinEngine.Candidate> words(String key, int limit) {
                    return Collections.emptyList();
                }
            }, maxKeyChars);
        }
        final Method exactKey = findMethod(engine.getClass(), "exactKey");
        final Method pick = exactKey != null ? exactKey : findMethod(engine.getClass(), "exact");
        final Method total = findTotalWeight(engine.getClass());
        return new SentenceDecoder(new WordTable() {
            @Override public List<PinyinEngine.Candidate> words(String key, int limit) {
                if (pick == null || key == null) return Collections.emptyList();
                try {
                    @SuppressWarnings("unchecked")
                    List<PinyinEngine.Candidate> r =
                            (List<PinyinEngine.Candidate>) pick.invoke(engine, key, limit);
                    return r == null ? Collections.<PinyinEngine.Candidate>emptyList() : r;
                } catch (Throwable t) {
                    return Collections.emptyList();
                }
            }

            @Override public double totalWeight() {
                if (total == null) return 0;
                try {
                    Object v = total.invoke(engine);
                    return v instanceof Number ? ((Number) v).doubleValue() : 0;
                } catch (Throwable t) {
                    return 0;
                }
            }
        }, maxKeyChars);
    }

    private static Method findMethod(Class<?> c, String name) {
        try {
            return c.getMethod(name, String.class, int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findTotalWeight(Class<?> c) {
        // 先试 long totalWeight()，再试 double / int，都按 number 统一处理
        String[] names = {"totalWeight", "totalWeights", "weightSum"};
        for (String n : names) {
            try {
                return c.getMethod(n);
            } catch (Throwable ignored) {
                // 继续试下一个
            }
        }
        return null;
    }

    /** 一条在途路径。 */
    private static final class Path {
        double score;
        String text;      // 已解码的汉字
        String consumed;  // 已吃掉的拼音
        int words;

        Path(double score, String text, String consumed, int words) {
            this.score = score;
            this.text = text == null ? "" : text;
            this.consumed = consumed == null ? "" : consumed;
            this.words = words;
        }
    }

    /**
     * 解码，返回最多 limit 条整句（第 0 条最佳）。
     */
    public List<Result> decode(String pinyin, int limit) {
        List<Result> out = new ArrayList<Result>();
        if (pinyin == null) return out;
        final String s = normalize(pinyin);
        if (s.isEmpty()) return out;
        if (limit <= 0) limit = 5;

        final int n = s.length();
        // 每个「已消费长度」上保留的路径
        List<List<Path>> at = new ArrayList<List<Path>>(n + 1);
        for (int i = 0; i <= n; i++) at.add(new ArrayList<Path>(1));
        at.get(0).add(new Path(0, "", "", 0));

        for (int i = 0; i < n; i++) {
            List<Path> here = at.get(i);
            if (here.isEmpty()) continue;
            int maxL = Math.min(maxKeyChars, n - i);
            for (int L = 1; L <= maxL; L++) {
                String key = s.substring(i, i + L);
                List<PinyinEngine.Candidate> cands = table.words(key, CAND_PER_KEY);
                if (cands == null || cands.isEmpty()) continue;
                List<Path> next = at.get(i + L);
                for (int p = 0; p < here.size(); p++) {
                    Path path = here.get(p);
                    for (int c = 0; c < cands.size() && c < CAND_PER_KEY; c++) {
                        PinyinEngine.Candidate cand = cands.get(c);
                        if (cand == null || cand.text == null || cand.text.isEmpty()) continue;
                        double w = Math.max(1.0, cand.weight);
                        double sc = path.score + wordScore(w, c);
                        next.add(new Path(sc, path.text + cand.text,
                                path.consumed + key, path.words + 1));
                    }
                }
                // 裁 beam
                trimToBeam(next);
            }
        }

        // 找能完整解码到的最远位置
        int far = n;
        while (far > 0 && at.get(far).isEmpty()) far--;
        if (far <= 0) return out;

        List<Path> finals = new ArrayList<Path>(at.get(far));
        Collections.sort(finals, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                if (a.score != b.score) return Double.compare(b.score, a.score);
                return b.text.length() - a.text.length();
            }
        });

        String rest = s.substring(far);
        for (int i = 0; i < finals.size() && out.size() < limit; i++) {
            Path p = finals.get(i);
            boolean dup = false;
            for (int j = 0; j < out.size(); j++) {
                if (out.get(j).text.equals(p.text)) { dup = true; break; }
            }
            if (dup) continue;
            out.add(new Result(p.text, p.consumed, rest,
                    (int) Math.round(p.score), p.words));
        }
        return out;
    }

    /** 只保留分数最高的 BEAM 条路径，且去重文本。 */
    private static void trimToBeam(List<Path> list) {
        if (list.size() <= BEAM) return;
        Collections.sort(list, new Comparator<Path>() {
            @Override public int compare(Path a, Path b) {
                return Double.compare(b.score, a.score);
            }
        });
        List<Path> kept = new ArrayList<Path>(BEAM);
        for (int i = 0; i < list.size() && kept.size() < BEAM; i++) {
            Path p = list.get(i);
            boolean dup = false;
            for (int j = 0; j < kept.size(); j++) {
                if (kept.get(j).text.equals(p.text)) { dup = true; break; }
            }
            if (!dup) kept.add(p);
        }
        list.clear();
        list.addAll(kept);
    }

    /**
     * 单个词的得分。
     *
     * unigram LM：log(weight/total) = log(weight) - log(total)。
     * 后半截 `- log(total)` 是每个词都要付一次的固定代价，所以
     * 「多切一个词」永远意味着多付一次代价 —— 这就是我们想要的
     * 「倾向少切、倾向整词」的数学来源，不用手调常数。
     *
     * 同 key 下第 c 个候选额外扣 c*CAND_PENALTY，让「中国」优先于「种过」。
     *
     * total 未知时（测试假表 / 老引擎）退回 FALLBACK_WORD_COST 固定代价，
     * 行为仍是「加法 + 每词付代价」，只是常数是拍的。
     */
    private double wordScore(double weight, int candIndex) {
        double base;
        if (logTotal > 0) {
            base = Math.log(weight) - logTotal;
        } else {
            base = Math.log(weight) - FALLBACK_WORD_COST;
        }
        return base - candIndex * CAND_PENALTY;
    }

    /** 兼容旧调用：等同于 decode(pinyin, limit)。 */
    public List<Result> decodeMulti(String pinyin, int limit) {
        return decode(pinyin, limit);
    }

    /** 只要一条最好的整句，没有就返回 null。 */
    public Result best(String pinyin) {
        List<Result> r = decode(pinyin, 1);
        return r.isEmpty() ? null : r.get(0);
    }

    // ---------- 工具 ----------

    /** 归一化：只留 a-z，转小写。 */
    public static String normalize(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        return sb.toString();
    }

    /** 供测试：归一化后长度不变（即原串已经是纯字母）。 */
    public static boolean isPlainPinyin(String in) {
        if (in == null || in.isEmpty()) return false;
        return normalize(in).length() == in.length();
    }

    /** 排序器：rest 短的优先（更完整的解码优先），其次分数高的。 */
    public static final Comparator<Result> BY_SCORE = new Comparator<Result>() {
        @Override public int compare(Result a, Result b) {
            if (a.rest.length() != b.rest.length()) return a.rest.length() - b.rest.length();
            if (a.score != b.score) return b.score - a.score;
            return a.text.compareTo(b.text);
        }
    };
}
