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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：开启悬浮窗 + 说明 + 试一下。
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0F1117;
    private static final int CARD = 0xFF171B24;
    private static final int CARD_HI = 0xFF1D2230;
    private static final int TEXT = 0xFFE6EBF5;
    private static final int MUTED = 0xFF8992A7;
    private static final int ACCENT = 0xFF7AA2FF;
    private static final int TEAL = 0xFF5EEAD4;
    private static final int OK = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView permView;
    private LinearLayout root;
    private Switch floatingSwitch;
    /** 回填开关状态时置位，避免 setChecked() 反过来触发监听器。 */
    private boolean syncingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("binf", Context.MODE_PRIVATE);
        setContentView(buildUi());
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
        boolean actual = FloatingService.isRunning();
        if (prefs.getBoolean("floating_enabled", false) != actual) {
            prefs.edit().putBoolean("floating_enabled", actual).apply();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);

        // 标题
        TextView title = new TextView(this);
        title.setText("二进制悬浮窗");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("桌面上一个小球，点开就能把剪切板里的文字转成二进制，或者把二进制译回文字");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        root.addView(sub, subLp);

        // 状态卡
        LinearLayout statusCard = makeCard();
        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(TEXT);
        statusCard.addView(statusView);

        permView = new TextView(this);
        permView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        permView.setTextColor(MUTED);
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

        // 用法
        addSectionTitle("怎么用");
        LinearLayout howCard = makeCard();
        TextView how = new TextView(this);
        how.setText("1. 在任意 App 里复制一段文字或一串 0/1\n"
                + "2. 点桌面上的「01」小球，面板会自动读剪切板并转换\n"
                + "3. 点「复制」把结果放回剪切板，粘到别处去\n\n"
                + "面板上还有输入框，不想走剪切板就直接在里面打字。\n"
                + "「⇄」切换转换方向，「8位/4位/连写/HEX」切换显示格式。\n"
                + "「✕」收起面板（悬浮球还在），长按「✕」才彻底关闭悬浮窗。\n"
                + "长按悬浮球也能弹出快捷菜单。");
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        how.setTextColor(TEXT);
        how.setLineSpacing(dp(5), 1f);
        howCard.addView(how);

        // 关于
        addSectionTitle("关于");
        LinearLayout aboutCard = makeCard();
        TextView about = new TextView(this);
        about.setText("编码：UTF-8\n"
                + "解码：UTF-8 → GBK / Big5（自动挑更像正文的）→ Latin-1 兜底\n"
                + "支持格式：空格、逗号、分号、下划线、短横线、0b 前缀、完全连写\n"
                + "判定阈值：去掉分隔符后至少 16 位；不超过 24 位时还必须同时含 0 和 1\n"
                + "长度上限：单次最多 20000 位（约 2500 字节）");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setTextColor(MUTED);
        about.setLineSpacing(dp(3), 1f);
        aboutCard.addView(about);

        // 版本 + 重置
        addSectionTitle("版本");
        LinearLayout verCard = makeCard();
        TextView ver = new TextView(this);
        ver.setText("v" + versionName() + " (" + versionCode() + ")");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setTextColor(MUTED);
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
        why.setTextColor(MUTED);
        why.setLineSpacing(dp(3), 1f);
        whyCard.addView(why);

        return scroll;
    }

    private void addSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(TEXT);
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
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(card, lp);
        return card;
    }

    private Button makeButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? 0xFFCFE0FF : MUTED);
        b.setBackgroundColor(primary ? 0xFF2B3A5E : CARD_HI);
        return b;
    }

    /** 悬浮窗开关：需要 SYSTEM_ALERT_WINDOW 权限，勾选时申请并启停服务。 */
    private void addFloatingSwitch() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundColor(CARD);
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
        t.setTextColor(TEXT);
        textBox.addView(t);

        TextView d = new TextView(this);
        d.setText("桌面上的小悬浮球，点开可双向转换（需悬浮窗权限）");
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setTextColor(MUTED);
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
            permView.setTextColor(OK);
        } else {
            permView.setText("权限：未授予（没有它悬浮球显示不出来）");
            permView.setTextColor(WARN);
        }

        StringBuilder sb = new StringBuilder();
        if (!perm) {
            sb.append("⚠️ 还差一步\n\n点下面的按钮，在系统设置里允许「显示在其他应用上层」。");
            statusView.setTextColor(WARN);
        } else if (on) {
            sb.append("✅ 悬浮窗运行中\n\n桌面上应该能看到「01」小球。点它打开面板。");
            if (!fgOk) {
                sb.append("\n\n⚠️ 前台服务通知没挂上，"
                        + "系统可能会把它回收掉。请检查通知权限。");
            }
            statusView.setTextColor(fgOk ? OK : WARN);
        } else {
            sb.append("⚙️ 权限已就绪，但悬浮窗没开\n\n把下面的「启用悬浮窗」打开。");
            statusView.setTextColor(WARN);
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
