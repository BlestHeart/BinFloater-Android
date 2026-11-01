import com.binf.floater.BinCore;
import com.binf.floater.Ccc;
import com.binf.floater.VersionUtil;

/** BinCore 自测：不依赖 Android，可直接用 java 运行。 */
public class CoreTest {
    static int pass = 0, fail = 0;

    static String joinInts(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }

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

        System.out.println("== 11. 从重定向地址抠 tag（检查更新用） ==");
        // releases/latest 会 302 到 .../releases/tag/v2.5，tag 就在最后一段。
        // 走这条路而不是 api.github.com：API 未认证每小时只有 60 次且按 IP 算，
        // 共享出口经常被限流成 403；网页重定向不限流。
        eq("标准重定向地址",
                VersionUtil.parseTag("https://github.com/BlestHeart/BinFloater-Android/releases/tag/v2.5"),
                "v2.5");
        eq("带查询串", VersionUtil.parseTag("https://github.com/o/r/releases/tag/v2.4?x=1"), "v2.4");
        eq("带锚点", VersionUtil.parseTag("https://github.com/o/r/releases/tag/v2.3#notes"), "v2.3");
        eq("带尾斜杠", VersionUtil.parseTag("https://github.com/o/r/releases/tag/v2.2/"), "v2.2");
        eq("不是 tag 地址返回 null",
                VersionUtil.parseTag("https://github.com/o/r/releases/latest"), null);
        eq("null 安全", VersionUtil.parseTag(null), null);
        eq("空 tag 返回 null", VersionUtil.parseTag("https://github.com/o/r/releases/tag/"), null);
        eq("抠出来的 tag 能直接比版本",
                VersionUtil.isNewer(VersionUtil.parseTag(
                        "https://github.com/o/r/releases/tag/v2.10"), "2.9") ? 1 : 0, 1);

        System.out.println("== 12. 解析 version.json（国内镜像那条路） ==");
        // 仓库根目录的 version.json 是 build.sh 生成的四行固定格式。
        // 国内连不上 github.com，所以检查更新改成读这个静态文件 + 一串镜像回退。
        String manifest = "{\n"
                + "  \"versionCode\": 6,\n"
                + "  \"versionName\": \"2.6\",\n"
                + "  \"tag\": \"v2.6\",\n"
                + "  \"apkPath\": \"dist/BinFloater.apk\"\n"
                + "}";
        eq("取字符串字段", VersionUtil.jsonString(manifest, "versionName"), "2.6");
        eq("取带 v 的 tag", VersionUtil.jsonString(manifest, "tag"), "v2.6");
        eq("取路径字段", VersionUtil.jsonString(manifest, "apkPath"), "dist/BinFloater.apk");
        eq("取整数字段", VersionUtil.jsonInt(manifest, "versionCode"), 6);
        eq("整数写成字符串也认",
                VersionUtil.jsonInt("{\"versionCode\": \"7\"}", "versionCode"), 7);
        eq("缺字段返回 null", VersionUtil.jsonString(manifest, "notes"), null);
        eq("缺整数返回 -1", VersionUtil.jsonInt(manifest, "notes"), -1);
        eq("字段名是别的字段的前缀时不能串",
                VersionUtil.jsonString("{\"versionCodeX\": \"9\", \"versionCode\": 6}", "versionCode"), "6");
        eq("没冒号返回 null", VersionUtil.jsonString("{\"tag\" \"v2.6\"}", "tag"), null);
        eq("null 安全", VersionUtil.jsonString(null, "tag"), null);
        eq("键为 null 安全", VersionUtil.jsonString(manifest, null), null);
        eq("末尾没逗号也能取",
                VersionUtil.jsonString("{\"tag\":\"v9.9\"}", "tag"), "v9.9");
        check("解析出来的版本能比大小",
                VersionUtil.isNewer(VersionUtil.jsonString(manifest, "versionName"), "2.5"));

