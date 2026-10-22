import com.binf.floater.BinCore;

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

        System.out.println("== 7. 自动判定 ==");
        eq("autoDetect 二进制", BinCore.autoDetect("0100100001101001"), "bin2text");
        eq("autoDetect 文字", BinCore.autoDetect("你好"), "text2bin");
        eq("autoDetect 空", BinCore.autoDetect(""), "unknown");

        System.out.println("== 8. 分组 ==");
        eq("八位分组", BinCore.groupBits("0100100001101001", 8, " "), "01001000 01101001");
        eq("四位分组", BinCore.groupBits("0100100001101001", 4, "-"), "0100-1000-0110-1001");

        System.out.println();
        System.out.println("结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
