package com.binf.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 九键（T9）拼音引擎。
 *
 * 九键的核心问题：用户按的是数字串，而词典的 key 是拼音串，中间差一层「数字 → 拼音」的反解。
 * 例如 "276" 可能对应 "an"/"ao"/"bo"/"cp" 等一堆音节，必须枚举所有可能的音节切分，
 * 再拿每一种切分去词典里查候选，最后按词频合并排序。
 *
 * 映射（标准 9 键）：
 *   2 abc   3 def   4 ghi   5 jkl   6 mno   7 pqrs   8 tuv   9 wxyz
 *
 * 本类不依赖 Context，可单独在 JVM 上测。
 */
public final class T9Engine {

    /** 内置音节表兜底（来自 rime-ice 8105 单字 pinyin，共 414 个）。 */
    public static final String[] BUILTIN_SYLLABLES = {
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bu" ,
            "ca cai can cang cao ce cei cen ceng cha chai chan chang chao che chen cheng chi chong" ,
            "chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai" ,
            "dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo" ,
            "e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen" ,
            "geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong" ,
            "hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju" ,
            "juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun" ,
            "kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu" ,
            "luan lun luo lv lve ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo" ,
            "mou mu na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu" ,
            "nuan nuo nv nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi" ,
            "qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong" ,
            "rou ru rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she" ,
            "shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui" ,
            "sun suo ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo" ,
            "wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan" ,
            "xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze" ,
            "zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai" ,
            "zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
    };

    /** 单个数字串能展开的最大音节组合数，防止爆炸。 */
    private static final int MAX_COMBOS = 192;
    /** 最多按几个音节切分（"jintiantianqi" 是 6 个，留足余量）。 */
    private static final int MAX_SYLLABLES = 14;
    /**
     * 每个音节扣的分，抑制「切得越碎分越高」。
     * 音节权重走对数（60 万权重才 13.3 分），所以惩罚取 6.0：
     * 一个长音节必须比拆成两个短音节高出 6 分以上才值得。
     */
    private static final double SYLLABLE_PENALTY = 6.0;

    private final Map<String, List<String>> digitToSyllables;
    /** 音节 → 是否合法（用于整体合法性检查）。 */
    private final Map<String, Boolean> syllableSet = new HashMap<String, Boolean>();
    /** 最长音节的数字长度。 */
    private final int maxDigitLen;

    private static T9Engine defaultInstance;

    public T9Engine(String[] syllables) {
        if (syllables == null || syllables.length == 0) syllables = BUILTIN_SYLLABLES;
        digitToSyllables = new HashMap<String, List<String>>();
        int maxLen = 0;
        for (String raw : syllables) {
            if (raw == null) continue;
            for (String s : raw.trim().split("\\s+")) {
                if (!isLowerLetters(s)) continue;
                syllableSet.put(s, Boolean.TRUE);
                String d = encode(s);
                List<String> bucket = digitToSyllables.get(d);
                if (bucket == null) {
                    bucket = new ArrayList<String>();
                    digitToSyllables.put(d, bucket);
                }
                if (!bucket.contains(s)) bucket.add(s);
                if (d.length() > maxLen) maxLen = d.length();
            }
        }
        maxDigitLen = Math.max(1, maxLen);
    }

    /** 用内置音节表构造的默认实例。 */
    public static synchronized T9Engine getDefault() {
        if (defaultInstance == null) defaultInstance = new T9Engine(BUILTIN_SYLLABLES);
        return defaultInstance;
    }

    // ---------- 数字 ↔ 拼音 ----------

    /** 字母 → 九键数字，非 a-z 返回 0。 */
    public static char digitFor(char c) {
        if (c >= 'a' && c <= 'c') return '2';
        if (c >= 'd' && c <= 'f') return '3';
        if (c >= 'g' && c <= 'i') return '4';
        if (c >= 'j' && c <= 'l') return '5';
        if (c >= 'm' && c <= 'o') return '6';
        if (c >= 'p' && c <= 's') return '7';
        if (c >= 't' && c <= 'v') return '8';
        if (c >= 'w' && c <= 'z') return '9';
        return 0;
    }

