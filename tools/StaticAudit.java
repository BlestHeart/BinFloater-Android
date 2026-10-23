package com.binf.floater;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 无设备静态审查：检查悬浮窗/主界面代码里的常见致命错误。
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
        String base = "app/src/com/binf/floater/";
        String main = read(base + "MainActivity.java");
        String flo = read(base + "FloatingService.java");
        String manifest = read("app/AndroidManifest.xml");

        System.out.println("=== A. 悬浮窗关键点 ===");
        check("用 TYPE_APPLICATION_OVERLAY", flo.contains("TYPE_APPLICATION_OVERLAY"), "");
        check("气泡设 NOT_FOCUSABLE", flo.contains("FLAG_NOT_FOCUSABLE"), "");
        // 面板必须可获焦，否则 Android 10+ 读不到剪切板。
        // 全文只应出现一次 FLAG_NOT_FOCUSABLE（给气泡用），面板绝不能加。
        check("面板不设 NOT_FOCUSABLE（要能读剪切板）",
                countOccurrences(stripComments(flo), "FLAG_NOT_FOCUSABLE") == 1,
                "只应气泡用一次");
        check("addView 有 try-catch", flo.contains("wm.addView"), "");
        check("removeView 有保护", flo.contains("wm.removeView"), "");
        check("前台服务通知已创建", flo.contains("startForeground"), "");
        check("onDestroy 清理视图", flo.contains("onDestroy"), "");
        check("拖动用 rawX/rawY", flo.contains("getRawX"), "");

        System.out.println("=== A2. 关闭按钮只收面板 ===");
        // ✕ 必须只收面板，不能把整个前台服务停掉——停了悬浮球也没了，
        // 用户还得回主界面重开。要彻底关掉走长按。
        check("✕ 有独立点击处理", flo.contains("close.setOnClickListener"), "");
        check("✕ 只收起面板", flo.contains("close.setOnClickListener(v -> hidePanel())"), "");
        check("✕ 长按才关服务", flo.contains("close.setOnLongClickListener"), "");
        check("✕ 有无障碍描述", flo.contains("setContentDescription(\"收起面板\")"), "");
        // 长按 ✕ 走 stopSelf()，不经过主界面开关回调。服务死了开关必须回落，
        // 否则主界面一直显示「运行中」，桌面上却没有小球。
        String floCode = stripComments(flo);
        int destroyAt = floCode.indexOf("public void onDestroy()");
        String destroyBody = destroyAt < 0 ? "" : floCode.substring(destroyAt);
        check("onDestroy 把开关回落成关", destroyBody.contains("setEnabledPref(false)"), "");
        int createAt = floCode.indexOf("public void onCreate()");
        String createBody = createAt < 0 ? "" : floCode.substring(createAt, Math.max(destroyAt, 0));
        check("onCreate 把开关置成开", createBody.contains("setEnabledPref(true)"), "");
        check("开关状态写进 SharedPreferences(\"binf\")",
                floCode.contains("getSharedPreferences(\"binf\""), "");
        // 主界面 onResume 回填开关时会触发监听器，不屏蔽就会白跑一次 start/stop 服务。
        check("回填开关有 syncingSwitch 屏蔽", main.contains("syncingSwitch"), "");
        check("监听器开头就 return", main.contains("if (syncingSwitch) return;"), "");

        System.out.println("=== B. 剪切板读取 ===");
        check("读剪切板有 try", flo.contains("catch (Exception e)"), "");
        check("检查 hasPrimaryClip", flo.contains("hasPrimaryClip"), "");
        check("用 coerceToText", flo.contains("coerceToText"), "");
        // 关键：面板获得焦点后要等一下再读，太快系统还没交焦点，读出来是空
        check("拿到焦点后延迟读剪切板", flo.contains("CLIP_DELAY_MS"), "");
        check("自动读只读一次", flo.contains("autoReadPending"), "");
        check("自动读不打扰（silent）", flo.contains("readClip(true)"), "");
        check("内容没变不覆盖输入", flo.contains("text.equals(input.getText().toString())"), "");

        System.out.println("=== C. 主界面 ===");
        check("检查 canDrawOverlays", main.contains("Settings.canDrawOverlays"), "");
        check("申请悬浮窗权限", main.contains("ACTION_MANAGE_OVERLAY_PERMISSION"), "");
        check("startForegroundService 调用", main.contains("startForegroundService"), "");
        check("stopService 调用", main.contains("stopService"), "");
        check("回到前台刷新状态", main.contains("refreshStatus()"), "");

        System.out.println("=== D. 清单 ===");
        check("声明 SYSTEM_ALERT_WINDOW", manifest.contains("SYSTEM_ALERT_WINDOW"), "");
        check("FloatingService 已注册", manifest.contains(".FloatingService"), "");
        check("foregroundServiceType 已设", manifest.contains("foregroundServiceType"), "");
        check("包名已改为 floater", manifest.contains("com.binf.floater"), "");
        // 输入法相关必须彻底消失，否则又变回输入法项目了
        check("没有输入法服务声明", !manifest.contains("BIND_INPUT_METHOD"), "");
        check("没有 InputMethod 意图", !manifest.contains("android.view.InputMethod"), "");
        check("没有 method 资源引用", !manifest.contains("@xml/method"), "");
        check("没有联网权限", !manifest.contains("android.permission.INTERNET"), "");
        check("没有网络状态权限", !manifest.contains("ACCESS_NETWORK_STATE"), "");

        System.out.println("=== E. 常见空指针风险 ===");
        check("refresh 前判空视图", flo.contains("if (input == null || output == null"), "");
        check("handler 判空", flo.contains("if (handler != null)"), "");
        check("wm 移除前判空", flo.contains("if (bubble != null)"), "");

        System.out.println("=== F. 双向转换完整性 ===");
        check("支持文字→二进制", flo.contains("textToBinary"), "");
        check("支持二进制→文字", flo.contains("binaryToText"), "");
        check("有方向切换", flo.contains("textToBin = !textToBin"), "");
        check("自动识别方向", flo.contains("looksLikeBinary"), "");
        check("有复制按钮", flo.contains("copyOutput"), "");
        check("有清空按钮", flo.contains("清空"), "");
        check("有紧凑模式变量", flo.contains("compact"), "");

        System.out.println("=== G. 输入法残留检查 ===");
        check("MainActivity 无输入法代码", !main.contains("InputMethodManager"), "");
        check("MainActivity 无拼音设置", !main.contains("layout_mode"), "");
        check("MainActivity 无云端设置", !main.contains("cloud_on"), "");
        check("FloatingService 无拼音引用", !flo.contains("PinyinEngine"), "");

        System.out.println();
        System.out.println("审查项：" + checked + "  问题：" + problems);
        if (problems > 0) System.exit(1);
    }

    static int countOccurrences(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /** 去掉 // 注释行，避免注释里提到关键字影响计数。 */
    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