        System.out.println("== 13. 摩斯码 ==");
        // 编码：字母空格、词用 " / "，一律大写（摩斯本身没有大小写）。
        eq("HELLO", BinCore.textToMorse("HELLO"), ".... . .-.. .-.. ---");
        eq("小写也认", BinCore.textToMorse("hello"), ".... . .-.. .-.. ---");
        eq("SOS", BinCore.textToMorse("SOS"), "... --- ...");
        eq("词间用斜杠", BinCore.textToMorse("HI ME"), ".... .. / -- .");
        eq("多空格只算一个词分隔", BinCore.textToMorse("HI   ME"), ".... .. / -- .");
        eq("首尾空格被忽略", BinCore.textToMorse("  HI  "), ".... ..");
        eq("数字", BinCore.textToMorse("123"), ".---- ..--- ...--");
        eq("标点", BinCore.textToMorse("?"), "..--..");
        eq("斜杠字符本身有码", BinCore.textToMorse("/"), "-..-.");
        eq("空串", BinCore.textToMorse(""), "");
        eq("null 安全", BinCore.textToMorse(null), "");
        String cn2 = BinCore.textToMorse("中文");
        eq("两个汉字两个词", cn2.split(" ").length, 2);
        eq("汉字词长 20 个符号", cn2.split(" ")[0].length(), BinCore.MORSE_CCC_SYMBOLS);
        eq("中文解回", BinCore.morseDecode(cn2).text, "中文");
        String mix = BinCore.textToMorse("中A文B");
        eq("中英混排按词切", mix.split(" ").length, 4);
        eq("混排第二个字母", mix.split(" ")[1], ".-");
        eq("混排第四个字母", mix.split(" ")[3], "-...");
        eq("混排能解回", BinCore.morseDecode(mix).text, "中A文B");
        eq("emoji 只占一个 ?", BinCore.textToMorse("a\uD83D\uDE42b"), ".- ? -...");
        eq("? 解回原样占位", BinCore.morseDecode(".- ? -...").text, "A?B");

        System.out.println("== 13b. 摩斯码解码 ==");
        eq("解回 HELLO", BinCore.morseDecode(".... . .-.. .-.. ---").text, "HELLO");
        eq("词间隔还原成空格", BinCore.morseDecode(".... .. / -- .").text, "HI ME");
        eq("竖线也当词分隔", BinCore.morseDecode(".- | -...").text, "A B");
        eq("多空格容错", BinCore.morseDecode("....   .").text, "HE");
        eq("首尾空格容错", BinCore.morseDecode("  .-  ").text, "A");
        eq("数字解码", BinCore.morseDecode(".---- ..--- ...--").text, "123");
        check("空输入被拒", !BinCore.morseDecode("").ok);
        check("null 被拒", !BinCore.morseDecode(null).ok);
        check("纯分隔符被拒", !BinCore.morseDecode(" /  / ").ok);
        check("非法字符被拒", !BinCore.morseDecode(".- x").ok);
        // 行为已改：对不上的组不再整体失败，用 ? 占位、其它部分照解。
        eq("未知组占位不整体失败", BinCore.morseDecode("........").text, "?");
        check("未知组写进说明", BinCore.morseDecode("........").message.contains("对不上码表"));
        eq("超 20 符号的组当未知组", BinCore.morseDecode("......................................").text, "?");
        eq("电码查不到时留着 4 位数字", BinCore.morseDecode("----.----.----.----.").text, "9999");

        System.out.println("== 13c. 摩斯码往返 ==");
        String[] morseSamples = {"SOS", "HELLO WORLD", "A1 B2", "TEST 123"};
        for (String s : morseSamples) {
            eq("往返 [" + s + "]", BinCore.morseDecode(BinCore.textToMorse(s)).text, s);
        }

        System.out.println("== 13d. 摩斯码判定 ==");
        check("标准串判为摩斯", BinCore.looksLikeMorse(".... . .-.. .-.. ---"));
        check("短串不误判", !BinCore.looksLikeMorse("..."));
        check("单个符号不误判", !BinCore.looksLikeMorse(".-"));
        check("普通文字不误判", !BinCore.looksLikeMorse("Hello"));
        check("中文不误判", !BinCore.looksLikeMorse("你好"));
        check("位串不误判", !BinCore.looksLikeMorse("01001000 01101001"));
        check("连写超长不误判", !BinCore.looksLikeMorse(".........-"));
        check("autoDetect 认摩斯", BinCore.autoDetect(".... . .-.. .-.. ---").equals("morse2text"));
        check("autoDetect 不乱认普通文字", BinCore.autoDetect("Hello World").equals("text2bin"));
        check("autoDetect 二进制优先于摩斯", BinCore.autoDetect("01001000 01101001").equals("bin2text"));

