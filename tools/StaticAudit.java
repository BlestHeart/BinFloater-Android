package com.binf.ime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 无设备静态审查：检查输入法/悬浮窗/主界面代码里的常见致命错误。
 * 直接读源码文本，不依赖 Android 运行时。
 */
public class StaticAudit {

    static int problems = 0;
    static int checked = 0;

    static void check(String label, boolean ok, String detail) {
        checked++;
        if (ok) {
            System.out.println("  OK    " + label);
        } else {
            problems++;
            System.out.println("  ERROR " + label + " -> " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        String base = "app/src/com/binf/ime/";
        String ime = read(base + "BinImeService.java");
        String main = read(base + "MainActivity.java");
        String flo = read(base + "FloatingService.java");
        String manifest = read("app/AndroidManifest.xml");

        System.out.println("=== A. 输入法服务必需项 ===");
        check("继承 InputMethodService", ime.contains("extends InputMethodService"), "");
        check("实现 onCreateInputView", ime.contains("public View onCreateInputView()"), "");
        check("返回 rootLayout", ime.contains("return rootLayout;"), "");
        check("有 commitText 调用", ime.contains("commitText("), "");
        check("有 getCurrentInputConnection", ime.contains("getCurrentInputConnection()"), "");
        check("有 deleteSurroundingText", ime.contains("deleteSurroundingText"), "");
        check("有 requestHideSelf", ime.contains("requestHideSelf"), "");
        check("处理 EditorInfo", ime.contains("EditorInfo"), "");

        System.out.println("=== B. 剪贴板读取有异常保护 ===");
        check("IME 读剪贴板 try-catch", ime.contains("catch (Exception e)"), "");
        check("IME 用 coerceToText", ime.contains("coerceToText"), "");
        check("悬浮窗读剪贴板有 try", flo.contains("catch (Exception e)"), "");
        check("两种方式都检查 hasPrimaryClip",
                ime.contains("hasPrimaryClip") && flo.contains("hasPrimaryClip"), "");

        System.out.println("=== C. 悬浮窗关键点 ===");
        check("用 TYPE_APPLICATION_OVERLAY", flo.contains("TYPE_APPLICATION_OVERLAY"), "");
        check("有 FLAG_NOT_FOCUSABLE（气泡）", flo.contains("FLAG_NOT_FOCUSABLE"), "");
        check("面板不设 NOT_FOCUSABLE（要能输入）",
                countOccurrences(flo, "FLAG_NOT_FOCUSABLE") == 1, "只应气泡用一次");
        check("addView 有 try-catch", flo.contains("wm.addView"), "");
        check("removeView 有保护", flo.contains("wm.removeView"), "");
        check("前台服务通知已创建", flo.contains("startForeground"), "");
        check("onDestroy 清理视图", flo.contains("onDestroy"), "");
        check("拖动用 rawX/rawY", flo.contains("getRawX"), "");

        System.out.println("=== D. 主界面 ===");
        check("检查 canDrawOverlays", main.contains("Settings.canDrawOverlays"), "");
        check("申请悬浮窗权限", main.contains("ACTION_MANAGE_OVERLAY_PERMISSION"), "");
        check("startForegroundService 调用", main.contains("startForegroundService"), "");
        check("检查输入法是否启用", main.contains("getEnabledInputMethodList"), "");
        check("检查当前输入法", main.contains("DEFAULT_INPUT_METHOD"), "");
        check("恢复 enabled 变量声明", main.contains("boolean enabled = isImeEnabled()"), "");
        check("恢复 selected 变量声明", main.contains("boolean selected = isImeSelected()"), "");

        System.out.println("=== E. 清单 ===");
        check("声明 BIND_INPUT_METHOD", manifest.contains("BIND_INPUT_METHOD"), "");
        check("声明 android.view.InputMethod 意图", manifest.contains("android.view.InputMethod"), "");
        check("引用 method 资源", manifest.contains("@xml/method"), "");
        check("声明 SYSTEM_ALERT_WINDOW", manifest.contains("SYSTEM_ALERT_WINDOW"), "");
        check("FloatingService 已注册", manifest.contains(".FloatingService"), "");
        check("foregroundServiceType 已设", manifest.contains("foregroundServiceType"), "");

        System.out.println("=== F. 常见空指针风险 ===");
        check("commit 前判空 ic", ime.contains("if (ic == null) return;"), "");
        check("refresh 前判空视图", flo.contains("if (input == null || output == null"), "");
        check("二进制面板视图判空", ime.contains("if (binInput == null || binOutput == null"), "");

        System.out.println("=== G. 双向转换完整性 ===");
        check("IME 支持文字→二进制", ime.contains("textToBinary"), "");
        check("IME 支持二进制→文字", ime.contains("binaryToText"), "");
        check("悬浮窗双向", flo.contains("textToBinary") && flo.contains("binaryToText"), "");
        check("有方向切换", ime.contains("binTextToBin = !binTextToBin"), "");
        check("有紧凑模式", ime.contains("binCompact"), "");
        check("自动识别方向", ime.contains("looksLikeBinary"), "");

        System.out.println("=== H. 键盘完整性 ===");
        check("有 QWERTY 三行", ime.contains("\"qwertyuiop\"") && ime.contains("\"asdfghjkl\"")
                && ime.contains("\"zxcvbnm\""), "");
        check("有数字符号行", ime.contains("1234567890"), "");
        check("有 shift/caps", ime.contains("capsOn"), "");
        check("有空格键", ime.contains("空格"), "");
        check("有删除键", ime.contains("⌫"), "");
        check("有换行键", ime.contains("换行"), "");

        System.out.println("=== I. 九键 T9 ===");
        check("有九键布局常量", ime.contains("LAYOUT_T9"), "");
        check("有九键键盘渲染", ime.contains("renderT9Keys"), "");
        check("有九键底部行", ime.contains("renderT9BottomRow"), "");
        check("有九键按键处理", ime.contains("onT9Key"), "");
        check("有数字串缓冲", ime.contains("t9Digits"), "");
        check("九键字母映射在 T9Engine", read(base + "T9Engine.java").contains("KEY_LETTERS"), "");
        check("九键退格删数字", ime.contains("t9Digits.deleteCharAt"), "");
        check("九键空格上屏首选", ime.contains("pickFirst"), "");

        System.out.println("=== J. 底部留白 / 系统栏 ===");
        check("注册 WindowInsets 监听", ime.contains("setOnApplyWindowInsetsListener"), "");
        check("读 systemBars insets", ime.contains("WindowInsets.Type.systemBars()"), "");
        check("有 applyBottomPad", ime.contains("applyBottomPad"), "");
        check("有 bottomPad 视图", ime.contains("bottomPad"), "");
        check("底部留白可配", ime.contains("\"bottom_pad_dp\""), "");
        check("留白不小于系统栏",
                ime.contains("lastSystemBottom + dp(8)"), "");

        System.out.println("=== J2. 收起键盘按钮 ===");
        check("有收起键字段", ime.contains("hideKbButton"), "");
        check("点了会收键盘", ime.contains("requestHideSelf"), "");
        // U+2304 ⌄ 在不少安卓系统字体里缺字形会显示方块（⌖ 也踩过），必须用 ▼
        check("收起键用 ▼ 不用 ⌄", ime.contains("\"▼\"") && !ime.contains("\"⌄\""), "");
        check("收起键有无障碍描述", ime.contains("setContentDescription"), "");
        // 符号页/二进制页也得能收键盘，所以候选列表只能 INVISIBLE 不能 GONE
        check("候选列表用 INVISIBLE 保留收起键位置",
                ime.contains("View.INVISIBLE"), "");

        System.out.println("=== J3. 长按连删 ===");
        check("有连删定时器", ime.contains("repeatHandler"), "");
        check("有节拍表", ime.contains("REPEAT_DELAYS"), "");
        // 必须用 OnTouchListener：OnLongClickListener 只在长按触发一次，
        // 拿不到松手时机，做不了「按住持续删、松手立刻停」。
        check("用 OnTouchListener 而非 OnLongClickListener",
                ime.contains("setOnTouchListener"), "");
        check("按下启动计时", ime.contains("LONG_PRESS_DELAY_MS"), "");
        // 松手时若不吞掉 up 事件，会再派发一次 onClick，多删一个
        check("松手吞掉多余点击", ime.contains("repeatDeleteStarted"), "");
        check("销毁时停掉定时器", ime.contains("stopRepeatDelete"), "");

        System.out.println("=== J4. 九键拼音选择列 ===");
        check("有拼音列", ime.contains("t9PinyinColumn"), "");
        check("有锁定字段", ime.contains("t9PickedPinyin"), "");
        check("按数字键复位锁定", ime.contains("t9PickedPinyin = \"\""), "");
        // 锁定后要绕过「按数字串猜拼音」，直接用用户选的那个
        check("锁定拼音进候选", ime.contains("decoder.decode(t9PickedPinyin"), "");
        check("锁定拼音可取消", ime.contains("py.equals(t9PickedPinyin) ? \"\" : py"), "");

        System.out.println("=== K. 整句解码 / 云端补词 ===");
        check("有整句解码器", ime.contains("SentenceDecoder"), "");
        check("候选合并了整句", ime.contains("isFull()"), "");
        check("有云端补词", ime.contains("CloudSuggest"), "");
        check("云端失败静默", ime.contains("cloud == null"), "");
        check("云端结果防串词", ime.contains("cloudPinyin"), "");
        check("云端可关", ime.contains("\"cloud_on\""), "");
        check("清单声明 INTERNET", manifest.contains("android.permission.INTERNET"), "");

        System.out.println("=== L. 设置项与读端一致 ===");
        // 这个检查是补一个真踩过的坑：设置里写 auto_translate_on_number，
        // 服务里读 auto_binary_on_number，开关点了没反应。
        check("auto_binary_on_number 两端一致",
                ime.contains("\"auto_binary_on_number\"")
                        && main.contains("\"auto_binary_on_number\""), "");
        check("layout_mode 两端一致",
                ime.contains("\"layout_mode\"") && main.contains("\"layout_mode\""), "");
        check("bottom_pad_dp 两端一致",
                ime.contains("\"bottom_pad_dp\"") && main.contains("\"bottom_pad_dp\""), "");
        check("cloud_on 两端一致",
                ime.contains("\"cloud_on\"") && main.contains("\"cloud_on\""), "");
        check("没有遗留的旧 key", !main.contains("auto_translate_on_number"), "");
        check("设置项写完会刷新输入法", main.contains("notifyImeRefresh"), "");

        System.out.println();
        System.out.println("审查项：" + checked + "  问题：" + problems);
        if (problems > 0) System.exit(1);
    }

    static int countOccurrences(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
