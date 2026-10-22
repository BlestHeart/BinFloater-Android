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
        if (raw.isEmpty() || raw.length() > 20000) return false;
        String bits = cleanBits(raw);
        if (bits.length() < MIN_BITS) return false;
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') return false;
        }
        // 短串必须同时包含 0 和 1，避免把 "1111111111111111" 之类误判
        if (bits.length() < 24 && !(bits.indexOf('0') >= 0 && bits.indexOf('1') >= 0)) {
            return false;
        }
        return true;
    }

    /** 位串转字节，末尾不足 8 位左侧补 0。 */
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
        for (String enc : new String[]{"UTF-8", "GBK", "Big5", "ISO-8859-1"}) {
            try {
                Charset cs = Charset.forName(enc);
                java.nio.charset.CharsetDecoder dec = cs.newDecoder();
                dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            } catch (Exception ignored) {
            }
        }
        return new String(data, UTF8);
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
        String msg = rest == 0
                ? "已解码 " + data.length + " 字节 / " + n + " 位"
                : "位数 " + n + " 不是 8 的倍数，末尾 " + rest + " 位已补 0";
        return new DecodeResult(text, msg, true, data.length, n);
    }

    /** 文字 -> 二进制。compact=true 时去掉空格。 */
    public static String textToBinary(String text, boolean compact) {
        if (text == null || text.isEmpty()) return "";
        byte[] data = text.getBytes(UTF8);
        StringBuilder sb = new StringBuilder(data.length * 9);
        for (int i = 0; i < data.length; i++) {
            String b = Integer.toBinaryString(data[i] & 0xFF);
            for (int k = b.length(); k < 8; k++) sb.append('0');
            sb.append(b);
            if (!compact && i < data.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    /** 文字 -> 十六进制，便于核对。 */
    public static String textToHex(String text) {
        if (text == null || text.isEmpty()) return "";
        byte[] data = text.getBytes(UTF8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /** "N 字符 · M 字节" 描述。 */
    public static String describe(String text) {
        if (text == null) text = "";
        return text.length() + " 字符 · " + text.getBytes(UTF8).length + " 字节";
    }

    /** 自动判定方向。 */
    public static String autoDetect(String text) {
        if (looksLikeBinary(text)) return "bin2text";
        if (text != null && !text.trim().isEmpty()) return "text2bin";
        return "unknown";
    }

    /** 把长位串按 8 位分组，方便阅读。 */
    public static String groupBits(String bits, int group, String sep) {
        if (bits == null || bits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length(); i += group) {
            if (i > 0) sb.append(sep);
            sb.append(bits, i, Math.min(i + group, bits.length()));
        }
        return sb.toString();
    }

    /** 分页切片，供界面分段显示超大位串。 */
    public static List<String> chunk(String s, int size) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(i + size, s.length())));
        }
        return out;
    }
}
