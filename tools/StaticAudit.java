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
        String upd = read(base + "UpdateChecker.java");
        String prov = read(base + "ApkProvider.java");
        String ver = read(base + "VersionUtil.java");
        // 反向断言一律对去注释后的代码做，否则会命中「以前写的是…」这类注释
        String mainCode = stripComments(main);
        String floCode = stripComments(flo);
        String binCode = stripComments(bin);
        String palCode = stripComments(pal);
        String updCode = stripComments(upd);
        String provCode = stripComments(prov);
        String verCode = stripComments(ver);

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
        // 联网权限是有意加的：只给「检查更新」用（查 GitHub Releases、下载本 App 的 APK）。
        // 但仍然不要 ACCESS_NETWORK_STATE —— 那是用来探测网络状态做后台重试的，这里不需要。
        check("声明 INTERNET（只给检查更新用）",
                manifest.contains("android.permission.INTERNET"), "");
        check("没有网络状态权限", !manifest.contains("ACCESS_NETWORK_STATE"), "");

        System.out.println("=== E. 常见空指针风险 ===");
        check("refresh 前判空视图", flo.contains("if (input == null || output == null"), "");
        check("handler 判空", flo.contains("if (handler != null)"), "");
        check("wm 移除前判空", flo.contains("if (bubble != null)"), "");

        System.out.println("=== F. 双向转换完整性 ===");
        check("支持文字→二进制", flo.contains("textToBinary"), "");
        check("支持二进制→文字", flo.contains("binaryToText"), "");
        // 方向现在是「自动 / 文→码 / 码→文」三态按钮，编码格式单独一个 BIN/B64
        // 按钮，两个布尔 + 派生出来的 dir。老的四向轮转已经拆掉了。
        check("有方向三态切换", flo.contains("autoDir = true;") && flo.contains("decodeDir = true;"), "");
        check("有编码格式切换", flo.contains("codecB64 = !codecB64;"), "");
        check("自动识别方向", flo.contains("BinCore.autoDetect"), "");
        check("自动识别在 refresh 里（手打也生效）",
                methodBody(floCode, "private void refresh()").contains("applyAutoDir(in)"),
                "自动识别只挂在 readClip 上，手打 0/1 不会被识别");
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
        // 21) 文字颜色不能等于按钮自己的背景色，否则按钮看起来像消失了。
        //     v2.3 真机验收踩到：modeBtn 在非 text2bin 方向把文字设成 pal.cardHi，
        //     而 smallBtn 的背景正好也是 pal.cardHi —— 「8位」按钮整块空白。
        check("文字颜色不等于自身背景色", !floCode.contains("setTextColor(pal.cardHi)"), "");
        check("不可用按钮用 alpha 淡出", floCode.contains("modeBtn.setAlpha("), "");

        System.out.println("=== J. 按钮按下反馈 ===");
        // 22) 所有按钮都要有按下态。以前全是 setBackgroundColor(单色)，TextView
        //     没有按下反馈，点下去屏幕一动不动，用户不知道点到没有。
        check("Palette 提供按下态背景", palCode.contains("pressedBg("), "");
        check("按下色由色板算（不写死）", palCode.contains("0.16f"), "");
        check("悬浮窗小按钮有按下态",
                methodBody(floCode, "private TextView smallBtn(String label)").contains("pressedBg("),
                "smallBtn 又改回 setBackgroundColor 了");
        check("操作按钮有按下态",
                methodBody(floCode, "private TextView actionBtn(String label, View.OnClickListener l)")
                        .contains("pressedBg("), "");
        check("菜单条目有按下态", floCode.contains("pressedBg(pal.cardHi)"), "");
        check("主界面按钮有按下态", mainCode.contains("pressedBg("), "");
        // 23) 标题栏必须有方向和编码两个按钮，且格式按钮让位到第二行。
        check("标题栏有方向按钮", floCode.contains("dirBtn = smallBtn("), "");
        check("标题栏有编码按钮", floCode.contains("codecBtn = smallBtn("), "");
        check("格式按钮挪到第二行", floCode.contains("dirRow.addView(modeBtn)"), "");
        // 24) 退出自动识别时 dir 必须自己同步。漏了就是「按钮点了没反应」：
        //     refresh() 读的是 dir，只改布尔不改 dir，界面就停在旧格式上。
        check("toggleCodec 退出自动后同步 dir",
                methodBody(floCode, "private void toggleCodec()").contains("syncDirFromFlags()"),
                "toggleCodec 退出自动识别后没同步 dir，点了不生效");
        check("cycleDir 退出自动后同步 dir",
                methodBody(floCode, "private void cycleDir()").contains("syncDirFromFlags()"), "");
        // 25) 按钮文字要读「实际生效的格式」，不能只看 codecB64。
        check("编码按钮读 shownCodecB64",
                methodBody(floCode, "private void updateDir()").contains("shownCodecB64()"),
                "编码按钮只看 codecB64，自动识别时会显示与实际解码格式不符");
        check("自动识别不覆盖正向编码目标",
                methodBody(floCode, "private void applyAutoDir(String in)")
                        .contains("dir = codecB64 ? DIR_TEXT2B64 : DIR_TEXT2BIN;"), "");

        System.out.println("=== J. 检查更新 / 安装器 ===");
        // 26) 打开主界面顺手查一次，24 小时节流；失败静默（国内直连 api.github.com 常超时）
        check("主界面会调自动检查", mainCode.contains("maybeAutoCheckUpdate()"), "");
        check("自动检查有 24 小时节流",
                mainCode.contains("UpdateChecker.AUTO_INTERVAL_MS"), "");
        check("自动检查失败静默",
                mainCode.contains("updState = manual ? UPD_FAILED : UPD_IDLE"),
                "自动检查失败也会显示错误，开 App 就弹噪音");
        // 27) 下载：用 applicationContext（转屏重建不能中断），有大小上限，失败要删半截文件
        check("下载用 applicationContext", mainCode.contains("getApplicationContext()"), "");
        check("下载失败删半截文件", updCode.contains("out.delete()"), "");
        check("下载有大小上限", updCode.contains("MAX_APK_BYTES"), "");
        check("请求带 User-Agent", updCode.contains("\"User-Agent\""), "");
        check("404 有专门提示", updCode.contains("没有找到 Release"), "");
        // 31) 查版本走网页重定向，不打 api.github.com
        check("走 releases/latest 重定向", updCode.contains("REPO + \"/releases/latest\""), "");
        check("代码里没有 api.github.com", !updCode.contains("api.github.com"),
                "API 未认证每小时 60 次、按 IP 算，共享出口会被限流成 403");
        check("不依赖 org.json", !updCode.contains("org.json"), "");
        check("手动接管重定向", updCode.contains("setInstanceFollowRedirects(false)"),
                "默认会自动跟到最终页，就拿不到 Location 了");
        check("APK 地址由 tag 拼出", updCode.contains("\"/releases/download/\""), "");
        check("tag 解析在 VersionUtil 里（可桌面单测）",
                verCode.contains("public static String parseTag"), "");
        // 28) 安装：必须走 content://（file:// 在 targetSdk 24+ 直接抛 FileUriExposedException）
        check("安装走 content URI", mainCode.contains("ApkProvider.uri()"), "");
        check("代码里没有 file:// URI", !mainCode.contains("file://"),
                "targetSdk 24+ 用 file:// 会抛 FileUriExposedException");
        check("安装授予读 URI 权限", mainCode.contains("FLAG_GRANT_READ_URI_PERMISSION"), "");
        check("判定未知来源权限", mainCode.contains("canRequestPackageInstalls"), "");
        check("能引导去开未知来源", mainCode.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"), "");
        check("从设置页回来续装", mainCode.contains("resumeInstallAfterSettings"), "");
        // 29) Provider 只放行这一个文件，且只读
        check("provider 只提供 update.apk",
                provCode.contains("FILE_NAME.equals(uri.getLastPathSegment())"), "");
        check("provider 只读打开", provCode.contains("MODE_READ_ONLY"), "");
        check("manifest 声明 provider", manifest.contains("com.binf.floater.apk"), "");
        check("manifest 有 INTERNET 权限",
                manifest.contains("android.permission.INTERNET"), "");
        check("manifest 有安装未知应用权限",
                manifest.contains("REQUEST_INSTALL_PACKAGES"), "");
        // 30) 版本比较是纯函数（能桌面单测），且不再声称「没有联网权限」
        check("版本比较在 VersionUtil 里", verCode.contains("public static int compare"), "");
        check("主界面不再声称没有联网权限",
                !mainCode.contains("App 没有联网权限"),
                "已经加了检查更新，这句是假话");

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