        System.out.println("== 13e. 编码结果与统计 ==");
        BinCore.MorseResult enc = BinCore.morseEncode("Hi \u4e2d\u6587");
        eq("混排全部编码（4 个词 + 1 个斜杠）", enc.text.split(" ").length, 5);
        eq("成功标记", enc.ok ? 1 : 0, 1);
        eq("没有跳过任何字", enc.skipped, 0);
        check("说明里点出电码", enc.message.contains("中文电码"));
        check("纯中文也能编码", BinCore.morseEncode("\u4f60\u597d").ok);
        BinCore.MorseResult hole = BinCore.morseEncode("A\uD83D\uDE42B");
        eq("emoji 记一个占位", hole.skipped, 1);
        check("说明里点出占位", hole.message.contains("用 ? 占位"));
        check("超长输入被拒", !BinCore.morseEncode(new String(new char[BinCore.MORSE_MAX_CHARS + 1]).replace('\0', 'a')).ok);

        System.out.println("== 13f. 中文电码表 ==");
        eq("码表规模", Ccc.SIZE, 7078);
        eq("你 = 0132", Ccc.code4('\u4f60'), "0132");
        eq("国 = 0948", Ccc.code4('\u56fd'), "0948");
        eq("一 = 0001（左侧补零）", Ccc.code4('\u4e00'), "0001");
        eq("汉字都有 4 位码", Ccc.code4('\u4e2d').length(), 4);
        eq("字母不在码表", Ccc.code4('A'), null);
        eq("codeOf 查不到返回 -1", Ccc.codeOf('A'), -1);
        eq("codeOf 与 code4 一致", Ccc.codeOf('\u4f60'), 132);
        eq("反查 132", Ccc.hanziOf(132), '\u4f60');
        eq("反查 948", Ccc.hanziOf(948), '\u56fd');
        eq("反查 1", Ccc.hanziOf(1), '\u4e00');
        eq("反查查不到返回 0", Ccc.hanziOf(9999), (char) 0);

        System.out.println("== 13g. 摩斯播放节奏 ==");
        eq("20 WPM 一个点", BinCore.dotMs(20), 60);
        eq("5 WPM 一个点", BinCore.dotMs(5), 240);
        eq("60 WPM 一个点", BinCore.dotMs(60), 20);
        eq("超过 60 WPM 夹住", BinCore.dotMs(100), 20);
        eq("低于 5 WPM 夹住", BinCore.dotMs(1), 240);
        BinCore.MorsePlan p1 = BinCore.morsePlan(".", 20, 60000);
        eq("单点节奏", joinInts(p1.pattern), "60 0");
        eq("单点时长", p1.totalMs, 60);
        BinCore.MorsePlan p2 = BinCore.morsePlan(".... .", 20, 60000);
        eq("HE 节奏", joinInts(p2.pattern), "60 60 60 60 60 60 60 180 60 0");
        eq("HE 时长", p2.totalMs, 660);
        BinCore.MorsePlan p3 = BinCore.morsePlan("... / ---", 20, 60000);
        eq("词间隔 7 单位", p3.totalMs, 1380);
        check("节奏表以发声开头", p3.pattern[0] == 60);
        check("未截断标记", !p3.truncated);
        BinCore.MorsePlan cut = BinCore.morsePlan(".... . . .", 20, 100);
        check("超上限被截断", cut.truncated);
        eq("截断后没排进去", cut.pattern.length, 0);
        BinCore.MorsePlan empty = BinCore.morsePlan("", 20, 60000);
        eq("空输入没有节奏", empty.pattern.length, 0);
        eq("空输入时长 0", empty.totalMs, 0);
        BinCore.MorsePlan cnPlan = BinCore.morsePlan(BinCore.textToMorse("\u4f60\u597d"), 20, 60000);
        check("中文摩斯也能排节奏", cnPlan.pattern.length > 0);
        check("节奏表长度是偶数", cnPlan.pattern.length % 2 == 0);