    /** 拼音串 → 数字串。含非法字符返回 null。 */
    public static String encode(String pinyin) {
        if (pinyin == null || pinyin.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(pinyin.length());
        for (int i = 0; i < pinyin.length(); i++) {
            char c = pinyin.charAt(i);
            if (c == ' ') continue;
            char d = digitFor(c);
            if (d == 0) return null;
            sb.append(d);
        }
        return sb.toString();
    }

    /** 该数字串是否可能是合法拼音的开头（用于灰键/合法性判断）。 */
    public boolean isValidPrefix(String digits) {
        if (digits == null || digits.isEmpty()) return false;
        for (String d : digitToSyllables.keySet()) {
            if (d.startsWith(digits)) return true;
        }
        // 长串：按可切分判断
        return !segment(digits, 1).isEmpty();
    }

    /**
     * 把数字串切成音节，返回所有可能的拼音串（音节之间不加分隔）。
     *
     * @param digits 只含 2-9 的数字串
     * @param max    最多返回多少种
     * @return 例如 "64426" → ["nihao", "mihao", "nihan", ...]
     */
    public List<String> segment(String digits, int max) {
        List<List<String>> parts = segmentations(digits, max);
        List<String> out = new ArrayList<String>(parts.size());
        for (List<String> p : parts) {
            StringBuilder sb = new StringBuilder();
            for (String s : p) sb.append(s);
            out.add(sb.toString());
        }
        return out;
    }

    /** 所有可能的音节切分（每个是音节列表）。 */
    public List<List<String>> segmentations(String digits, int max) {
        List<List<String>> out = new ArrayList<List<String>>();
        if (digits == null || digits.isEmpty()) return out;
        if (max <= 0) max = MAX_COMBOS;
        int n = digits.length();

        // 先做一次可达性 DP：reach[i] = digits[i..] 能不能被完整切成合法音节。
        // 没有它的话，像 "944649464326644869444944" 这种压根切不动的串要白跑
        // 3 万多次递归（实测 35ms），而按键是同步调用的，会卡住输入。
        boolean[] reach = new boolean[n + 1];
        reach[n] = true;
        for (int pos = n - 1; pos >= 0; pos--) {
            for (int len = 1; len <= Math.min(maxDigitLen, n - pos); len++) {
                if (!reach[pos + len]) continue;
                List<String> b = digitToSyllables.get(digits.substring(pos, pos + len));
                if (b != null && !b.isEmpty()) { reach[pos] = true; break; }
            }
        }
        if (!reach[0]) return out;

        List<String> stack = new ArrayList<String>();
        // 按「音节数从少到多」枚举，而不是纯 DFS 的字典序。
        //
        // 纯 DFS 是从左往右、每种长度 1,2,3... 依次试，等价于**字母序**：
        // 短音节先穷尽，长音节排到很后面。而中文里「zhong/zhuang/chuang」这些
        // 5~6 位的长音节恰恰是最常见的，等它们被枚举到时配额（MAX_COMBOS）早用完了。
        // 实测 13 位数字串有 2904 种切分，前 192 种全是 "x-i-o-m-i-g-u-o-..." 这种碎切，
        // 真正的 "zhongguo..." 一条都进不来。
        //
        // 换成按音节数升序后：少音节 = 更可能是一个词或短语，
        // 截断时优先保留的正好是最像人话的那批。
        for (int k = 1; k <= MAX_SYLLABLES && k <= n; k++) {
            if (out.size() >= max) break;
            dfsExact(digits, 0, stack, out, max, k, reach);
        }
        return out;
    }

    /** 枚举「恰好 target 个音节」的切分；靠剩余长度和可达性剪枝，单次代价很小。 */
    private void dfsExact(String digits, int pos, List<String> stack,
                          List<List<String>> out, int max, int target, boolean[] reach) {
        if (out.size() >= max) return;
        int slots = target - stack.size();
        int remain = digits.length() - pos;
        if (slots == 0) {
            if (remain == 0) out.add(new ArrayList<String>(stack));
            return;
        }
        // 剩下的数字要正好填满剩下的槽：每位至少 1 个数字、最多 maxDigitLen 个
        if (remain < slots || remain > slots * maxDigitLen) return;
        for (int len = 1; len <= Math.min(maxDigitLen, remain); len++) {
            int next = pos + len;
            if (!reach[next]) continue;
            List<String> bucket = digitToSyllables.get(digits.substring(pos, next));
            if (bucket == null || bucket.isEmpty()) continue;
            for (String syl : bucket) {
                stack.add(syl);
                dfsExact(digits, next, stack, out, max, target, reach);
                stack.remove(stack.size() - 1);
                if (out.size() >= max) return;
            }
        }
    }

    // ---------- 带权重的切分（用于展示和候选排序） ----------

    /** 音节权重查询器：返回该音节的权重，0 表示不认识。 */
    public interface SyllableWeights {
        int weightOf(String syllable);

        /**
         * 所有合法音节权重之和，用来把权重换成概率。
         *
         * 这个值决定切分打分是「概率模式」还是「罚分模式」：
         * 概率模式下 score = Σ log(w_i / total)，多一个音节就多乘一个概率因子，
         * 天然抑制「把 ni hao 切成 ni ha o」这种碎切，不需要任何手调常数。
         *
         * 返回 <= 0 表示调用方不知道总和，退化为老的固定罚分模式。
         */
        default long totalWeight() { return 0L; }
    }

    /**
     * 给每种切分打分，分数最高的就是「最像人话」的读法。
     *
     * 首选概率模式：score = Σ log(w_i / total)。因为 total 对所有切分相同，
     * 等价于 Σ log(w_i) - n·log(total)，也就是**每个音节自动被扣 log(total)**。
     * 这比手调罚分稳：无论词库量纲怎么变，多切一刀总是要付真实概率代价。
     *
     * 老实现用固定罚分 SYLLABLE_PENALTY=6.0，而音节 log 权重最高到 13.8，
     * 于是每多切一刀反而净赚 7.8 分，导致 "64426" 展示成 "ni ha o"。
     */
    public List<String> bestSegments(String digits, SyllableWeights w) {
        if (w == null) return null;
        List<List<String>> all = segmentations(digits, MAX_COMBOS);
        if (all.isEmpty()) return null;

        final long total = w.totalWeight();
        final boolean probMode = total > 0;
        final double logTotal = probMode ? Math.log(total) : 0.0;

        List<String> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<String> seg : all) {
            double score = 0;
            boolean okAll = true;
            for (String syl : seg) {
                int wt = w.weightOf(syl);
                if (wt <= 0) { okAll = false; break; }
                if (probMode) score += Math.log(wt) - logTotal;
                else score += Math.log(1 + wt) - SYLLABLE_PENALTY;
            }
            if (!okAll) continue;
            // 同分时偏好音节更少的（少切一刀总是更像真的读音）
            if (score > bestScore) {
                bestScore = score;
                best = seg;
            }
        }
        return best != null ? best : all.get(0);
    }

