package com.binf.ime;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 拼音组字状态机：维护当前输入的拼音串，产出候选词。
 *
 * 支持：
 *  - 逐字母输入，实时前缀候选
 *  - 分音节切分（最多 3 段），用于 "nihao" -> "ni"+"hao"
 *  - 选中候选后清空
 */
public final class PinyinComposer {

    private final PinyinEngine engine;
    private final StringBuilder raw = new StringBuilder();

    public PinyinComposer(Context ctx) {
        this.engine = PinyinEngine.get(ctx);
    }

    public boolean isEmpty() {
        return raw.length() == 0;
    }

    public String rawText() {
        return raw.toString();
    }

    public void append(char c) {
        if (c >= 'a' && c <= 'z') {
            raw.append(c);
        }
    }

    public void delete() {
        if (raw.length() > 0) {
            raw.deleteCharAt(raw.length() - 1);
        }
    }

    public void reset() {
        raw.setLength(0);
    }

    /** 当前输入的拼音串（可能是缩写）。 */
    public String composing() {
        return raw.toString();
    }

    /**
     * 产出候选列表。
     * 策略：
     *   1. 整串前缀匹配（覆盖 nihao -> 你好）
     *   2. 若整串无结果，尝试按音节切分后再查
     */
    public List<PinyinEngine.Candidate> candidates(int limit) {
        List<PinyinEngine.Candidate> out = new ArrayList<PinyinEngine.Candidate>();
        if (raw.length() == 0) return out;

        String s = raw.toString();

        // 1) 整串前缀
        out.addAll(engine.query(s, limit));

        // 2) 音节切分：把 s 切成若干已知音节，拼出词
        if (out.size() < 3) {
            List<String> seg = segment(s);
            if (seg.size() > 1) {
                // 逐段取最高权重候选，组合成词，给低一点的排名
                StringBuilder word = new StringBuilder();
                boolean ok = true;
                int minW = Integer.MAX_VALUE;
                for (String p : seg) {
                    List<PinyinEngine.Candidate> cs = engine.exact(p, 1);
                    if (cs.isEmpty()) { ok = false; break; }
                    word.append(cs.get(0).text);
                    minW = Math.min(minW, cs.get(0).weight);
                }
                if (ok && word.length() > 0) {
                    boolean dup = false;
                    for (PinyinEngine.Candidate c : out) {
                        if (c.text.equals(word.toString())) { dup = true; break; }
                    }
                    if (!dup) {
                        out.add(new PinyinEngine.Candidate(word.toString(), minW - 1));
                    }
                }
            }
        }

        if (out.size() > limit) out = new ArrayList<PinyinEngine.Candidate>(out.subList(0, limit));
        return out;
    }

    /**
     * 把拼音串切成音节。贪心 + 最长匹配。
     * 只用于兜底，切不准也没关系。
     */
    static List<String> segment(String s) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < s.length()) {
            int take = -1;
            for (int len = Math.min(6, s.length() - i); len >= 1; len--) {
                if (isSyllable(s.substring(i, i + len))) { take = len; break; }
            }
            if (take < 0) {
                out.clear();
                return out;
            }
            out.add(s.substring(i, i + take));
            i += take;
        }
        return out;
    }

    private static final String[] INITIALS = {
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
    };

    private static final String[] FINALS = {
            "iang", "iong", "uang", "ueng", "ang", "eng", "ing", "ong", "ian", "iao",
            "uai", "uan", "van", "van", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve",
            "er", "an", "en", "in", "un", "vn", "ia", "ua", "uo", "ue", "a", "o", "e",
            "i", "u", "v"
    };

    /** 粗略判断是否是合法音节。 */
    static boolean isSyllable(String s) {
        if (s == null || s.isEmpty() || s.length() > 6) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        for (String ini : INITIALS) {
            if (s.startsWith(ini)) {
                String rest = s.substring(ini.length());
                if (rest.isEmpty()) return true;
                for (String fin : FINALS) {
                    if (rest.equals(fin)) return true;
                }
                return false;
            }
        }
        for (String fin : FINALS) {
            if (s.equals(fin)) return true;
        }
        return false;
    }

    /** 直接选第一个候选。 */
    public String pickFirst(int limit) {
        List<PinyinEngine.Candidate> cs = candidates(limit);
        return cs.isEmpty() ? null : cs.get(0).text;
    }

    /** 引擎规模，供设置页显示。 */
    public int dictSize() {
        return engine.size();
    }
}