        System.out.println("== 14. 十六进制 ==");
        eq("文字 -> HEX", BinCore.textToHex("Hi"), "48 69");
        eq("空串", BinCore.textToHex(""), "");
        eq("null 安全", BinCore.textToHex(null), "");
        eq("中文 -> HEX", BinCore.textToHex("\u4f60\u597d"), "E4 BD A0 E5 A5 BD");
        eq("HEX -> 文字", BinCore.hexToText("48 69").text, "Hi");
        eq("HEX 解码字节数", BinCore.hexToText("48 69").bytes, 2);
        check("HEX 说明里点出字节", BinCore.hexToText("48 69").message.contains("2 字节"));
        eq("认 0x 前缀", BinCore.hexToText("0x48656C6C6F").text, "Hello");
        eq("小写也认", BinCore.hexToText("48656c6c6f").text, "Hello");
        eq("冒号分隔", BinCore.hexToText("48:65:6C:6C:6F").text, "Hello");
        eq("逗号/下划线/短横线分隔", BinCore.hexToText("48,65_6C-6C-6F").text, "Hello");
        eq("中文能解回", BinCore.hexToText(BinCore.textToHex("\u4f60\u597d")).text, "\u4f60\u597d");
        eq("emoji 能解回", BinCore.hexToText(BinCore.textToHex("A\uD83D\uDE42B")).text, "A\uD83D\uDE42B");
        eq("字母串 0x 前缀的 1 字节", BinCore.hexToBytes("FF")[0], (byte) 0xFF);
        check("奇数位被拒", !BinCore.hexToText("486").ok);
        check("奇数位说明里点出来", BinCore.hexToText("486").message.contains("奇数"));
        check("非法字符被拒", !BinCore.hexToText("48 ZZ").ok);
        check("空输入被拒", !BinCore.hexToText("").ok);
        check("null 被拒", !BinCore.hexToText(null).ok);
        check("纯分隔符被拒", !BinCore.hexToText("  :, ").ok);
        check("解出控制字符时拒绝", !BinCore.hexToText("0001020304").ok);
        eq("上限与二进制同一口径", BinCore.MAX_HEX_CHARS, BinCore.MAX_BITS / 4);
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < BinCore.MAX_HEX_CHARS + 2; i++) tooLong.append('4');
        check("超长被拒", !BinCore.hexToText(tooLong.toString()).ok);

        System.out.println("== 14b. HEX 判定 ==");
        check("带空格的 HEX 认得", BinCore.looksLikeHex("48 65 6C 6C 6F"));
        check("0x 前缀认得", BinCore.looksLikeHex("0x48656C6C6F"));
        check("连写 HEX 认得", BinCore.looksLikeHex("48656C6C6F"));
        check("年份不当 HEX", !BinCore.looksLikeHex("2024"));
        check("英文单词不当 HEX", !BinCore.looksLikeHex("Hello"));
        check("中文不当 HEX", !BinCore.looksLikeHex("\u4f60\u597d"));
        check("位串不当 HEX", !BinCore.looksLikeHex("01001000 01101001"));
        check("Base64 不当 HEX", !BinCore.looksLikeHex("SGVsbG8="));
        check("解不成正文的 HEX 不认", !BinCore.looksLikeHex("deadbeef"));
        check("autoDetect 认 HEX", BinCore.autoDetect("48 65 6C 6C 6F").equals("hex2text"));
        check("autoDetect 不乱认年份", BinCore.autoDetect("2024").equals("text2bin"));
        check("autoDetect 二进制仍优先", BinCore.autoDetect("01001000 01101001").equals("bin2text"));
        check("autoDetect 摩斯仍最优先", BinCore.autoDetect(".... . .-.. .-.. ---").equals("morse2text"));

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}