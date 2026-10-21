package com.binf.ime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 可选悬浮窗。
 *
 * 说明：Android 10+ 禁止后台应用读取剪切板，所以这里不主动监听，
 * 而是用户点「读剪切板」时才读取一次（属于用户手势触发）。
 */
public class FloatingService extends Service {

    private static final String CHANNEL_ID = "binf_floating";
    private static final int NOTIF_ID = 1001;

    private static final int BG = 0xFF0F1117;
    private static final int CARD = 0xFF171B24;
    private static final int CARD_HI = 0xFF1D2230;
    private static final int TEXT = 0xFFE6EBF5;
    private static final int MUTED = 0xFF8992A7;
    private static final int TEAL = 0xFF5EEAD4;
    private static final int ACCENT = 0xFF7AA2FF;
    private static final int ERR = 0xFFF87171;

    private WindowManager wm;
    private LinearLayout bubble;
    private LinearLayout panel;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager.LayoutParams panelLp;
    private boolean panelVisible = false;

    private EditText input;
    private TextView output;
    private TextView status;
    private boolean textToBin = true;
    private boolean compact = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForegroundSafe();
        createBubble();
    }

    private void startForegroundSafe() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("二进制悬浮窗运行中")
                    .setContentText("点按悬浮按钮打开面板")
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIF_ID, n);
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ---------- 悬浮按钮 ----------

    private void createBubble() {
        bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundColor(0xE60F1117);
        bubble.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView t = new TextView(this);
        t.setText("01");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextColor(TEAL);
        t.setGravity(Gravity.CENTER);
        bubble.addView(t);

        TextView c = new TextView(this);
        c.setText("BIN");
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        c.setTextColor(ACCENT);
        c.setGravity(Gravity.CENTER);
        bubble.addView(c);

        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(8);
        bubbleLp.y = dp(220);

        setupDrag(bubble, bubbleLp, true);
        bubble.setOnClickListener(v -> togglePanel());

        try {
            wm.addView(bubble, bubbleLp);
        } catch (Exception e) {
            toast("悬浮窗没有权限，请先在设置里授权");
            stopSelf();
        }
    }

    /** 拖动 + 点按区分。 */
    private void setupDrag(View v, final WindowManager.LayoutParams lp, final boolean isBubble) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                            if (isBubble && panelVisible) syncPanelPos();
                        }
                        return moved;
                    default:
                        return false;
                }
            }
        });
    }

    // ---------- 面板 ----------

    private void togglePanel() {
        if (panelVisible) hidePanel(); else showPanel();
    }

    private void showPanel() {
        if (panel == null) panel = buildPanel();
        panelLp = new WindowManager.LayoutParams(
                dp(292),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        syncPanelPosTo(panelLp);
        try {
            wm.addView(panel, panelLp);
            panelVisible = true;
        } catch (Exception e) {
            toast("无法显示面板");
        }
    }

    private void syncPanelPos() {
        if (panelLp != null && panelVisible) syncPanelPosTo(panelLp);
    }

    private void syncPanelPosTo(WindowManager.LayoutParams lp) {
        lp.x = Math.max(dp(4), bubbleLp.x);
        lp.y = Math.max(dp(4), bubbleLp.y + dp(56));
    }

    private void hidePanel() {
        if (panelVisible && panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panelVisible = false;
        }
    }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));

        // 标题栏
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("二进制悬浮窗");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView swap = smallBtn("⇄");
        swap.setOnClickListener(v -> { textToBin = !textToBin; refresh(); });
        head.addView(swap);

        TextView close = smallBtn("✕");
        close.setOnClickListener(v -> {
            hidePanel();
            stopSelf();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        head.addView(close, clp);

        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 方向说明
        final TextView dir = new TextView(this);
        dir.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dir.setTextColor(TEAL);
        dir.setPadding(0, dp(6), 0, dp(2));
        root.addView(dir);

        // 输入
        input = new EditText(this);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(TEXT);
        input.setHintTextColor(0xFF5B6579);
        input.setBackgroundColor(CARD_HI);
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        root.addView(input, ilp);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh(); }
        });

        // 输出
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(CARD_HI);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        slp.topMargin = dp(6);
        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextColor(TEAL);
        output.setPadding(dp(8), dp(6), dp(8), dp(6));
        output.setTextIsSelectable(true);
        sc.addView(output);
        root.addView(sc, slp);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        status.setTextColor(MUTED);
        status.setPadding(dp(2), dp(5), 0, 0);
        root.addView(status);

        // 按钮行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        rlp.topMargin = dp(6);
        root.addView(row, rlp);

        row.addView(actionBtn("复制", v -> copyOutput()), weight(1f));
        row.addView(actionBtn("读剪切板", v -> readClip()), weight(1.3f));
        row.addView(actionBtn("清空", v -> { input.setText(""); refresh(); }), weight(1f));

        refresh();
        return root;
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(MUTED);
        tv.setBackgroundColor(CARD_HI);
        tv.setPadding(dp(9), dp(4), dp(9), dp(4));
        return tv;
    }

    private TextView actionBtn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(TEXT);
        tv.setBackgroundColor(CARD_HI);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        return tv;
    }

    private void refresh() {
        if (input == null || output == null || status == null) return;
        String in = input.getText().toString();
        // 方向标签通过面板重建来更新，这里只更新输出
        if (in.isEmpty()) {
            output.setText("");
            status.setText(textToBin ? "输入文字，下方出二进制" : "粘贴 0/1 串，下方出译文");
            return;
        }
        if (textToBin) {
            String bin = BinCore.textToBinary(in, compact);
            output.setText(bin);
            output.setTextColor(TEAL);
            status.setText(BinCore.describe(in));
        } else {
            BinCore.DecodeResult r = BinCore.binaryToText(in);
            if (r.ok) {
                output.setText(r.text);
                output.setTextColor(TEXT);
                status.setText(r.message);
            } else {
                output.setText("无法解码：" + r.message);
                output.setTextColor(ERR);
                status.setText("");
            }
        }
    }

    private void copyOutput() {
        String s = output == null ? "" : output.getText().toString();
        if (s.isEmpty()) { toast("没有可复制的内容"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bin", s));
            toast("已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void readClip() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) { toast("剪切板是空的"); return; }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { toast("剪切板是空的"); return; }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            if (cs == null || cs.length() == 0) { toast("剪切板没有文字"); return; }
            String text = cs.toString();
            // 自动判断方向，二进制就译，文字就转
            if (BinCore.looksLikeBinary(text)) {
                textToBin = false;
            } else {
                textToBin = true;
            }
            input.setText(text);
            refresh();
            toast(textToBin ? "已转成二进制" : "已译成文字");
        } catch (Exception e) {
            toast("读取剪切板失败：" + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        hidePanel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
