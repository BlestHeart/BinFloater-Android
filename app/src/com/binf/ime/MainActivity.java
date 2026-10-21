package com.binf.ime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主界面：引导启用输入法 + 设置 + 快速试用。
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
    private LinearLayout root;

    /** 与 BinImeService 保持一致。 */
    private static final int LAYOUT_QWERTY = 0;
    private static final int LAYOUT_T9 = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("binf", Context.MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
        title.setText("二进制输入法");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("在输入框里直接把文字转成二进制，也能把二进制译回文字");
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

        LinearLayout statusBtns = new LinearLayout(this);
        statusBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(12);
        statusCard.addView(statusBtns, sblp);

        Button step1 = makeButton("① 启用输入法", true);
        step1.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (Exception e) {
                toast("无法打开输入法设置");
            }
        });
        statusBtns.addView(step1, weightLp(1f, dp(44), dp(3)));

        Button step2 = makeButton("② 切换输入法", false);
        step2.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        statusBtns.addView(step2, weightLp(1f, dp(44), dp(3)));

        // 快速试用
        addSectionTitle("快速试用");
        LinearLayout tryCard = makeCard();

        TextView tryLabel = new TextView(this);
        tryLabel.setText("在这里试打字（切到二进制输入法后，点键盘上的「01」）");
        tryLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tryLabel.setTextColor(MUTED);
        tryCard.addView(tryLabel);

        EditText tryInput = new EditText(this);
        tryInput.setHint("点这里唤起键盘");
        tryInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tryInput.setTextColor(TEXT);
        tryInput.setHintTextColor(0xFF5B6579);
        tryInput.setBackgroundColor(CARD_HI);
        tryInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        tryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams tilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tilp.topMargin = dp(10);
        tryCard.addView(tryInput, tilp);

        // 设置
        addSectionTitle("设置");

        addSwitch("数字键盘自动开翻译面板",
                "在数字输入框里直接显示二进制面板",
                "auto_binary_on_number", false);

        addSwitch("启动时提示开启输入法",
                "打开 App 时检查输入法是否已启用",
                "show_guide", true);

        // ---------- 键盘 ----------
        addSectionTitle("键盘");

        addChoice("键盘布局", "九键用数字键输入拼音，全键盘是 26 键",
                "layout_mode", new String[]{"全键盘", "九键"}, LAYOUT_QWERTY);

        addSlider("底部留白", "避开 ColorOS 的「切换输入法」按钮和手势条，觉得压手就调大",
                "bottom_pad_dp", 0, 60, 26);

        addSwitch("云端补词",
                "本地词库没有的词，联网补几条候选（需要网络）",
                "cloud_on", true);

        // 悬浮窗开关：勾选时检查并申请权限，然后启停服务
        addFloatingSwitch();

        // 关于
        addSectionTitle("关于");
        LinearLayout aboutCard = makeCard();
        TextView about = new TextView(this);
        about.setText("编码：UTF-8\n"
                + "解码：UTF-8 → GBK → Big5 → Latin-1 自动回退\n"
                + "支持格式：空格、逗号、分号、下划线、短横线、0b 前缀、完全连写\n"
                + "判定阈值：去掉分隔符后至少 16 位，避免把 2024 之类误判");
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setTextColor(MUTED);
        about.setLineSpacing(dp(3), 1f);
        aboutCard.addView(about);

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

    private LinearLayout.LayoutParams weightLp(float w, int height, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, w);
        lp.setMargins(margin, 0, margin, 0);
        return lp;
    }

    /** 构建一个设置卡片：左边标题+描述，返回 (卡片, 左侧文本框)。 */
    private LinearLayout newSettingCard(String title, String desc, LinearLayout[] textBoxOut) {
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
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(TEXT);
        textBox.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            d.setTextColor(MUTED);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(3);
            textBox.addView(d, dlp);
        }

        if (textBoxOut != null && textBoxOut.length > 0) textBoxOut[0] = textBox;
        return card;
    }

    /**
     * 二选一/多选一设置：一行「上一个 | 当前 | 下一个」的切换条。
     * 用按钮而不是 RadioGroup，是为了避免在卡片里占太多竖向空间。
     */
    private void addChoice(String title, String desc, final String key,
                          final String[] options, int def) {
        LinearLayout card = newSettingCard(title, desc, null);

        int cur = prefs.getInt(key, def);
        if (cur < 0 || cur >= options.length) cur = def;

        final TextView valueView = new TextView(this);
        valueView.setText(options[cur]);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueView.setTextColor(TEAL);
        valueView.setGravity(Gravity.CENTER);
        valueView.setPadding(dp(14), dp(6), dp(14), dp(6));
        valueView.setBackgroundColor(CARD_HI);
        valueView.setClickable(true);
        card.addView(valueView);

        final int[] holder = {cur};
        valueView.setOnClickListener(v -> {
            holder[0] = (holder[0] + 1) % options.length;
            prefs.edit().putInt(key, holder[0]).apply();
            valueView.setText(options[holder[0]]);
            // 立刻通知输入法重排（若正在输入）
            notifyImeRefresh();
        });
    }

    /** 滑动条设置：拖动时实时保存。 */
    private void addSlider(String title, String desc, final String key,
                          final int min, final int max, int def) {
        // 滑块要独占一行，所以这里不用左右布局的卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(card, lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        head.addView(textBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(TEXT);
        textBox.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setTextColor(MUTED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        textBox.addView(d, dlp);

        int val = prefs.getInt(key, def);
        if (val < min) val = min;
        if (val > max) val = max;

        final TextView valueView = new TextView(this);
        valueView.setText(val + " dp");
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valueView.setTextColor(TEAL);
        valueView.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(valueView);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(val - min);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(6);
        card.addView(bar, blp);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = progress + min;
                valueView.setText(v + " dp");
                prefs.edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                notifyImeRefresh();
            }
        });
    }

    /** 通知正在运行的输入法重新读取偏好（不强制重启输入法）。 */
    private void notifyImeRefresh() {
        // InputMethodService 没有跨进程的刷新通道，这里靠下次弹出键盘时读偏好即可。
        // 但如果输入法正在显示，重启一次输入法界面能让设置立刻生效。
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm == null) return;
            ComponentName cn = new ComponentName(this, BinImeService.class);
            List<InputMethodInfo> list = imm.getEnabledInputMethodList();
            for (InputMethodInfo info : list) {
                if (info.getComponent().equals(cn)) {
                    // restartInput 会让下次弹出时重建视图
                    toast("已保存，重新弹出键盘后生效");
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void addSwitch(String title, String desc, final String key, boolean def) {
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
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(textBox, tlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(TEXT);
        textBox.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        d.setTextColor(MUTED);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        textBox.addView(d, dlp);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        card.addView(sw);
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

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean("floating_enabled", false));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                if (!canDrawOverlays()) {
                    toast("请先允许「显示在其他应用上层」");
                    requestOverlayPermission();
                    sw.setChecked(false);
                    return;
                }
                prefs.edit().putBoolean("floating_enabled", true).apply();
                startFloating();
            } else {
                prefs.edit().putBoolean("floating_enabled", false).apply();
                stopFloating();
            }
        });
        card.addView(sw);
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开悬浮窗权限设置");
        }
    }

    private void startFloating() {
        try {
            startForegroundService(new Intent(this, FloatingService.class));
            toast("悬浮窗已开启");
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
        boolean enabled = isImeEnabled();
        boolean selected = isImeSelected();

        StringBuilder sb = new StringBuilder();
        if (selected) {
            sb.append("✅ 已启用并使用中\n\n可以开始打字了。切到任意输入框，点键盘顶部的「二进制面板」。");
            statusView.setTextColor(OK);
        } else if (enabled) {
            sb.append("⚙️ 已启用，但还没切换过去\n\n点下面的「② 切换输入法」，选「二进制输入法」。");
            statusView.setTextColor(WARN);
        } else {
            sb.append("⚠️ 还没启用\n\n点下面的「① 启用输入法」，在列表里勾选「二进制输入法」。");
            statusView.setTextColor(WARN);
        }
        statusView.setText(sb.toString());
    }

    private boolean isImeEnabled() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return false;
            List<InputMethodInfo> list = imm.getEnabledInputMethodList();
            String target = new ComponentName(this, BinImeService.class).flattenToString();
            for (InputMethodInfo info : list) {
                if (info.getComponent().flattenToString().equals(target)) return true;
                if (info.getPackageName().equals(getPackageName())) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isImeSelected() {
        try {
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (cur == null) return false;
            return cur.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
