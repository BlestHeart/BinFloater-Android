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
        String bin = read(base + "BinCore.java");
        String pal = read(base + "Palette.java");
        String manifest = read("app/AndroidManifest.xml");
        // 反向断言一律对去注释后的代码做，否则会命中「以前写的是…」这类注释
        String mainCode = stripComments(main);
        String floCode = stripComments(flo);
        String binCode = stripComments(bin);
        String palCode = stripComments(pal);

        System.out.println("=== A. 悬浮窗关键点 ===");
        check("用 TYPE_APPLICATION_OVERLAY", flo.contains("TYPE_APPLICATION_OVERLAY"), "");
        check("气泡设 NOT_FOCUSABLE", flo.contains("FLAG_NOT_FOCUSABLE"), "");
        // 面板必须可获焦，否则 Android 10+ 读不到剪切板。
        // 气泡和长按菜单都要 NOT_FOCUSABLE（它们不收键盘输入），但**面板那一块**绝不能加。
        // 所以这里按「panelLp = new WindowManager.LayoutParams(...)」整段取出来单独查，
        // 而不是数全文出现几次 —— 数次数会把合法的菜单也算进去，误报。
        check("面板不设 NOT_FOCUSABLE（要能读剪切板）",
                !layoutBlock(flo, "panelLp").contains("FLAG_NOT_FOCUSABLE"),
                "panelLp 里出现了 FLAG_NOT_FOCUSABLE，面板会读不到剪切板");
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
        // 方向从「两向布尔」扩成四向之后，切换逻辑改成 switch (dir) 轮转。
        check("有方向切换", flo.contains("case DIR_BIN2TEXT: dir = DIR_TEXT2B64; break;"), "");
        check("自动识别方向", flo.contains("BinCore.autoDetect"), "");
        check("有复制按钮", flo.contains("copyOutput"), "");
        check("有清空按钮", flo.contains("清空"), "");
        check("有紧凑模式变量", flo.contains("compact"), "");

        System.out.println("=== G. 输入法残留检查 ===");
        check("MainActivity 无输入法代码", !main.contains("InputMethodManager"), "");
        check("MainActivity 无拼音设置", !main.contains("layout_mode"), "");
        check("MainActivity 无云端设置", !main.contains("cloud_on"), "");
        check("FloatingService 无拼音引用", !flo.contains("PinyinEngine"), "");

        System.out.println("=== H. 本轮修复的回归守卫 ===");
        // 1) 长按球只能有一条路径。setOnLongClickListener 会在 ~400ms 就
        //    performLongClick，自定义 runnable 在 450ms 再来一次，两次 showMenu()
        //    相撞把菜单关掉 —— 菜单闪一下就消失。
        check("长按球不挂 setOnLongClickListener（只留自定义 runnable）",
                !floCode.contains("bubble.setOnLongClickListener"),
                "bubble 上又挂了 setOnLongClickListener，会和 postDelayed(longPress) 双触发");
        check("长按仍由自定义 runnable 触发",
                floCode.contains("handler.postDelayed(longPress, LONG_PRESS_MS)"), "");
        // 2) showPanel 必须幂等，否则面板已开时通知栏「打开面板」会重复 addView。
        String showPanelBody = methodBody(floCode, "private void showPanel()");
        check("showPanel 开头有 panelVisible 守卫", showPanelBody.contains("if (panelVisible)"), "");
        // 3) hidePanel 不能清掉全部 handler 消息，否则重开面板输出框停在旧值。
        String hidePanelBody = methodBody(floCode, "private void hidePanel()");
        check("hidePanel 不清全部 handler 消息",
                !hidePanelBody.contains("removeCallbacksAndMessages(null)"),
                "又清成 null 了，refreshRunnable 会被一起清掉");
        check("hidePanel 只清 refreshRunnable",
                hidePanelBody.contains("removeCallbacks(refreshRunnable)"), "");
        // 4) 复制不能弹两条 toast：截断后长度正好等于上限，旧写法两条都成立。
        String copyBody = methodBody(floCode, "private void copyOutput()");
        check("复制用截断前标记判断 toast", copyBody.contains("boolean truncated"), "");
        check("复制不再用长度比较决定提示",
                !copyBody.contains("if (s.length() <= MAX_COPY_CHARS) toast"), "");
        // 5) 文字→二进制方向必须有长度上限，否则粘长文卡死主线程。
        check("文字→二进制有长度上限", floCode.contains("bitsLen > BinCore.MAX_BITS"), "");
        // 6) addView 失败要把 bubble 置回 null，否则幂等守卫挡住重试。
        String createBubbleBody = methodBody(floCode, "private void createBubble()");
        check("addView 失败回滚 bubble 字段", createBubbleBody.contains("bubble = null;"), "");
        // 7) startForeground 失败必须自杀，不然系统判 ANR。
        String fgBody = methodBody(floCode, "private void startForegroundSafe()");
        check("startForeground 失败后 stopSelf", fgBody.contains("stopSelf()"), "");
        // 8) 主界面只信 isRunning()，不能拿陈旧 prefs 冒充「运行中」。
        String isOnBody = methodBody(mainCode, "private boolean isFloatingOn()");
        check("isFloatingOn 只信 isRunning()",
                !isOnBody.contains("prefs.getBoolean(\"floating_enabled\"")
                        && isOnBody.contains("FloatingService.isRunning()"),
                "又退回用 prefs 判断了，重启后会显示「运行中」但桌面没球");
        check("onResume 对齐 prefs", mainCode.contains("syncEnabledPref()"), "");
        // 9) 服务要覆写 onConfigurationChanged 自己重算位置。
        //    注意 <service> 没有 android:configChanges 这个属性（那是 activity 的），
        //    服务的 onConfigurationChanged 由系统直接调，不需要清单声明。
        check("service 覆写 onConfigurationChanged",
                floCode.contains("public void onConfigurationChanged(Configuration"), "");
        String cfgBody = methodBody(floCode, "public void onConfigurationChanged(Configuration");
        check("转屏后重算球位置", cfgBody.contains("clampBubble"), "");
        check("转屏后重算面板位置", cfgBody.contains("syncPanelPos"), "");
        // 10) BinCore 的长度上限必须两边口径一致（都先 cleanBits 再比）。
        check("looksLikeBinary 先 cleanBits 再比长度",
                binCode.contains("String bits = cleanBits(raw);")
                        && !binCode.contains("raw.length() > MAX_BITS"),
                "又用含分隔符的 raw.length() 比上限了，判定和解码会不一致");
        // 11) HEX 公开 API 不能复用带预览上限的实现。
        check("textToHex 走完整实现",
                binCode.contains("public static String textToHex(String text)"),
                "");
        check("bitsToHex 走完整实现",
                binCode.contains("public static String bitsToHex(String bits)"), "");
        check("textToHexBytes 不再截断", !binCode.contains("Math.min(data.length, 24)"), "");
        check("previewHex 只给错误提示用", binCode.contains("previewHex(data, 24)"), "");
        // 12) 分享不能把超大内容塞进 extras。
        check("分享有长度上限", floCode.contains("MAX_SHARE_CHARS"), "");
        // 13) 截断显示要防代理对。
        check("截断显示防代理对", floCode.contains("Character.isHighSurrogate"), "");
        // 14) 键盘遮挡：面板要监听 ime insets。
        check("面板监听 ime insets", flo.contains("Type.ime()"), "");
        check("面板位置扣掉键盘高度", flo.contains("imeBottom"), "");

        System.out.println("=== I. 球外观 / 历史 / 面板拖动 / Base64 / 主题 的回归守卫 ===");
        // 15) 球大小与透明度：必须走统一入口，且必须夹在合法区间里。
        check("有球大小三档", floCode.contains("BUBBLE_SCALES"), "");
        check("有球透明度四档", floCode.contains("BUBBLE_ALPHAS"), "");
        check("球大小下标有钳制",
                floCode.contains("clamp(bubbleScale, 0, BUBBLE_SCALES.length - 1)"), "");
        check("球透明度夹在 0..100",
                floCode.contains("clamp(percent, 0, 100)"), "");
        check("球外观统一入口", floCode.contains("applyBubbleStyle"), "");
        // 16) 转换历史：环形上限 + 去重 + 不落盘。
        check("历史有上限常量", floCode.contains("MAX_HISTORY"), "");
        check("历史按方向区分", floCode.contains("DIR_SEP") || floCode.contains("'\\u0002'"), "");
        // 用户内容里可能自带 \u0001/\u0002，不转义就会把一条历史拆成几条、chip 也切错位置。
        check("历史条目转义分隔符",
                floCode.contains("escapeHist") && floCode.contains("unescapeHist")
                        && floCode.contains("unescapeHist(sep < 0"), "");
        // 「清空历史」后一收面板又冒一条，是用户能直接看见的自相矛盾。
        check("清空历史后不再写回", floCode.contains("skipHistoryOnHide"), "");
        check("历史去重后再入栈", floCode.contains("history.remove(entry)"), "");
        check("历史有清空入口", mainCode.contains("清空历史") || floCode.contains("清空历史"), "");
        // 17) 面板拖动：拖动时不能触发点击，且松手要记住位置。
        check("有面板拖动实现", floCode.contains("setupPanelDrag"), "");
        check("面板拖动区分点击与拖动", floCode.contains("if (moved)"), "");
        check("面板位置有记忆", floCode.contains("panelPinned"), "");
        check("面板位置落盘", floCode.contains("putInt(\"panel_x\""), "");
        // 18) Base64 四向转换的四个入口都要在。
        check("有文字→Base64", binCode.contains("public static String textToBase64"), "");
        check("有Base64→文字", binCode.contains("public static DecodeResult base64ToText"), "");
        check("有Base64判定", binCode.contains("public static boolean looksLikeBase64"), "");
        check("autoDetect 认 Base64", binCode.contains("\"b64text\""), "");
        check("位串→Base64 走完整实现", binCode.contains("public static String bitsToBase64"), "");
        // 19) 主题：两套调色板 + 跟随系统 + 不硬编码颜色。
        check("有 Palette 类", mainCode.contains("Palette") && floCode.contains("Palette"), "");
        check("主题三模式", palCode.contains("MODE_SYSTEM") && palCode.contains("MODE_LIGHT")
                && palCode.contains("MODE_DARK"), "");
        check("主题跟随系统", palCode.contains("UI_MODE_NIGHT"), "");
        check("主题落盘", mainCode.contains("theme_mode"), "");
        // 20) 主界面不能再有写死的颜色字面量（都应该走 Palette）。
        check("主界面不再写死颜色",
                !mainCode.contains("Color.rgb(") && !mainCode.contains("0xFF0F1117"), "");

        System.out.println();
        System.out.println("审查项：" + checked + "  问题：" + problems);
        if (problems > 0) System.exit(1);
    }

    static int countOccurrences(String haystack, String needle) {
        int c = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /**
     * 从「lpName = new WindowManager.LayoutParams(」开始截到该语句结束的 ");"，
     * 拿到这一整块布局参数文本。用来单查某个窗口有没有加某个 flag，
     * 避免全文计数把别的窗口（气泡/菜单）算进来。
     */
    static String layoutBlock(String src, String lpName) {
        String marker = lpName + " = new WindowManager.LayoutParams(";
        int start = src.indexOf(marker);
        if (start < 0) return "";
        int end = src.indexOf(");", start);
        if (end < 0) end = Math.min(src.length(), start + 600);
        return src.substring(start, Math.min(src.length(), end + 2));
    }

    /** 去掉 // 注释行，避免注释里提到关键字影响计数。 */
    static String stripComments(String src) {
        // 先把块注释 /* ... */ 整段抹掉，再逐行去 //。
        // 只去 // 是不够的：代码里大量注释在讲「以前这里写的是 xxx」，
        // 反向断言（!contains）会命中注释里那串历史代码，把已经修好的东西判成没修。
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\n")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    /** 取某个方法（含签名）之后的一整段代码，括号配平为止。找不到返回空串。 */
    static String methodBody(String code, String signature) {
        int at = code.indexOf(signature);
        if (at < 0) return "";
        int open = code.indexOf('{', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return code.substring(at, i + 1);
            }
        }
        return code.substring(at);
    }

    static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
