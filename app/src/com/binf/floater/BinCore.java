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
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,;:_\\-|/.\\\\]+");
    private static final Pattern ZERO_B = Pattern.compile("(?i)0b");

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

    /** 从任意文本里提取纯 0/1 位串。 */
    public static String cleanBits(String text) {
        if (text == null) return "";
        String t = text.trim();
        t = SEPARATOR.matcher(t).replaceAll("");
        t = ZERO_B.matcher(t).replaceAll("");
        return t;
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
        int pad = (8 - bits.length() % 8) % 8;
        StringBuilder sb = new StringBuilder(bits);
        for (int i = 0; i < pad; i++) sb.append('0');
        int n = sb.length() / 8;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(sb.substring(i * 8, i * 8 + 8), 2);
        }
        return out;
    }

    /** 字节转文本，依次尝试多种编码。 */
    public static String bytesToText(byte[] data) {
        if (data == null || data.length == 0) return "";
        // 1) 严格 UTF-8：能过就一定是它，中文/emoji 都走这条。
        String utf8 = strictDecode(data, "UTF-8");
        if (utf8 != null) return utf8;
        // 2) 剩下的是 GBK 或 Big5。两者都是「对很多错误字节也能成功解码」的宽编码，
        //    单靠「谁先成功」会误判（Big5 正文常被 GBK 抢先解成乱码），所以两个都解、
        //    再按「像不像正常中文」打分选赢的那个。
        String gbk = strictDecode(data, "GBK");
        String big5 = strictDecode(data, "Big5");
        if (gbk != null && big5 != null) {
            return scoreText(big5) > scoreText(gbk) ? big5 : gbk;
        }
        if (gbk != null) return gbk;
        if (big5 != null) return big5;
        // 3) 都不是——只能兜底。ISO-8859-1 对任意字节都成功，
        //    所以它必须留在最后，放前面会让上面所有分支失效。
        return new String(data, Charset.forName("ISO-8859-1"));
    }

    /** 严格解码，解不出（或含不可映射字符）返回 null。 */
    private static String strictDecode(byte[] data, String enc) {
        try {
            Charset cs = Charset.forName(enc);
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

    /** 字节 -> 十六进制串（完整，不截断）。 */
    private static String textToHexBytes(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
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
            sb.append(String.format("%02X", data[i] & 0xFF));
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

    /** "N 字符 · M 字节" 描述。 */
    public static String describe(String text) {
        if (text == null) text = "";
        return text.length() + " 字符 · " + text.getBytes(UTF8).length + " 字节";
    }

    // ---------- Base64 ----------

    /** Base64 正文里允许出现的字符（标准 + URL 安全两套字母表）。 */
    private static final Pattern B64_CHARS = Pattern.compile("[A-Za-z0-9+/=_-]+");

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
        String t = s.replaceAll("[\\s]+", "");
        t = t.replace('-', '+').replace('_', '/');
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        int pad = (4 - t.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < pad; i++) sb.append('=');
        return sb.toString();
    }

    /** Base64 -> 字节。解不出返回 null（不做「尽力而为」的猜测）。 */
    public static byte[] base64ToBytes(String text) {
        if (text == null) return null;
        String raw = text.replaceAll("[\\s]+", "");
        if (raw.isEmpty()) return null;
        if (!B64_CHARS.matcher(raw).matches()) return null;
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
     */
    public static boolean looksLikeBase64(String text) {
        if (text == null) return false;
        String raw = text.trim();
        if (raw.length() < 8) return false;
        if (raw.indexOf('\n') >= 0) return false;
        if (!B64_CHARS.matcher(raw).matches()) return false;
        String stripped = raw.replaceAll("=", "");
        // 长度必须是 4 的倍数（或至少去掉 padding 后能补齐）
        if (raw.length() % 4 != 0) return false;
        // 纯字母且没有任何数字/+//= 的串，多半是英文单词，不冒这个险
        if (stripped.matches("[A-Za-z]+") && !raw.matches(".*[+=].*")) {
            byte[] probe = base64ToBytes(raw);
            String t = probe == null ? null : strictDecode(probe, "UTF-8");
            if (t == null) return false;
        }
        byte[] data = base64ToBytes(raw);
        if (data == null || data.length == 0) return false;
        String t = strictDecode(data, "UTF-8");
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
        StringBuilder sb = new StringBuilder();
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
