import com.binf.floater.BinCore;
import com.binf.floater.VersionUtil;

/** BinCore 自测：不依赖 Android，可直接用 java 运行。 */
public class CoreTest {
    static int pass = 0, fail = 0;

    static void eq(String label, Object actual, Object expect) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) { pass++; System.out.println("  PASS  " + label); }
        else { fail++; System.out.println("  FAIL  " + label + "\n        期望: " + expect + "\n        实际: " + actual); }
    }

    static void check(String label, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + label); }
        else { fail++; System.out.println("  FAIL  " + label); }
    }

    public static void main(String[] args) {
        System.out.println("== 1. 位串形式识别 ==");
        eq("空格分隔", BinCore.binaryToText("01001000 01101001").text, "Hi");
        eq("完全连写", BinCore.binaryToText("0100100001101001").text, "Hi");
        eq("0b 前缀+下划线", BinCore.binaryToText("0b01001000_0b01101001").text, "Hi");
        eq("短横线分隔", BinCore.binaryToText("0100-1000 0110-1001").text, "Hi");
        eq("逗号分隔", BinCore.binaryToText("01001000,01101001").text, "Hi");

        System.out.println("== 2. 中文与 emoji 往返 ==");
        String[] samples = {"你好世界", "自动化测试 ✅", "Hello 世界 🙂", "混合 mixed 文本 123", "！@#￥%……&*（）"};
        for (String s : samples) {
            String bin = BinCore.textToBinary(s, false);
            BinCore.DecodeResult r = BinCore.binaryToText(bin);
            eq("往返 [" + s + "]", r.text, s);
            check("  判定为二进制", BinCore.looksLikeBinary(bin));
        }

        System.out.println("== 3. 紧凑模式 ==");
        String spaced = BinCore.textToBinary("Hi", false);
        String compact = BinCore.textToBinary("Hi", true);
        eq("空格模式", spaced, "01001000 01101001");
        eq("紧凑模式", compact, "0100100001101001");
        eq("紧凑也能解回", BinCore.binaryToText(compact).text, "Hi");

        System.out.println("== 4. 误判防护 ==");
        check("2024 不误判", !BinCore.looksLikeBinary("2024"));
        check("短 0/1 串不误判", !BinCore.looksLikeBinary("0101"));
        check("普通中文不误判", !BinCore.looksLikeBinary("这是一段普通文字"));
        check("全 1 长串不误判", !BinCore.looksLikeBinary("1111111111111111"));
        check("正常二进制能识别", BinCore.looksLikeBinary("0100100001101001"));
        check("Hello 不误判", !BinCore.looksLikeBinary("Hello world"));

        System.out.println("== 5. 边界情况 ==");
        check("空输入失败", !BinCore.binaryToText("").ok);
        check("null 失败", !BinCore.binaryToText(null).ok);
        check("不足 8 位失败", !BinCore.binaryToText("0101").ok);
        eq("空文字转二进制", BinCore.textToBinary("", false), "");
        BinCore.DecodeResult odd = BinCore.binaryToText("010010000");  // 9 位
        check("9 位给出补零提示", odd.message.contains("补 0"));

        System.out.println("== 6. hex 与描述 ==");
        eq("hex", BinCore.textToHex("Hi"), "48 69");
        eq("describe", BinCore.describe("Hi"), "2 字符 · 2 字节");
        eq("中文 describe", BinCore.describe("你好"), "2 字符 · 6 字节");

        System.out.println("== 6b. hex 不再被 24 字节预览上限截断 ==");
        // 以前 textToHex / bitsToHex 复用 textToHexBytes，里面写死 Math.min(len,24)，
        // 于是 HEX 显示模式和「复制」都静默丢掉第 25 字节之后的内容。
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) longText.append('A');
        String hex40 = BinCore.textToHex(longText.toString());
        eq("40 字节 hex 长度", hex40.split(" ").length, 40);
        check("40 字节 hex 无省略号", !hex40.contains("…"));
        eq("40 字节 hex 首尾", hex40.substring(0, 2) + "…" + hex40.substring(hex40.length() - 2),
                "41…41");
        String bits40 = BinCore.textToBinary(longText.toString(), true);
        String hexFromBits = BinCore.bitsToHex(bits40);
        eq("bitsToHex 也是 40 字节", hexFromBits.split(" ").length, 40);
        eq("两条路径结果一致", hexFromBits, hex40);
        // 预览版仍然要截断（错误提示用，别把几百 KB 全打出来）
        check("previewHex 会截断", BinCore.previewHex(new byte[100], 24).contains("…"));
        eq("previewHex 24 字节", BinCore.previewHex(new byte[100], 24).split(" ").length, 25);

        System.out.println("== 6c. 判定与解码的长度上限口径一致 ==");
        // 带分隔符时 raw.length() 远大于 bits.length()。以前 looksLikeBinary 比 raw，
        // binaryToText 比 bits，于是同一段串「自动识别」说不是二进制、「手动解码」却能译。
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < 2500; i++) { if (i > 0) grouped.append(' '); grouped.append("01001000"); }
        String groupedBits = grouped.toString();
        check("带分组串 raw 长度 > MAX_BITS", groupedBits.length() > BinCore.MAX_BITS);
        eq("cleanBits 后正好到上限", BinCore.cleanBits(groupedBits).length(), BinCore.MAX_BITS);
        check("到上限仍判定为二进制", BinCore.looksLikeBinary(groupedBits));
        check("到上限仍能解码", BinCore.binaryToText(groupedBits).ok);
        // 多一位就越界，两边必须同时拒绝
        String over = groupedBits + " 0";
        check("超上限判定拒绝", !BinCore.looksLikeBinary(over));
        check("超上限解码拒绝", !BinCore.binaryToText(over).ok);
        eq("autoDetect 与解码方向一致", BinCore.autoDetect(groupedBits), "bin2text");

        System.out.println("== 6d. 短串边界 ==");
        // 24 位纯 1 串曾经漏过检查被判成二进制
        check("24 位纯 1 不误判", !BinCore.looksLikeBinary("111111111111111111111111"));
        check("25 位纯 1 判为二进制", BinCore.looksLikeBinary("1111111111111111111111111"));

        System.out.println("== 7. 自动判定 ==");
        eq("autoDetect 二进制", BinCore.autoDetect("0100100001101001"), "bin2text");
        eq("autoDetect 文字", BinCore.autoDetect("你好"), "text2bin");
        eq("autoDetect 空", BinCore.autoDetect(""), "unknown");

        System.out.println("== 8. 分组 ==");
        eq("八位分组", BinCore.groupBits("0100100001101001", 8, " "), "01001000 01101001");
        eq("四位分组", BinCore.groupBits("0100100001101001", 4, "-"), "0100-1000-0110-1001");

        System.out.println("== 9. Base64 编解码 ==");
        eq("ASCII 编码", BinCore.textToBase64("Hi"), "SGk=");
        eq("中文编码", BinCore.textToBase64("你好"), "5L2g5aW9");
        eq("空串编码", BinCore.textToBase64(""), "");
        eq("null 编码", BinCore.textToBase64(null), "");
        eq("ASCII 解码", BinCore.base64ToText("SGk=").text, "Hi");
        eq("中文解码", BinCore.base64ToText("5L2g5aW9").text, "你好");
        eq("emoji 解码", BinCore.base64ToText("8J+Zgg==").text, "🙂");
        check("解码成功标记", BinCore.base64ToText("SGk=").ok);
        check("解码消息含字节数", BinCore.base64ToText("SGk=").message.contains("2 字节"));

        System.out.println("== 9b. Base64 往返 ==");
        String[] b64samples = {"你好世界", "Hello 世界 🙂", "a", "混合 mixed 123", "！@#￥%…"};
        for (String s : b64samples) {
            String enc = BinCore.textToBase64(s);
            eq("往返 [" + s + "]", BinCore.base64ToText(enc).text, s);
        }

        System.out.println("== 9c. Base64 容错 ==");
        // 缺 padding：很多地方（JWT、URL）不写 =，要能补齐
        eq("缺 padding 能解", BinCore.base64ToText("SGk").text, "Hi");
        // 带空白/换行：从文档里复制常带换行
        eq("带换行能解", BinCore.base64ToText("SGk=\n").text, "Hi");
        eq("带空格能解", BinCore.base64ToText("5L2g 5aW9").text, "你好");
        // URL 安全字母表：- 和 _ 要换回 + 和 /
        eq("URL 安全字母表", BinCore.base64ToText("8J-Zgg==").text, "🙂");
        check("非法字符被拒", !BinCore.base64ToText("SGk=!!").ok);
        check("空输入被拒", !BinCore.base64ToText("").ok);
        check("null 被拒", !BinCore.base64ToText(null).ok);

        System.out.println("== 9d. Base64 判定不误伤 ==");
        // 这是 Base64 判定的核心难点：字母表就是普通英文，
        // 任何 4 个字母都能「解」出 3 个字节。判据必须是「解出来的像不像文本」。
        check("test 不判为 Base64", !BinCore.looksLikeBase64("test"));
        check("HelloWorld 不判为 Base64", !BinCore.looksLikeBase64("HelloWorld"));
        check("普通中文不判为 Base64", !BinCore.looksLikeBase64("这是一段普通文字"));
        check("二进制串不判为 Base64", !BinCore.looksLikeBase64("0100100001101001"));
        check("短串不判为 Base64", !BinCore.looksLikeBase64("SGk="));
        check("中文 Base64 能判定", BinCore.looksLikeBase64("5L2g5aW9"));
        check("emoji Base64 能判定", BinCore.looksLikeBase64("8J+Zgg=="));
        eq("autoDetect Base64", BinCore.autoDetect("5L2g5aW9"), "b64text");
        eq("autoDetect 仍优先二进制", BinCore.autoDetect("0100100001101001"), "bin2text");
        // 判定口径必须和解码一致：解码会自动补 padding，判定却按原始长度卡 4 的倍数，
        // 于是 "SGVsbG8"（7 字符）自动识别认不出、手动解码又能解 —— 自相矛盾。
        eq("autoDetect 认得无 padding 的 Base64", BinCore.autoDetect("SGVsbG8"), "b64text");
        check("无 padding 的 Base64 能判定", BinCore.looksLikeBase64("SGVsbG8"));
        // 判定和解码的上限也要一致：超过解码上限的长串不该被判成 Base64，
        // 否则自动识别把方向设成 b64text，随后 refresh 只报「内容过长」。
        StringBuilder hugeB64 = new StringBuilder();
        for (int i = 0; i < 3000; i++) hugeB64.append("QUJD"); // 4000 字符 = 3000 字节
        check("超长 Base64 不判为 Base64", !BinCore.looksLikeBase64(hugeB64.toString()));

        System.out.println("== 9e. 位串 -> Base64 ==");
        eq("位串转 Base64", BinCore.bitsToBase64("0100100001101001"), "SGk=");
        eq("位串带空格转 Base64", BinCore.bitsToBase64("01001000 01101001"), "SGk=");
        eq("renderBits base64", BinCore.renderBits("0100100001101001", "base64"), "SGk=");
        eq("renderBits 未知模式兜底",
                BinCore.renderBits("0100100001101001", "nonsense"), "01001000 01101001");

        System.out.println("== 10. 版本号比较（检查更新用） ==");
        // Release 的 tag 是 v2.4 这种，只有 versionName 没有 versionCode，
        // 所以必须能比字符串版本号，而且不能按字典序比（"2.10" < "2.9" 是错的）。
        check("2.5 比 2.4 新", VersionUtil.isNewer("v2.5", "2.4"));
        check("同一个版本不算新", !VersionUtil.isNewer("v2.4", "2.4"));
        check("旧版本返回 -1", VersionUtil.compare("2.4", "v2.5") < 0);
        eq("2.4 与 2.4.0 相等", VersionUtil.compare("2.4", "2.4.0"), 0);
        check("2.10 比 2.9 新（按数字不是按字典）", VersionUtil.isNewer("2.10", "2.9"));
        check("前缀字母不影响", VersionUtil.isNewer("release-2.5", "v2.4"));
        check("预发布后缀被忽略", VersionUtil.isNewer("v2.5-beta", "2.4"));
        check("缺位补 0：3 比 2.9.9 新", VersionUtil.isNewer("3", "2.9.9"));
        check("四位版本号", VersionUtil.isNewer("v1.2.3.4", "1.2.3.3"));
        check("抠不出数字时返回 0", VersionUtil.compare("abc", "v2.4") == 0);
        check("null 不会崩", VersionUtil.compare(null, null) == 0);
        check("空串返回 0", VersionUtil.compare("", "2.4") == 0);
        check("parse 抠不出返回 null", VersionUtil.parse("v") == null);
        check("parse 认 v 前缀", VersionUtil.parse("v2.4") != null && VersionUtil.parse("v2.4").length == 2);

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