    /**
     * 带权重的展示：把数字串显示成最优切分的拼音，例如 "64426" → "ni hao"。
     */
    public String displayPinyin(String digits, SyllableWeights w) {
        if (digits == null || digits.isEmpty()) return "";
        List<String> seg = bestSegments(digits, w);
        if (seg == null || seg.isEmpty()) return displayPinyin(digits);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(seg.get(i));
        }
        return sb.toString();
    }

    /** 把 PinyinEngine 适配成 SyllableWeights（反射取 exactKey / exact，兼容 v1/v2）。 */
    public static SyllableWeights weightsFrom(final PinyinEngine engine) {
        if (engine == null) return null;
        final java.lang.reflect.Method m = findQuery(engine.getClass());
        if (m == null) return null;
        final java.lang.reflect.Method sylls = findSyllables(engine.getClass());

        // 音节总数只在第一次用到时算一遍（414 次查表，几毫秒），之后常驻
        final long[] totalCache = {0L};

        return new SyllableWeights() {
            @Override public int weightOf(String syllable) {
                try {
                    @SuppressWarnings("unchecked")
                    List<PinyinEngine.Candidate> r =
                            (List<PinyinEngine.Candidate>) m.invoke(engine, syllable, 1);
                    if (r == null || r.isEmpty()) return 0;
                    PinyinEngine.Candidate c = r.get(0);
                    // 严格 key 相等才算（exact() 在 v1 里是前缀匹配）
                    return c == null ? 0 : Math.max(0, c.weight);
                } catch (Throwable t) {
                    return 0;
                }
            }

            /**
             * 合法音节的权重总和，用来把音节权重换成概率。
             *
             * 只有引擎能给出音节表（v2 有 syllables()）时才算得出；
             * 拿不到就返回 0，bestSegments 会自动退回老的固定罚分模式。
             */
            @Override public long totalWeight() {
                if (totalCache[0] > 0) return totalCache[0];
                if (sylls == null) return 0L;
                try {
                    String[] arr = (String[]) sylls.invoke(engine);
                    if (arr == null || arr.length == 0) return 0L;
                    long sum = 0;
                    for (String s : arr) {
                        int w = weightOf(s);
                        if (w > 0) sum += w;
                    }
                    totalCache[0] = sum;
                    return sum;
                } catch (Throwable t) {
                    return 0L;
                }
            }
        };
    }

    /** 找 syllables() 方法（v2 才有）。 */
    private static java.lang.reflect.Method findSyllables(Class<?> c) {
        try {
            return c.getMethod("syllables");
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method findQuery(Class<?> c) {
        try {
            java.lang.reflect.Method m = c.getMethod("exactKey", String.class, int.class);
            if (m != null) return m;
        } catch (Throwable ignored) {
        }
        try {
            return c.getMethod("exact", String.class, int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 带权重的展示快捷方式。 */
    public String displayPinyin(String digits, PinyinEngine engine) {
        return displayPinyin(digits, weightsFrom(engine));
    }

    /**
     * 九键候选。把数字串展开成若干拼音串，逐个查词典，按权重合并去重。
     *
     * @param digits 数字串
     * @param engine 词库
     * @param limit  最多返回多少个候选
     */
    public List<PinyinEngine.Candidate> candidates(String digits, PinyinEngine engine, int limit) {
        List<PinyinEngine.Candidate> out = new ArrayList<PinyinEngine.Candidate>();
        if (engine == null || engine.size() == 0 || digits == null || digits.isEmpty()) {
            return out;
        }
        // 按概率顺序展开（不是 DFS 顺序），这样「最像人话」的读音先查，
        // 权重高的词先入表，merged.size() 的提前 break 才不会漏掉它们。
        List<String> pyList = pinyinListByScore(digits, engine, MAX_COMBOS);

        // 用 LinkedHashMap 去重同时保留插入顺序，最后统一排序
        Map<String, Integer> merged = new HashMap<String, Integer>();
        for (String py : pyList) {
            List<PinyinEngine.Candidate> cs = engine.query(py, 8);
            for (PinyinEngine.Candidate c : cs) {
                if (c == null || c.text == null) continue;
                Integer old = merged.get(c.text);
                if (old == null || c.weight > old) merged.put(c.text, c.weight);
            }
            if (merged.size() >= limit * 6) break;
        }

        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            out.add(new PinyinEngine.Candidate(e.getKey(), e.getValue()));
        }
        Collections.sort(out, new Comparator<PinyinEngine.Candidate>() {
            @Override public int compare(PinyinEngine.Candidate a, PinyinEngine.Candidate b) {
                if (a.weight != b.weight) return b.weight - a.weight;
                if (a.text.length() != b.text.length()) return a.text.length() - b.text.length();
                return a.text.compareTo(b.text);
            }
        });
        if (out.size() > limit) return new ArrayList<PinyinEngine.Candidate>(out.subList(0, limit));
        return out;
    }

    /** 前缀查询用：数字串展开成拼音后，取前若干个拼音串做前缀检索。 */
    public List<String> pinyinList(String digits, int max) {
        List<String> list = segment(digits, max);
        return list == null ? new ArrayList<String>() : list;
    }

    /**
     * 按「像人话的程度」排好序的拼音串列表（最可能的在前）。
     *
     * 和 pinyinList 的区别是关键的一处：pinyinList 用的是 DFS 顺序，
     * 也就是**字母顺序**，出来的第一种切分可能极不合理。
     * 例如 "94664486" 的 DFS 第一项是 "xiomiguo"（洗哦米国），
     * 而真正想要的 "zhongguo" 排在很后面，只取前 8 个根本轮不到。
     *
     * 整句解码必须按概率顺序取，否则「中国」永远出不来。
     *
     * @param digits 数字串
     * @param engine 词库（用来给音节取权重）
     * @param max    最多返回多少种
     */
    public List<String> pinyinListByScore(String digits, PinyinEngine engine, int max) {
        List<String> result = new ArrayList<String>();
        if (digits == null || digits.isEmpty()) return result;
        SyllableWeights w = weightsFrom(engine);
        List<List<String>> all = segmentations(digits, MAX_COMBOS);
        if (all.isEmpty()) return result;

        final long total = w == null ? 0 : w.totalWeight();
        final boolean probMode = total > 0;
        final double logTotal = probMode ? Math.log(total) : 0.0;

        // 每种切分算分，最后按分数降序
        List<double[]> scored = new ArrayList<double[]>(all.size());
        List<String> joined = new ArrayList<String>(all.size());
        for (int i = 0; i < all.size(); i++) {
            List<String> seg = all.get(i);
            double score = 0;
            boolean okAll = true;
            for (String syl : seg) {
                int wt = w == null ? 0 : w.weightOf(syl);
                if (wt <= 0) { okAll = false; break; }
                score += probMode ? Math.log(wt) - logTotal
                        : Math.log(1 + wt) - SYLLABLE_PENALTY;
            }
            if (!okAll) continue;
            StringBuilder sb = new StringBuilder();
            for (String s : seg) sb.append(s);
            scored.add(new double[]{score});
            joined.add(sb.toString());
        }
        if (joined.isEmpty()) {
            // 权重拿不到（老词库 / 空表），退回 DFS 顺序
            return pinyinList(digits, max);
        }
        // 用索引排序，保证 join 的结果和分数对得上
        final List<String> keys = joined;
        final List<double[]> sc = scored;
        List<Integer> idx = new ArrayList<Integer>(keys.size());
        for (int i = 0; i < keys.size(); i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                int c = Double.compare(sc.get(b)[0], sc.get(a)[0]);
                if (c != 0) return c;
                // 同分时短拼音串优先（音节更少 = 更可能是整词）
                return keys.get(a).length() - keys.get(b).length();
            }
        });
        Set<String> seen = new LinkedHashSet<String>();
        for (Integer i : idx) {
            if (seen.add(keys.get(i))) result.add(keys.get(i));
            if (result.size() >= max) break;
        }
        return result;
    }

    /** 当前数字串的一个「好看」的拼音展示（用于候选栏上方的组合态显示）。 */
    public String displayPinyin(String digits) {
        List<String> segs = displaySegments(digits);
        if (segs.isEmpty()) return digits == null ? "" : digits;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(segs.get(i));
        }
        return sb.toString();
    }

    /** 取一种音节切分（最长匹配优先），用于展示。 */
    List<String> displaySegments(String digits) {
        List<String> out = new ArrayList<String>();
        if (digits == null) return out;
        int pos = 0;
        while (pos < digits.length()) {
            String best = null;
            for (int len = Math.min(maxDigitLen, digits.length() - pos); len >= 1; len--) {
                List<String> bucket = digitToSyllables.get(digits.substring(pos, pos + len));
                if (bucket != null && !bucket.isEmpty()) {
                    best = bucket.get(0);
                    pos += len;
                    break;
                }
            }
            if (best == null) {
                // 这一位接不上，把剩下的一起当拼音显示，别卡死
                out.add(digits.substring(pos));
                return out;
            }
            out.add(best);
        }
        return out;
    }

    /** 音节是否合法。 */
    public boolean isSyllable(String s) {
        return s != null && syllableSet.containsKey(s);
    }

    public int syllableCount() {
        return syllableSet.size();
    }

    /** 供测试：某个数字串对应的音节候选。 */
    public List<String> syllablesOf(String digits) {
        List<String> b = digitToSyllables.get(digits);
        if (b == null) return new ArrayList<String>();
        return new ArrayList<String>(b);
    }

    private static boolean isLowerLetters(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    // ---------- 九键键盘布局 ----------

    /** 九键每个键上要显示的字母。下标 0 对应数字 1，下标 8 对应数字 9。 */
    public static final String[] KEY_LETTERS = {"", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    /** 九键布局的数字顺序（3x4，最后一行是 * 0 #）。 */
    public static final char[] KEY_DIGITS = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '0'};
}
