package com.binf.floater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：开启悬浮窗 + 说明 + 试一下。
 *
 * 颜色全部走 Palette，和悬浮窗面板同源 —— 以前这里写死一套深色，
 * 系统是浅色时主界面也是黑的，和面板对不上。
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView permView;
    private LinearLayout root;
    /** buildUi 里那个外层 ScrollView，重建界面时用它保住滚动位置。 */
    private ScrollView rootScroll;
    private Switch floatingSwitch;
    /** 回填开关状态时置位，避免 setChecked() 反过来触发监听器。 */
    private boolean syncingSwitch = false;
    /**
     * 刚点了「开」、服务还没起来的这段时间。
     *
     * 服务从 startForegroundService 到 onCreate 里把 running 置 true 之间有个空档，
     * 这期间 isRunning() 还是 false。若用户在 300ms 内切出去再回来，
     * onResume 的 syncEnabledPref() 会照着 false 把 prefs 写回去 ——
     * 开关显示「关」，可球其实已经出来了，两边打架。
     */
    private long pendingStartUntil = 0L;
    /** 当前配色，buildUi 时从 Palette 取一次。 */
    private Palette pal;
    /**
     * 上次画界面时用的球大小 / 不透明度档位。
     *
     * 长按悬浮球菜单也能改这两项（服务侧写同一个 prefs 键），
     * 主界面此时在后台、不会重建界面。回到前台如果只刷状态文字，
     * 卡片上还印着旧档位「大小：中」，可球已经是「大」了 —— 用户看到的和实际不一致。
     */
    private int shownScale = -1;
    private int shownAlpha = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("binf", Context.MODE_PRIVATE);
        pal = Palette.get(this);
        setContentView(buildUi());
        applySystemBars();
        requestNotificationPermissionIfNeeded();
    }

    /** Android 13+ 不请求通知权限，前台服务的常驻通知就是隐形的。 */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncEnabledPref();
        refreshStatus();
        // 从权限设置页回来时开关要跟着实际状态走。
        // 服务可能在我们看不见的时候被系统回收，或者被长按 ✕ 关掉——
        // 那时候 prefs 已经是 false，这里把开关也拉回去。
        // setChecked() 会触发 OnCheckedChangeListener，得先屏蔽掉，
        // 否则一次回填会顺带再 start/stop 一次服务。
        if (floatingSwitch != null) {
            syncingSwitch = true;
            floatingSwitch.setChecked(isFloatingOn());
            syncingSwitch = false;
        }
        // 系统深浅色可能在后台切了（跟随系统模式下），回来时重画一遍。
        Palette now = Palette.get(this);
        int scaleNow = prefs.getInt("bubble_scale", 1);
        int alphaNow = prefs.getInt("bubble_alpha", 1);
        if (now != pal || scaleNow != shownScale || alphaNow != shownAlpha) {
            pal = now;
            setContentView(buildUi());
            refreshStatus();
            applySystemBars();
        }
    }

    /**
     * 悬浮窗到底开没开：只信服务进程内的静态标志（它才是事实）。
     *
     * 以前是 `isRunning() || prefs.getBoolean("floating_enabled", false)`，
     * 而 prefs 是陈旧的：重启手机 / `am force-stop` / 被系统回收时，
     * onDestroy 不保证执行，prefs 还停在 true。于是主界面显示「✅ 运行中」，
     * 桌面上却没有球，用户把开关拨向「开」还是空操作 —— 因为 setChecked 本来就是开的。
     * 现在只信 isRunning()，并且 onResume 里把 prefs 对齐到实际状态。
     */
    private boolean isFloatingOn() {
        return FloatingService.isRunning();
    }

    /** 把 prefs 里那份「应该开着」对齐到服务的真实状态，避免下次冷启动被陈旧值误导。 */
    private void syncEnabledPref() {
        // 刚拨开开关、服务还没 onCreate 完，这段时间别照着 isRunning()=false 写 prefs。
        if (System.currentTimeMillis() < pendingStartUntil) return;
        boolean actual = FloatingService.isRunning();
        if (prefs.getBoolean("floating_enabled", false) != actual) {
            prefs.edit().putBoolean("floating_enabled", actual).apply();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        rootScroll = scroll;

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);

        // 标题
        TextView title = new TextView(this);
        title.setText("二进制悬浮窗");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(pal.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("桌面上一个小球，点开就能把剪切板里的文字转成二进制，或者把二进制译回文字");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(pal.muted);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        root.addView(sub, subLp);

        // 状态卡
        LinearLayout statusCard = makeCard();
        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(pal.text);
        statusCard.addView(statusView);

        permView = new TextView(this);
        permView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        permView.setTextColor(pal.muted);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        statusCard.addView(permView, plp);

        Button grant = makeButton("① 授予悬浮窗权限", true);
        grant.setOnClickListener(v -> {
            if (canDrawOverlays()) {
                toast("已经有权限了");
                return;
            }
            requestOverlayPermission();
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        glp.topMargin = dp(12);
        statusCard.addView(grant, glp);

        // 开关
        addFloatingSwitch();

        // 外观
        addSectionTitle("外观");
        addThemeRow();
        addBubbleLookRow();

        // 用法
        addSectionTitle("怎么用");
        LinearLayout howCard = makeCard();
        TextView how = new TextView(this);
        how.setText("1. 在任意 App 里复制一段文字或一串 0/1\n"
                + "2. 点桌面上的「01」小球，面板会自动读剪切板并转换\n"
                + "3. 点「复制」把结果放回剪切板，粘到别处去\n\n"
                + "面板上还有输入框，不想走剪切板就直接在里面打字。\n"
                + "「⇄」切换转换方向（文字↔二进制、文字↔Base64），\n"
                + "「8位/4位/连写/HEX」切换二进制的显示格式。\n"
                + "「✕」收起面板（悬浮球还在），长按「✕」才彻底关闭悬浮窗。\n"
                + "长按悬浮球弹出快捷菜单：切方向、读剪切板、调球大小和不透明度。\n"
                + "拖动标题栏可以把面板挪走，松手就固定在那个位置；\n"
                + "点「面板归位」让它重新跟着球走。\n\n"
                + "面板底部那排小胶囊是最近 20 次输入，点一下就能回到那次内容。");
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        how.setTextColor(pal.text);
        how.setLineSpacing(dp(5), 1f);
        howCard.addView(how);

        // 关于
        addSectionTitle("关于");
        LinearLayout aboutCard = makeCard();
        TextView about = new TextView(this);
        about.setText("编码：UTF-8\n"
                + "解码：UTF-8 → GBK / Big5（自动挑更像正文的）→ Latin-1 兜底\n"
                + "二进制支持格式：空格、逗号、分号、下划线、短横线、0b 前缀、完全连写\n"
                + "二进制判定：去掉分隔符后至少 16 位；不超过 24 位时还必须同时含 0 和 1\n"
                + "Base64：标准/URL 安全字母表都认，缺 padding 会自动补齐；"
                + "解出的字节必须是合法 UTF-8 才当作 Base64\n"
                + "长度上限：二进制单次 20000 位（约 2500 字节）");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setTextColor(pal.muted);
        about.setLineSpacing(dp(3), 1f);
        aboutCard.addView(about);

        // 数据
        addSectionTitle("数据");
        LinearLayout dataCard = makeCard();
        TextView dataNote = new TextView(this);
        dataNote.setText("转换历史、球的位置、外观设置都只存在本机，App 没有联网权限。");
        dataNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dataNote.setTextColor(pal.muted);
        dataCard.addView(dataNote);

        Button clearHist = makeButton("清空转换历史", false);
        clearHist.setOnClickListener(v -> clearHistory());
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        chlp.topMargin = dp(12);
        dataCard.addView(clearHist, chlp);

        // 版本 + 重置
        addSectionTitle("版本");
        LinearLayout verCard = makeCard();
        TextView ver = new TextView(this);
        ver.setText("v" + versionName() + " (" + versionCode() + ")");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setTextColor(pal.muted);
        verCard.addView(ver);

        Button reset = makeButton("重置所有设置", false);
        reset.setOnClickListener(v -> resetSettings());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rlp.topMargin = dp(12);
        verCard.addView(reset, rlp);

        addSectionTitle("为什么不能自动监听剪切板");
        LinearLayout whyCard = makeCard();
        TextView why = new TextView(this);
        why.setText("Android 10 起，没有焦点的应用读剪切板一律拿到空内容。"
                + "后台自动监听只有 root 或无障碍服务两条路，都不该为一个转换工具申请。\n\n"
                + "所以这里点一下才读一次：点小球 → 面板抢到焦点 → 这时读取合法 → 结果直接填好。"
                + "代价是点的那一下会收起当前键盘，换来的是不需要任何敏感权限。");
        why.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        why.setTextColor(pal.muted);
        why.setLineSpacing(dp(3), 1f);
        whyCard.addView(why);

        return scroll;
    }

    /** 主题三档：跟随系统 / 浅色 / 深色。 */
    private void addThemeRow() {
        LinearLayout card = makeCard();
        TextView t = new TextView(this);
        t.setText("配色");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(pal.text);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText("悬浮窗面板和这个界面一起变");
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setTextColor(pal.muted);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        card.addView(d, dlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        rlp.topMargin = dp(10);
        card.addView(row, rlp);

        int current = prefs.getInt("theme_mode", Palette.MODE_SYSTEM);
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            boolean on = current == i;
            TextView btn = new TextView(this);
            btn.setText(Palette.modeLabel(i));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(on ? pal.text : pal.muted);
            btn.setBackgroundColor(on ? pal.cardHi : pal.card);
            btn.setOnClickListener(v -> applyTheme(mode));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(btn, lp);
        }
    }

    /** 球大小 / 不透明度，和长按菜单里那两个循环按钮共用同一份设置。 */
    private void addBubbleLookRow() {
        LinearLayout card = makeCard();
        TextView t = new TextView(this);
        t.setText("悬浮球外观");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(pal.text);
        card.addView(t);

        int scale = prefs.getInt("bubble_scale", 1);
        int alpha = prefs.getInt("bubble_alpha", 1);
        // 记下这一版界面画的是哪两档，onResume 靠它判断要不要重建（见 shownScale 注释）。
        shownScale = scale;
        shownAlpha = alpha;
        String[] scaleLabels = {"小", "中", "大"};
        int[] alphas = {100, 80, 60, 40};

        TextView info = new TextView(this);
        info.setText("大小：" + scaleLabels[Math.max(0, Math.min(2, scale))]
                + "　不透明度：" + alphas[Math.max(0, Math.min(3, alpha))] + "%"
                + "（也可以在长按球的菜单里调）");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        info.setTextColor(pal.muted);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(3);
        card.addView(info, ilp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        rlp.topMargin = dp(10);
        card.addView(row, rlp);

        TextView smaller = smallBtn("球小一点");
        smaller.setOnClickListener(v -> nudgeBubble(-1));
        row.addView(smaller, weight(1f));
        TextView bigger = smallBtn("球大一点");
        bigger.setOnClickListener(v -> nudgeBubble(1));
        row.addView(bigger, weight(1f));
        TextView fainter = smallBtn("更透明");
        fainter.setOnClickListener(v -> nudgeAlpha(-1));
        row.addView(fainter, weight(1f));
        TextView solid = smallBtn("更实");
        solid.setOnClickListener(v -> nudgeAlpha(1));
        row.addView(solid, weight(1f));
    }

    /**
     * 改球大小。服务在跑就让它自己重读设置，不用重启服务 ——
     * 重启会把用户正在用的面板一起关掉。
     */
    private void nudgeBubble(int delta) {
        int v = prefs.getInt("bubble_scale", 1) + delta;
        v = Math.max(0, Math.min(2, v));
        prefs.edit().putInt("bubble_scale", v).apply();
        applyLookToService();
        rebuildUiKeepingScroll();
        refreshStatus();
    }

    private void nudgeAlpha(int delta) {
        int v = prefs.getInt("bubble_alpha", 1) + delta;
        v = Math.max(0, Math.min(3, v));
        prefs.edit().putInt("bubble_alpha", v).apply();
        applyLookToService();
        rebuildUiKeepingScroll();
        refreshStatus();
    }

    /**
     * 重建界面但保住滚动位置。
     *
     * 点「球大一点 / 更透明」要立刻看到按钮上的新档位，只能重建 UI；
     * 但直接 setContentView(buildUi()) 会把滚动位置打回顶部，
     * 用户在外观区连点几下就得反复往下滚。
     */
    private void rebuildUiKeepingScroll() {
        int y = rootScroll == null ? 0 : rootScroll.getScrollY();
        setContentView(buildUi());
        if (rootScroll != null) {
            final int keep = y;
            rootScroll.post(() -> rootScroll.scrollTo(0, keep));
        }
    }

    /**
     * 外观改了就通知服务立刻生效。
     * 用 SHOW_PANEL 会顺手把面板弹出来，这里不合适，所以复用 ACTION_STOP 之外的
     * 那个「只重读外观」的动作 —— 服务里 onStartCommand 收到它只刷球样式。
     *
     * 必须先判 isRunning()：悬浮窗关着的时候 startService 会把服务**新建**出来，
     * 于是点一下「球大一点 / 配色 / 清空转换历史」就凭空冒出悬浮球和常驻通知，
     * 开关却还显示着「关」—— 用户会以为中病毒了。
     */
    private void applyLookToService() {
        if (!FloatingService.isRunning()) return;
        try {
            Intent i = new Intent(this, FloatingService.class)
                    .setAction(FloatingService.ACTION_REFRESH_LOOK);
            startService(i);
        } catch (Exception ignored) {
        }
    }

    private void applyTheme(int mode) {
        prefs.edit().putInt("theme_mode", mode).apply();
        pal = Palette.get(this);
        setContentView(buildUi());
        refreshStatus();
        applySystemBars();
        // 悬浮窗是同一个进程里的窗口视图，叫它自己重画一遍。
        applyLookToService();
    }

    /**
     * 状态栏 / 导航栏跟着主题走。
     *
     * 以前 styles.xml 里把 statusBarColor 钉死成 @color/bg（深色），浅色主题下
     * 状态栏还是深色底、白色图标 —— 看着突兀；反过来深色主题里如果系统栏是浅底
     * 白图标就直接看不见了。这里按当前配色同时设「栏背景色」和「图标明暗」。
     */
    private void applySystemBars() {
        try {
            Window w = getWindow();
            boolean dark = Palette.get(this) == Palette.DARK;
            w.setStatusBarColor(pal.bg);
            w.setNavigationBarColor(pal.bg);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    // 「浅色图标」的开关是反的：深色主题要亮图标（关掉 LIGHT），
                    // 浅色主题要暗图标（打开 LIGHT）。
                    c.setSystemBarsAppearance(dark ? 0 : light, light);
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                w.getDecorView().setSystemUiVisibility(dark ? 0 : flags);
            }
        } catch (Exception ignored) {
        }
    }

    private void clearHistory() {
        prefs.edit().putString("history", "").apply();
        applyLookToService();
        toast("转换历史已清空");
    }

    private void addSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(pal.text);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(10);
        root.addView(tv, lp);
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(pal.card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(card, lp);
        return card;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private TextView smallBtn(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(pal.text);
        tv.setBackgroundColor(pal.cardHi);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button makeButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? (pal == Palette.DARK ? 0xFFCFE0FF : pal.text) : pal.muted);
        b.setBackgroundColor(primary ? (pal == Palette.DARK ? 0xFF2B3A5E : pal.accent) : pal.cardHi);
        if (primary && pal != Palette.DARK) b.setTextColor(0xFFFFFFFF);
        return b;
    }

    /** 悬浮窗开关：需要 SYSTEM_ALERT_WINDOW 权限，勾选时申请并启停服务。 */
    private void addFloatingSwitch() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundColor(pal.card);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(card, lp);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText("启用悬浮窗");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(pal.text);
        textBox.addView(t);

        TextView d = new TextView(this);
        d.setText("桌面上的小悬浮球，点开可双向转换（需悬浮窗权限）");
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setTextColor(pal.muted);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        textBox.addView(d, dlp);

        floatingSwitch = new Switch(this);
        floatingSwitch.setChecked(isFloatingOn());
        floatingSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (syncingSwitch) return;
            if (checked) {
                if (!canDrawOverlays()) {
                    toast("请先允许「显示在其他应用上层」");
                    // 回滚时必须屏蔽监听器：直接 setChecked(false) 会再进一次
                    // else 分支，白跑一次 stopService 并弹出「已关闭」的相反提示。
                    syncingSwitch = true;
                    btn.setChecked(false);
                    syncingSwitch = false;
                    requestOverlayPermission();
                    return;
                }
                prefs.edit().putBoolean("floating_enabled", true).apply();
                startFloating();
            } else {
                prefs.edit().putBoolean("floating_enabled", false).apply();
                stopFloating();
            }
            refreshStatus();
        });
        card.addView(floatingSwitch);
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开悬浮窗权限设置");
        }
    }

    private void startFloating() {
        try {
            startForegroundService(new Intent(this, FloatingService.class));
            // 服务起来之前 isRunning() 还是 false，先记一个宽限期，
            // 期间 onResume 不要拿 false 去覆盖 prefs。
            pendingStartUntil = System.currentTimeMillis() + 2000L;
            // 不再立刻报「已开启」：服务可能随后因权限/窗口错误自毁，
            // 两条 toast 会互相打脸。状态交给 refreshStatus() 读事实。
            toast("正在开启悬浮窗…");
            if (floatingSwitch != null) {
                floatingSwitch.postDelayed(this::refreshStatus, 300);
            }
        } catch (Exception e) {
            toast("启动悬浮窗失败：" + e.getMessage());
        }
    }

    private void stopFloating() {
        try {
            stopService(new Intent(this, FloatingService.class));
            toast("悬浮窗已关闭");
        } catch (Exception e) {
            // 忽略
        }
    }

    private void refreshStatus() {
        boolean perm = canDrawOverlays();
        boolean on = isFloatingOn();
        boolean fgOk = FloatingService.isForegroundOk();

        if (perm) {
            permView.setText("权限：已授予");
            permView.setTextColor(pal.ok);
        } else {
            permView.setText("权限：未授予（没有它悬浮球显示不出来）");
            permView.setTextColor(pal.warn);
        }

        StringBuilder sb = new StringBuilder();
        if (!perm) {
            sb.append("⚠️ 还差一步\n\n点下面的按钮，在系统设置里允许「显示在其他应用上层」。");
            statusView.setTextColor(pal.warn);
        } else if (on) {
            sb.append("✅ 悬浮窗运行中\n\n桌面上应该能看到「01」小球。点它打开面板。");
            if (!fgOk) {
                sb.append("\n\n⚠️ 前台服务通知没挂上，"
                        + "系统可能会把它回收掉。请检查通知权限。");
            }
            statusView.setTextColor(fgOk ? pal.ok : pal.warn);
        } else {
            sb.append("⚙️ 权限已就绪，但悬浮窗没开\n\n把下面的「启用悬浮窗」打开。");
            statusView.setTextColor(pal.warn);
        }
        statusView.setText(sb.toString());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 清掉所有偏好并重启服务，让用户能把调乱的设置恢复默认。 */
    private void resetSettings() {
        try {
            prefs.edit().clear().apply();
        } catch (Exception ignored) {
        }
        try {
            stopService(new Intent(this, FloatingService.class));
        } catch (Exception ignored) {
        }
        pal = Palette.get(this);
        setContentView(buildUi());
        if (floatingSwitch != null) {
            syncingSwitch = true;
            floatingSwitch.setChecked(false);
            syncingSwitch = false;
        }
        refreshStatus();
        toast("已恢复默认设置");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
