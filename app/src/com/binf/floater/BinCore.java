package com.binf.floater;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 二进制/文本双向转换核心。
 *
 * 与 Windows 版 core.py 逻辑等价：
 *  - 编码使用 UTF-8
 *  - 解码依次尝试 UTF-8 / GBK / Big5 / ISO-8859-1
 *  - 支持空格、逗号、下划线、短横线、0b 前缀等分隔风格
 */
public final class BinCore {

    private BinCore() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    // 解码候选编码缓存成常量：Charset.forName 是同步查表，以前每次解码都要查一遍，
    // 而一次刷新最多会解码 4 次（UTF-8 / GBK / Big5 / ISO-8859-1）。
    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    /** 判定阈值：去掉分隔符后至少 16 位，且只含 0/1。 */
    public static final int MIN_BITS = 16;
    /**
     * 单次处理的位数上限。判定和解码共用同一个值 —— 以前只有判定有上限、
     * 解码没有，于是同一段超长 0/1 串「自动识别」判成文字、「手动切到解码」却能译，
     * 行为自相矛盾。
     */
    public static final int MAX_BITS = 20000;
    /** 解码结果里不可打印字符占比超过这个值，就认为多半解错了。 */
    public static final double MAX_CONTROL_RATIO = 0.3;

    /**
     * 从任意文本里提取纯 0/1 位串。
     *
     * 以前是两条正则（先去分隔符、再去 0b 前缀）。这两步本质都是「逐字符决定留不留」，
     * 换成普通循环完全等价，省掉两轮 Matcher 和两个中间字符串 —— 2 万位的串上，
     * 这一步原来要 0.8 ms，是自动识别里最大的一块。
     */
    public static String cleanBits(String text) {
        if (text == null) return "";
        String t = text.trim();
        // 快路径：已经是一串干净的 0/1 就直接返回（自动识别最常见的输入形态，
        // 以前这条路上也要走两遍正则、建两个中间字符串）。
        boolean pure = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '0' && c != '1') { pure = false; break; }
        }
        if (pure) return t;
        // 去分隔符。惰性分配：一个分隔符都没有就不复制。
        StringBuilder sb = null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (isSeparator(c)) {
                if (sb == null) sb = new StringBuilder(t.length()).append(t, 0, i);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        String noSep = sb == null ? t : sb.toString();
        // 分隔符去掉之后才认 0b，和原来「先 replaceAll 分隔符、再 replaceAll 0b」同序：
        // 输入 "0 b1" 仍然是 "0b1" → "1"。这一步同样惰性分配。
        StringBuilder out = null;
        for (int i = 0; i < noSep.length(); i++) {
            char c = noSep.charAt(i);
            if (c == '0' && i + 1 < noSep.length()) {
                char n = noSep.charAt(i + 1);
                if (n == 'b' || n == 'B') {
                    if (out == null) out = new StringBuilder(noSep.length()).append(noSep, 0, i);
                    i++;
                    continue;
                }
            }
            if (out != null) out.append(c);
        }
        return out == null ? noSep : out.toString();
    }

    /** 与正则 {@code \s} 同一套（Java 的 \s = 空格、\t、\n、\x0B、\f、\r）。 */
    private static boolean isSpaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** 与正则 {@code [\s,;:_\-|/.\\]+} 同一套字符。 */
    private static boolean isSeparator(char c) {
        if (isSpaceChar(c)) return true;
        switch (c) {
            case ',': case ';': case ':': case '_': case '-':
            case '|': case '/': case '.': case '\\':
                return true;
            default:
                return false;
        }
    }

    /** 是否“像二进制”：只由 0/1 和分隔符组成，位数 >= MIN_BITS。 */
    public static boolean looksLikeBinary(String text) {
        if (text == null) return false;
        String raw = text.trim();
        if (raw.isEmpty()) return false;
        String bits = cleanBits(raw);
        // 上限必须比**去掉分隔符后**的位数，和 binaryToText 用同一个口径。
        // 以前这里比的是 raw.length()（含空格），于是同一段带分组的超长位串
        // 「自动识别」判成文字、「手动切到解码」却能译，方向还会被判反。
        if (bits.length() > MAX_BITS) return false;
        if (bits.length() < MIN_BITS) return false;
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') return false;
        }
        // 短串必须同时包含 0 和 1，避免把 "1111111111111111" 之类误判。
        // 边界是 <=24：正好 24 位的纯 1 串以前会漏过检查被判成二进制。
        if (bits.length() <= 24 && !(bits.indexOf('0') >= 0 && bits.indexOf('1') >= 0)) {
            return false;
        }
        return true;
    }

    /** 位串转字节。末尾不足 8 位时在**右侧**补 0（bit 流从高位开始读）。 */
    public static byte[] bitsToBytes(String bits) {
        if (bits == null || bits.isEmpty()) return new byte[0];
        int len = bits.length();
        byte[] out = new byte[(len + 7) / 8];
        int acc = 0;
        int cnt = 0;
        int idx = 0;
        // 以前每 8 位 substring 一次再 Integer.parseInt(...,2)：2 万位就是 2500 个
        // 临时 String + 2500 次进制解析。按位左移累加，零分配。
        for (int i = 0; i < len; i++) {
            char c = bits.charAt(i);
            if (c == '1') acc = (acc << 1) | 1;
            else if (c == '0') acc <<= 1;
            else throw new NumberFormatException("不是 0/1 字符: " + c);
            if (++cnt == 8) {
                out[idx++] = (byte) acc;
                acc = 0;
                cnt = 0;
            }
        }
        if (cnt > 0) out[idx] = (byte) (acc << (8 - cnt));   // 末尾不足 8 位补 0
        return out;
    }

    /** 字节转文本，依次尝试多种编码。 */
    public static String bytesToText(byte[] data) {
        if (data == null || data.length == 0) return "";
        // 1) 严格 UTF-8：能过就一定是它，中文/emoji 都走这条。
        String utf8 = strictDecode(data, UTF8);
        if (utf8 != null) return utf8;
        // 2) 剩下的是 GBK 或 Big5。两者都是「对很多错误字节也能成功解码」的宽编码，
        //    单靠「谁先成功」会误判（Big5 正文常被 GBK 抢先解成乱码），所以两个都解、
        //    再按「像不像正常中文」打分选赢的那个。
        String gbk = strictDecode(data, GBK);
        String big5 = strictDecode(data, BIG5);
        if (gbk != null && big5 != null) {
            return scoreText(big5) > scoreText(gbk) ? big5 : gbk;
        }
        if (gbk != null) return gbk;
        if (big5 != null) return big5;
        // 3) 都不是——只能兜底。ISO-8859-1 对任意字节都成功，
        //    所以它必须留在最后，放前面会让上面所有分支失效。
        return new String(data, LATIN1);
    }

    /** 严格解码，解不出（或含不可映射字符）返回 null。 */
    private static String strictDecode(byte[] data, Charset cs) {
        try {
            java.nio.charset.CharsetDecoder dec = cs.newDecoder();
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 给一段解码结果打分：常用汉字、ASCII 可打印、常见标点加分；
     * 控制字符、私用区、生僻扩展区扣分。用来在 GBK / Big5 之间挑更像正文的那个，
     * 也用来判断「Base64 解出来的是不是正常文本」。
     */
    public static int scoreText(String s) {
        if (s == null || s.isEmpty()) return Integer.MIN_VALUE;
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // emoji 在 Java 里是代理对。以前两个代理各扣 2 分，一段纯 emoji
            // 的文本会被判成「不像正文」，Base64 解码直接被拒。
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, s.charAt(i + 1));
                    if (cp >= 0x1F300 && cp <= 0x1FAFF) score += 1;  // emoji
                    else if (cp >= 0x1D100 && cp <= 0x1D1FF) score += 1; // 音乐符号
                    else score -= 1;
                    i++;
                    continue;
                }
                score -= 2;
                continue;
            }
            if (Character.isLowSurrogate(c)) { score -= 2; continue; }
            if (c >= 0x20 && c < 0x7F) score += 2;              // ASCII 可打印
            else if (c >= 0x4E00 && c <= 0x9FFF) score += 3;    // 常用汉字区
            else if (c >= 0x3000 && c <= 0x303F) score += 2;    // 中日韩标点
            else if (c >= 0xFF00 && c <= 0xFFEF) score += 1;    // 全角符号
            else if (c >= 0x2600 && c <= 0x27BF) score += 1;    // 杂项符号 / 装饰
            else if (c == '\n' || c == '\t' || c == '\r') score += 1;
            else if (c < 0x20) score -= 10;                     // 控制字符
            else if (c >= 0xE000 && c <= 0xF8FF) score -= 8;    // 私用区
            else if (c >= 0x3400 && c <= 0x4DBF) score -= 1;    // 扩展 A 生僻字
            else score -= 2;                                    // 其它可疑字符
        }
        return score;
    }

    /** 解码结果。 */
    public static final class DecodeResult {
        public final String text;     // 解码出的文字，失败为 null
        public final String message;  // 给用户看的说明
        public final boolean ok;
        public final int bytes;
        public final int bits;

        DecodeResult(String text, String message, boolean ok, int bytes, int bits) {
            this.text = text;
            this.message = message;
            this.ok = ok;
            this.bytes = bytes;
            this.bits = bits;
        }
    }

    /** 二进制文本 -> 文字。 */
    public static DecodeResult binaryToText(String input) {
        if (input == null || input.trim().isEmpty()) {
            return new DecodeResult(null, "内容是空的", false, 0, 0);
        }
        String bits = cleanBits(input);
        if (bits.isEmpty()) {
            return new DecodeResult(null, "没有可识别的 0/1 位串", false, 0, 0);
        }
        // 和 looksLikeBinary 用同一个上限，别再出现「自动识别不认、手动解码能译」。
        if (bits.length() > MAX_BITS) {
            return new DecodeResult(null, "内容过长（" + bits.length()
                    + " 位），最多支持 " + MAX_BITS + " 位", false, 0, bits.length());
        }
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') {
                return new DecodeResult(null, "包含非二进制字符：" + c, false, 0, 0);
            }
        }
        int n = bits.length();
        if (n < 8) {
            return new DecodeResult(null, "位数不足 8 位（" + n + " 位）", false, 0, n);
        }
        int rest = n % 8;
        byte[] data = bitsToBytes(bits);
        String text = bytesToText(data);
        // 解出一堆控制字符说明多半解错了（比如把一段纯 1 当二进制）。
        // 以前 00000000 会译出 NUL 显示成空白，状态行还报「已解码 1 字节」。
        int ctrl = countControls(text);
        if (!text.isEmpty() && ctrl > text.length() * MAX_CONTROL_RATIO) {
            // 位数不是 8 的倍数时，补 0 本身就可能造出假的 0x00 字节，
            // 所以先把「补 0」这件事说清楚，再报控制字符。
            String padNote = rest == 0 ? ""
                    : "（位数 " + n + " 不是 8 的倍数，末尾 " + rest + " 位已补 0）";
            return new DecodeResult(null, "解出 " + ctrl + " 个不可打印字符（共 "
                    + text.length() + " 个），这段可能不是二进制" + padNote
                    + "。十六进制：" + previewHex(data, 24), false, data.length, n);
        }
        String msg = rest == 0
                ? "已解码 " + data.length + " 字节 / " + n + " 位"
                : "位数 " + n + " 不是 8 的倍数，末尾 " + rest + " 位已补 0";
        return new DecodeResult(text, msg, true, data.length, n);
    }

    /** 统计控制字符个数（换行/制表/回车不算）。 */
    public static int countControls(String s) {
        if (s == null) return 0;
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') c++;
        }
        return c;
    }

    /** 大写十六进制字符表。以前每字节调一次 String.format("%02X")，2 万位要分配 2500 个
     *  Formatter —— 这一步是整条转换链里最慢的（基准里 6.6 ms），换成查表后降到零点几。 */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static void appendHex(StringBuilder sb, byte b) {
        int v = b & 0xFF;
        sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
    }

    /** 字节 -> 十六进制串（完整，不截断）。 */
    private static String textToHexBytes(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            appendHex(sb, data[i]);
        }
        return sb.toString();
    }

    /**
     * 字节 -> 十六进制串的**预览**（只给错误提示用，最多 limit 字节）。
     *
     * 以前 textToHex / bitsToHex 都复用了带 24 字节上限的预览版，
     * 于是「HEX 显示模式」和「复制 HEX」静默丢掉 24 字节之后的所有内容。
     * 现在截断只留在这个专用方法里，公开 API 一律完整。
     */
    public static String previewHex(byte[] data, int limit) {
        if (data == null || data.length == 0) return "";
        int n = Math.min(data.length, Math.max(limit, 0));
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            appendHex(sb, data[i]);
        }
        if (data.length > n) sb.append(" …");
        return sb.toString();
    }

    /** 256 项字节→8 位二进制查表。比每次 Integer.toBinaryString + 补零循环快一截。 */
    private static final String[] BYTE_BITS = new String[256];

    static {
        for (int i = 0; i < 256; i++) {
            String b = Integer.toBinaryString(i);
            StringBuilder sb = new StringBuilder(8);
            for (int k = b.length(); k < 8; k++) sb.append('0');
            BYTE_BITS[i] = sb.append(b).toString();
        }
    }

    /** 文字 -> 二进制。compact=true 时去掉空格。 */
    public static String textToBinary(String text, boolean compact) {
        if (text == null || text.isEmpty()) return "";
        byte[] data = text.getBytes(UTF8);
        StringBuilder sb = new StringBuilder(data.length * 9);
        for (int i = 0; i < data.length; i++) {
            sb.append(BYTE_BITS[data[i] & 0xFF]);
            if (!compact && i < data.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    /** 文字 -> 十六进制，便于核对。 */
    public static String textToHex(String text) {
        if (text == null || text.isEmpty()) return "";
        return textToHexBytes(text.getBytes(UTF8));
    }

    /**
     * "N 字符 · M 字节" 描述。
     *
     * 这里的 getBytes 看着浪费（只为拿一个长度就编码一遍），但实测比逐字符数
     * 快一倍多（4000 字：0.013 ms vs 0.031 ms）—— JDK 的 UTF-8 编码是向量化的，
     * 逐字符分支反而更慢。所以「少分配」不等于「更快」，这个保持原样。
     */
    public static String describe(String text) {
        if (text == null) text = "";
        return text.length() + " 字符 · " + text.getBytes(UTF8).length + " 字节";
    }


    // ---------- Base64 ----------

    /** Base64 正文里允许出现的字符（标准 + URL 安全两套字母表）。 */
    private static boolean isB64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
    }

    /** 去掉所有空白（与 {@code replaceAll("[\s]+", "")} 等价，但不编译正则）。 */
    private static String stripSpaces(String s) {
        int i = 0;
        while (i < s.length() && !isSpaceChar(s.charAt(i))) i++;
        if (i == s.length()) return s;          // 没有空白，原样返回，不复制
        StringBuilder sb = new StringBuilder(s.length()).append(s, 0, i);
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isSpaceChar(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** 文字 -> Base64（UTF-8 编码后转）。 */
    public static String textToBase64(String text) {
        if (text == null || text.isEmpty()) return "";
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(UTF8));
    }

    /** 位串 -> Base64：先按位还原成字节，再编码。 */
    public static String bitsToBase64(String bits) {
        if (bits == null || bits.isEmpty()) return "";
        return java.util.Base64.getEncoder().encodeToString(bitsToBytes(cleanBits(bits)));
    }

    /** 去掉空白并把 URL 安全字符换回标准字符，再补齐 padding。 */
    static String normalizeBase64(String s) {
        if (s == null) return "";
        // 一遍扫描完成「去空白 + URL 安全字符换回标准字符」。
        // 以前是 replaceAll + 两次 replace + 一个 substring 循环（每去掉一个 '=' 就复制一次整串）。
        String base = s;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpaceChar(c) || c == '-' || c == '_') {
                // 慢路径：确实要改写，才复制一遍
                StringBuilder sb = new StringBuilder(s.length() + 3);
                for (int k = 0; k < s.length(); k++) {
                    char d = s.charAt(k);
                    if (isSpaceChar(d)) continue;
                    if (d == '-') d = '+';
                    else if (d == '_') d = '/';
                    sb.append(d);
                }
                base = sb.toString();
                break;
            }
        }
        int len = base.length();
        while (len > 0 && base.charAt(len - 1) == '=') len--;
        int pad = (4 - len % 4) % 4;
        if (len == base.length() && pad == 0) return base;   // 不用改，直接复用
        StringBuilder out = new StringBuilder(len + pad).append(base, 0, len);
        for (int i = 0; i < pad; i++) out.append('=');
        return out.toString();
    }

    /** Base64 -> 字节。解不出返回 null（不做「尽力而为」的猜测）。 */
    public static byte[] base64ToBytes(String text) {
        if (text == null) return null;
        String raw = stripSpaces(text);
        if (raw.isEmpty()) return null;
        for (int i = 0; i < raw.length(); i++) {
            if (!isB64Char(raw.charAt(i))) return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(normalizeBase64(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 是否「像一段 Base64」。
     *
     * 这里刻意保守：Base64 的字母表就是普通英文字母，任何 4 个字母都能「解」出
     * 3 个字节，所以光看字符集一定误判。真正的判据是**解出来的东西像不像文本**：
     * 必须能严格按 UTF-8 解出、控制字符占比低、打分不为负。
     * 例："test" 解出 B5 EB 2D，不是合法 UTF-8 → 判成普通文字。
     *
     * 判定的口径必须和解码完全一致，否则会出现「自动识别判成 Base64、
     * 手动解码却报错」的自相矛盾：
     *  - padding 用补齐后的长度算（base64ToBytes 会自动补），
     *    于是 "SGVsbG8"（7 字符、无 padding）自动路径也认得出来；
     *  - 长度上限和 base64ToText 一样卡 MAX_BITS/8 字节，超长的直接不判成 Base64；
     *  - 只解一次，解出来的字节复用给后面的判断（以前 probe 解一遍、正式再解一遍，
     *    长串在主线程上白解两遍）。
     */
    public static boolean looksLikeBase64(String text) {
        if (text == null) return false;
        String raw = text.trim();
        if (raw.indexOf('\n') >= 0) return false;
        // 一遍扫描同时判断「字符集合不合法」和「是不是纯字母」，替掉两次正则 matches()。
        boolean allLetters = true;
        boolean hasPlusOrEq = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isB64Char(c)) return false;
            if (c == '+' || c == '=') hasPlusOrEq = true;
            else if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) allLetters = false;
        }
        // 和 base64ToBytes 同一个口径：normalizeBase64 会补 padding，所以这里
        // 比的应该是「补齐后」的长度，不是原始长度。
        // 最短门槛也按补齐后算：7 个字符的 "SGVsbG8" 补齐后是 8，能解得出来，
        // 按原始长度卡 8 就把它漏掉了。
        String normalized = normalizeBase64(raw);
        if (normalized.length() < 8) return false;
        if (normalized.length() % 4 != 0) return false;
        // 解出来的字节数不能超过解码上限，否则自动识别会把一段根本解不动的
        // 长串判成 Base64，随后 refresh 只报「内容过长」。
        if (normalized.length() / 4 * 3 > MAX_BITS / 8) return false;
        // 只解这一次。以前「纯字母」那条分支会先探一次解码、紧接着正式再解一次，
        // 长串等于在主线程上白解两遍。
        byte[] data = base64ToBytes(raw);
        if (data == null || data.length == 0) return false;
        String t = strictDecode(data, UTF8);
        // 纯字母且没有 +/= 的串多半是英文单词，解不出合法 UTF-8 就不冒这个险。
        if (allLetters && !hasPlusOrEq && t == null) return false;
        if (t == null) return false;
        if (t.isEmpty()) return false;
        if (countControls(t) > t.length() * MAX_CONTROL_RATIO) return false;
        return scoreText(t) > 0;
    }

    /** Base64 文本 -> 文字。 */
    public static DecodeResult base64ToText(String input) {
        if (input == null || input.trim().isEmpty()) {
            return new DecodeResult(null, "内容是空的", false, 0, 0);
        }
        byte[] data = base64ToBytes(input);
        if (data == null) {
            return new DecodeResult(null, "不是合法的 Base64（含非法字符或长度不对）", false, 0, 0);
        }
        if (data.length == 0) {
            return new DecodeResult(null, "Base64 解出来是空的", false, 0, 0);
        }
        if (data.length > MAX_BITS / 8) {
            return new DecodeResult(null, "内容过长（" + data.length + " 字节），最多支持 "
                    + (MAX_BITS / 8) + " 字节", false, data.length, 0);
        }
        String text = bytesToText(data);
        int ctrl = countControls(text);
        if (!text.isEmpty() && ctrl > text.length() * MAX_CONTROL_RATIO) {
            return new DecodeResult(null, "解出 " + ctrl + " 个不可打印字符（共 "
                    + text.length() + " 个），这段可能不是 Base64。十六进制："
                    + previewHex(data, 24), false, data.length, 0);
        }
        return new DecodeResult(text, "已解码 " + data.length + " 字节（Base64）",
                true, data.length, 0);
    }

    /** 自动判定方向。 */
    public static String autoDetect(String text) {
        if (looksLikeBinary(text)) return "bin2text";
        if (looksLikeBase64(text)) return "b64text";
        if (text != null && !text.trim().isEmpty()) return "text2bin";
        return "unknown";
    }

    /** 把长位串按 group 位分组，方便阅读。group <= 0 时直接返回原串，不再死循环。 */
    public static String groupBits(String bits, int group, String sep) {
        if (bits == null || bits.isEmpty() || group <= 0) return bits == null ? "" : bits;
        // 预先算好最终长度，省掉 StringBuilder 的多次扩容（2 万位分组后约 2.2 万字符）。
        int groups = (bits.length() + group - 1) / group;
        int cap = bits.length() + Math.max(0, groups - 1) * (sep == null ? 0 : sep.length());
        StringBuilder sb = new StringBuilder(cap);
        for (int i = 0; i < bits.length(); i += group) {
            if (i > 0) sb.append(sep);
            sb.append(bits, i, Math.min(i + group, bits.length()));
        }
        return sb.toString();
    }

    /** 分页切片，供界面分段显示超大位串。size <= 0 时返回整串单页。 */
    public static List<String> chunk(String s, int size) {
        List<String> out = new ArrayList<String>();
        if (s == null || s.isEmpty()) return out;
        if (size <= 0) {
            out.add(s);
            return out;
        }
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(i + size, s.length())));
        }
        return out;
    }

    /**
     * 显示模式：把位串/文本按用户选的形式呈现。
     * 这些方法以前都写好了却没人调用，界面上也就没有「分组/连写/十六进制」可选。
     */
    public static String renderBits(String bits, String mode) {
        if (bits == null) return "";
        if (mode == null) return groupBits(bits, 8, " ");
        switch (mode) {
            case "compact": return bits;
            case "group4":  return groupBits(bits, 4, " ");
            case "hex":     return bitsToHex(bits);
            case "base64":  return bitsToBase64(bits);
            case "group8":
            default:        return groupBits(bits, 8, " ");
        }
    }

    /** 位串 -> 十六进制（不足 8 位补零后按字节输出）。 */
    public static String bitsToHex(String bits) {
        if (bits == null || bits.isEmpty()) return "";
        return textToHexBytes(bitsToBytes(cleanBits(bits)));
    }
}