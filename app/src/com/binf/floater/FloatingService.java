package com.binf.floater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * 悬浮窗：桌面上一个小气泡，点开就是转换面板。
 *
 * 设计取舍（重要，别改回去）：
 * Android 10 起，没有焦点的应用读剪切板一律拿到空内容。想「后台自动监听剪切板」
 * 只有两条路：root / 无障碍服务。都不该为这个工具申请。
 *
 * 所以这里的做法是：点气泡 → 面板浮起来并抢到焦点 → 这时读剪切板是合法的
 * → 读完立刻把结果填进面板。用户点一下，就完成一次「读 → 判断方向 → 出结果」。
 * 代价是点的那一下会收起当前键盘，换来的是不用申请任何敏感权限。
 */
public class FloatingService extends Service {

    public static final String CHANNEL_ID = "binf_floating";
    private static final int NOTIF_ID = 1001;
    /** 通知上的两个动作，交给 onStartCommand 处理。 */
    public static final String ACTION_SHOW_PANEL = "com.binf.floater.action.SHOW_PANEL";
    public static final String ACTION_STOP = "com.binf.floater.action.STOP";

    private static final int BG = 0xFF0F1117;
    private static final int CARD_HI = 0xFF1D2230;
    private static final int TEXT = 0xFFE6EBF5;
    private static final int MUTED = 0xFF8992A7;
    private static final int TEAL = 0xFF5EEAD4;
    private static final int ACCENT = 0xFF7AA2FF;
    private static final int ERR = 0xFFF87171;

    /** 输入防抖：每敲一个字就全量重算，长文本会卡死。 */
    private static final int REFRESH_DEBOUNCE_MS = 120;
    /** 面板打开后等这么久再读剪切板（要等焦点交接完）。 */
    private static final int CLIP_DELAY_MS = 120;
    /** 一次最多显示多少字符，超出截断显示（复制仍给全文）。 */
    private static final int MAX_SHOW_CHARS = 20000;
    /** 复制走 binder，超过这个量容易撞 TransactionTooLargeException。 */
    private static final int MAX_COPY_CHARS = 500000;
    /**
     * 分享也走 binder（Intent extras），但上限比剪切板低得多。
     * 超过就别往 extras 里塞了 —— 会抛 TransactionTooLargeException。
     */
    private static final int MAX_SHARE_CHARS = 100000;
    /** 长按多久算长按。 */
    private static final int LONG_PRESS_MS = 450;
    /** 点外面收起面板后，这么短时间内忽略气泡点击，否则会「收了又开」。 */
    private static final int SUPPRESS_TOGGLE_MS = 350;

    private WindowManager wm;
    private LinearLayout bubble;
    private ScrollView panel;
    private LinearLayout menu;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager.LayoutParams panelLp;
    private WindowManager.LayoutParams menuLp;
    private boolean panelVisible = false;
    /** 当前软键盘高度（px）。0 表示键盘没弹。 */
    private int imeBottom = 0;

    private EditText input;
    private TextView output;
    private TextView status;
    private TextView dirLabel;
    private TextView modeBtn;
    private boolean textToBin = true;
    /** 用户手动按过 ⇄ 之后，自动识别就不再改方向。 */
    private boolean dirPinnedByUser = false;
    /** group8 / group4 / compact / hex。 */
    private String renderMode = "group8";
    /** 未截断的完整输出，「复制」用它。 */
    private String fullOutput = "";
    /** 上次自动读到的剪切板内容，用来判断「面板重开时要不要覆盖」。 */
    private String lastClip = null;
    private long suppressToggleUntil = 0;

    private Handler handler;
    /** 打开面板时自动读一次剪切板的标记，避免面板重绘时反复读。 */
    private boolean autoReadPending = false;
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() { refresh(); }
    };

    /**
     * 服务是否真的活着。主界面拿它当事实源 —— 只信 SharedPreferences 会在
     * 「进程被系统回收 / 被强停」时撒谎：偏好还是 true，桌面却没有球。
     */
    private static volatile boolean running = false;
    /** startForeground 是否成功。失败时系统会报「没调 startForeground」并可能 ANR。 */
    private static volatile boolean foregroundOk = false;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isForegroundOk() {
        return foregroundOk;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // 服务真的起来了，开关状态就跟着「开」。和 onDestroy 里的回写配对，
        // 不管是从主界面开、被系统重启、还是长按 ✕ 关，主界面显示的永远是实际状态。
        setEnabledPref(true);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        SharedPreferences p = prefs();
        renderMode = p.getString("render_mode", "group8");
        startForegroundSafe();
        createBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            setEnabledPref(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW_PANEL.equals(action) && bubble != null) {
            showPanel();
            return START_STICKY;
        }
        // START_STICKY：前台服务被系统回收后让系统有机会重建。
        // 默认值 START_STICKY_COMPATIBILITY 语义上「不保证重建」，服务一死就永久消失。
        // 重建时会再走 onCreate，createBubble() 里有幂等保护，不会叠出两个球。
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("binf", Context.MODE_PRIVATE);
    }

    private void setEnabledPref(boolean on) {
        try {
            prefs().edit().putBoolean("floating_enabled", on).apply();
        } catch (Exception ignored) {
        }
    }

    private void startForegroundSafe() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // IMPORTANCE_MIN 会让通知基本不可见，用户就不知道有东西在跑、
                // 也找不到关闭入口。LOW：不响、但状态栏能看到。
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE);
            Intent show = new Intent(this, FloatingService.class).setAction(ACTION_SHOW_PANEL);
            PendingIntent showPi = PendingIntent.getService(this, 1, show,
                    PendingIntent.FLAG_IMMUTABLE);
            Intent stop = new Intent(this, FloatingService.class).setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("二进制悬浮窗运行中")
                    .setContentText("点按悬浮球打开转换面板")
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "打开面板", showPi).build())
                    .addAction(new Notification.Action.Builder(null, "关闭", stopPi).build())
                    .build();
            startForeground(NOTIF_ID, n);
            foregroundOk = true;
        } catch (Exception e) {
            // 不能静默吞掉：真抛了的话系统随后会报
            // "did not then call Service.startForeground()"，表现为 ANR 弹窗，
            // 而用户完全不知道悬浮窗其实没起来。记下状态让主界面能显示。
            foregroundOk = false;
            // 而且前台服务没挂上就不该继续跑：系统会把这种「startForegroundService
            // 后没调 startForeground」的进程直接判成 ANR。自己收掉，让用户重试。
            setEnabledPref(false);
            stopSelf();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    /** 状态栏高度，球的 y 不能进到它下面（那里点不到）。 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- 悬浮球 ----------

    private void createBubble() {
        // 幂等：服务被 START_STICKY 重建时会再走一次 onCreate，
        // 不做这个判断就会叠出两个球。
        if (bubble != null) return;

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

        // 气泡必须 NOT_FOCUSABLE：它只是个按钮，抢焦点会打断用户正在做的事。
        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        // 位置记忆：README 早就说「位置会记住」，之前每次重启都回左上角。
        SharedPreferences p = prefs();
        bubbleLp.x = p.getInt("bubble_x", dp(8));
        bubbleLp.y = p.getInt("bubble_y", dp(220));
        clampBubble();

        setupDrag(bubble, bubbleLp, true);
        bubble.setOnClickListener(v -> togglePanel());
        // 长按只留 setupDrag 里那一条自定义路径（postDelayed LONG_PRESS_MS）。
        // 以前这里还挂了 setOnLongClickListener —— View 自己在 ~400ms 就 performLongClick
        // 弹一次菜单，450ms 自定义 runnable 又调一次 showMenu()，撞上 showMenu() 开头的
        // 「已经开着就关掉」，菜单刚出来就自己消失，等于不可用。

        try {
            wm.addView(bubble, bubbleLp);
        } catch (Exception e) {
            // 以前不管什么原因都提示「没有权限」，误导用户反复授权。
            boolean perm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || android.provider.Settings.canDrawOverlays(this);
            String why = perm ? ("未知错误：" + e.getClass().getSimpleName())
                    : "悬浮窗没有权限，请先在设置里授权";
            toast(why);
            // addView 失败时视图并没挂到窗口上，但 bubble 字段已非空。
            // 不置回 null：幂等守卫会挡住后续重试，onDestroy 的 removeView 还会抛。
            bubble = null;
            bubbleLp = null;
            setEnabledPref(false);
            stopSelf();
        }
    }

    private int bubbleW() {
        return bubble == null || bubble.getWidth() <= 0 ? dp(56) : bubble.getWidth();
    }

    private int bubbleH() {
        return bubble == null || bubble.getHeight() <= 0 ? dp(56) : bubble.getHeight();
    }

    /** 把球夹在屏幕内，并且不让它钻进状态栏底下。 */
    private void clampBubble() {
        bubbleLp.x = clamp(bubbleLp.x, 0, screenW() - bubbleW());
        bubbleLp.y = clamp(bubbleLp.y, statusBarHeight(), screenH() - bubbleH());
    }

    /** 拖动 + 点按区分。 */
    private void setupDrag(View v, final WindowManager.LayoutParams lp, final boolean isBubble) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            boolean longPressed;
            final Runnable longPress = new Runnable() {
                @Override public void run() {
                    longPressed = true;
                    if (isBubble) showMenu();
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        longPressed = false;
                        if (isBubble && handler != null) {
                            handler.postDelayed(longPress, LONG_PRESS_MS);
                        }
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            moved = true;
                            if (handler != null) handler.removeCallbacks(longPress);
                        }
                        if (moved) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            if (isBubble) clampBubble();
                            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                            if (isBubble && panelVisible) syncPanelPos();
                        }
                        return moved;
                    }
                    case MotionEvent.ACTION_UP:
                        if (handler != null) handler.removeCallbacks(longPress);
                        // 关键：拖动或长按之后必须把 UP 吃掉（return true），
                        // 否则 View.onTouchEvent 会看到 pressed 仍为 true 而触发 performClick，
                        // 于是每拖一次球就开关一次面板。只有「没动过」才放行给点击。
                        if (moved || longPressed) {
                            if (moved && isBubble) {
                                snapToEdge();
                                saveBubblePos();
                                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
                            }
                            view.setPressed(false);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        if (handler != null) handler.removeCallbacks(longPress);
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    /** 拖到贴边时吸过去，省得留一条缝。 */
    private void snapToEdge() {
        if (bubbleLp == null) return;
        int edge = dp(16);
        if (bubbleLp.x <= edge) bubbleLp.x = 0;
        else if (bubbleLp.x + bubbleW() >= screenW() - edge) bubbleLp.x = screenW() - bubbleW();
    }

    private void saveBubblePos() {
        if (bubbleLp == null) return;
        try {
            prefs().edit().putInt("bubble_x", bubbleLp.x)
                    .putInt("bubble_y", bubbleLp.y).apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后球可能停在屏外（FLAG_LAYOUT_NO_LIMITS 不会被系统夹回来），
        // 面板坐标也要跟着重算。
        if (bubbleLp == null || wm == null || bubble == null) return;
        clampBubble();
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
        if (panelVisible) {
            syncPanelPos();
            capPanelHeight();
        }
        hideMenu();
    }

    // ---------- 面板 ----------

    private void togglePanel() {
        if (System.currentTimeMillis() < suppressToggleUntil) return;
        if (panelVisible) hidePanel(); else showPanel();
    }

    private void showPanel() {
        // 幂等守卫：面板已经开着时不能再 addView 一次。
        // 通知栏「打开面板」在面板已开时点一下，以前会重复 addView，
        // WindowManagerGlobal 抛 IllegalStateException，被 catch 转成「无法显示面板」。
        if (panelVisible) {
            panel.post(this::capPanelHeight);
            return;
        }
        if (panel == null) panel = buildPanel();
        hideMenu();
        // 面板要能输入、也要能读到剪切板 —— 所以它必须**可获焦**。
        // 只加 FLAG_NOT_TOUCH_MODAL，不加 FLAG_NOT_FOCUSABLE。
        int pw = Math.min(dp(292), (int) (screenW() * 0.9));
        panelLp = new WindowManager.LayoutParams(
                pw,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
        syncPanelPosTo(panelLp);
        try {
            wm.addView(panel, panelLp);
            panelVisible = true;
            // 软键盘遮面板：TYPE_APPLICATION_OVERLAY 上的 SOFT_INPUT_ADJUST_RESIZE
            // 对 IME 不一定生效，而且窗口高度是 WRAP_CONTENT，没有可压缩的余量。
            // 所以自己听 insets：键盘一弹就把面板整体上移，让出 IME 高度。
            panel.setOnApplyWindowInsetsListener((v, insets) -> {
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if (ime != imeBottom) {
                    imeBottom = ime;
                    syncPanelPos();
                }
                return insets;
            });
            // 面板刚拿到焦点，剪切板这时才读得到。等一小会儿再读。
            autoReadPending = true;
            handler.postDelayed(this::readClipIfPending, CLIP_DELAY_MS);
            // 高度可能超出屏幕（长文本），量完再夹一次。
            panel.post(this::capPanelHeight);
        } catch (Exception e) {
            toast("无法显示面板");
        }
    }

    /** 面板高度不超过屏幕 62%，超出部分靠面板内部滚动。 */
    private void capPanelHeight() {
        if (!panelVisible || panel == null || panelLp == null) return;
        int cap = (int) (screenH() * 0.62);
        int h = panel.getHeight();
        if (h > cap && panelLp.height != cap) {
            panelLp.height = cap;
            try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
        }
        syncPanelPos();
    }

    private void readClipIfPending() {
        if (!autoReadPending || !panelVisible) return;
        autoReadPending = false;
        readClip(true);
    }

    private void syncPanelPos() {
        if (panelLp != null && panelVisible && panel != null) {
            syncPanelPosTo(panelLp);
            // 以前只改了 lp 的 x/y 字段、不调 updateViewLayout，面板纹丝不动，
            // 拖着球走面板不跟 —— 球和面板脱节。
            try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
        }
    }

    private void syncPanelPosTo(WindowManager.LayoutParams lp) {
        if (bubbleLp == null || lp == null) return;
        int pw = lp.width > 0 ? lp.width : dp(292);
        int ph = lp.height > 0 ? lp.height : dp(240);
        // 以前只夹下界不夹上界：球拖到右边缘/底部时面板整块跑到屏外，
        // 面板其实「开着」但看不见，再点球又收起 —— 用户观感是「点了没反应」。
        lp.x = clamp(bubbleLp.x, dp(4), screenW() - pw - dp(4));
        // 键盘弹起时把面板往上挪 imeBottom，否则输入框以下的输出框/按钮全被盖住。
        int bottomLimit = screenH() - ph - dp(4) - imeBottom;
        lp.y = clamp(bubbleLp.y + bubbleH() + dp(8), statusBarHeight() + dp(4),
                bottomLimit);
    }

    private void hidePanel() {
        if (panelVisible && panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panelVisible = false;
            autoReadPending = false;
            imeBottom = 0;
            // 只清「还没执行的防抖刷新」，不能清全部消息。
            // 以前用 removeCallbacksAndMessages(null)，把 refreshRunnable 一起清了，
            // 而重开面板时 panel != null 又不会再 buildPanel/refresh，
            // 于是输出框停在上一次结果、和输入框里的文字对不上。
            if (handler != null) handler.removeCallbacks(refreshRunnable);
        }
    }

    // ---------- 长按菜单 ----------

    private TextView menuBtn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(TEXT);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        tv.setOnClickListener(l);
        return tv;
    }

    private void showMenu() {
        if (menu != null) { hideMenu(); return; }
        if (wm == null || bubbleLp == null) return;
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setBackgroundColor(CARD_HI);
        m.addView(menuBtn("⇄  切换方向", v -> {
            textToBin = !textToBin;
            dirPinnedByUser = true;
            updateDir();
            flushRefresh();
            hideMenu();
        }));
        m.addView(menuBtn("📋  读剪切板", v -> { hideMenu(); readClip(false); }));
        m.addView(menuBtn("✕  关闭悬浮窗", v -> {
            hideMenu();
            setEnabledPref(false);
            stopSelf();
        }));

        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        // 先用一个保守的估计值占位，addView 之后菜单量出真实宽高再重新夹一次。
        // 以前写死 dp(140)/dp(160) 猜尺寸：系统字体调大或文案变长时，
        // 菜单会跑出屏幕右边/下边，按钮点不到。
        menuLp.x = clamp(bubbleLp.x + bubbleW() + dp(6), 0, screenW() - dp(140));
        menuLp.y = clamp(bubbleLp.y, statusBarHeight(), screenH() - dp(160));
        m.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; }
            return false;
        });
        try {
            wm.addView(m, menuLp);
            menu = m;
            m.post(this::placeMenu);
        } catch (Exception e) {
            menu = null;
        }
    }

    /** 量出菜单真实宽高，再把它夹回屏幕内。 */
    private void placeMenu() {
        if (menu == null || menuLp == null || bubbleLp == null) return;
        int mw = menu.getWidth() > 0 ? menu.getWidth() : dp(140);
        int mh = menu.getHeight() > 0 ? menu.getHeight() : dp(160);
        menuLp.x = clamp(bubbleLp.x + bubbleW() + dp(6), 0, screenW() - mw);
        menuLp.y = clamp(bubbleLp.y, statusBarHeight(), screenH() - mh);
        try { wm.updateViewLayout(menu, menuLp); } catch (Exception ignored) {}
    }

    private void hideMenu() {
        if (menu != null && wm != null) {
            try { wm.removeView(menu); } catch (Exception ignored) {}
        }
        menu = null;
        menuLp = null;
    }

    // ---------- 面板 UI ----------

    private ScrollView buildPanel() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        // 面板能获焦，返回键要先还给面板，不然按 BACK 没反应（只能去点 ✕）。
        sc.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                hidePanel();
                return true;
            }
            return false;
        });
        sc.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                // 点面板外面收起面板。但点球本身也会产生 OUTSIDE，
                // 不抑制一下就会「收起 → 球点击 → 又打开」。
                suppressToggleUntil = System.currentTimeMillis() + SUPPRESS_TOGGLE_MS;
                hidePanel();
                return true;
            }
            return false;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        sc.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 显示模式：8位一组 / 4位一组 / 连写 / 十六进制
        modeBtn = smallBtn("8位");
        modeBtn.setContentDescription("切换显示模式");
        modeBtn.setOnClickListener(v -> cycleRenderMode());
        head.addView(modeBtn);

        TextView swap = smallBtn("⇄");
        swap.setContentDescription("切换转换方向");
        swap.setOnClickListener(v -> {
            textToBin = !textToBin;
            dirPinnedByUser = true;
            updateDir();
            flushRefresh();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(6);
        head.addView(swap, slp);

        // ✕ 只收起面板，悬浮球留着。
        // 以前这里是 hidePanel() + stopSelf()，一点整个前台服务就停了，
        // 悬浮球跟着消失，用户还得回主界面重新打开 —— 那是关掉程序，不是收面板。
        // 真要彻底关掉，长按这个按钮，或者在主界面关掉开关。
        TextView close = smallBtn("✕");
        close.setContentDescription("收起面板");
        close.setOnClickListener(v -> hidePanel());
        close.setOnLongClickListener(v -> {
            hidePanel();
            toast("悬浮窗已关闭");
            setEnabledPref(false);
            stopSelf();
            return true;
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        head.addView(close, clp);

        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 方向说明
        dirLabel = new TextView(this);
        dirLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dirLabel.setTextColor(TEAL);
        dirLabel.setPadding(0, dp(6), 0, dp(2));
        root.addView(dirLabel);

        // 输入
        input = new EditText(this);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(TEXT);
        input.setHintTextColor(0xFF5B6579);
        input.setBackgroundColor(CARD_HI);
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setMaxLines(3);
        // 只写 TYPE_CLASS_TEXT 是单行语义：回车变成「完成」，与 3 行输入框不符。
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSaveEnabled(false);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(4);
        root.addView(input, ilp);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { scheduleRefresh(); }
        });

        // 输出
        ScrollView out = new ScrollView(this);
        out.setBackgroundColor(CARD_HI);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        olp.topMargin = dp(6);
        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextColor(TEAL);
        output.setPadding(dp(8), dp(6), dp(8), dp(6));
        // 不设 setTextIsSelectable：外层已经有 ScrollView，长按选字会和滚动打架。
        // 要复制走「复制」按钮，够用。
        out.addView(output);
        root.addView(out, olp);

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
        row.addView(actionBtn("读剪切板", v -> readClip(false)), weight(1.3f));
        row.addView(actionBtn("分享", v -> shareOutput()), weight(1f));
        row.addView(actionBtn("清空", v -> { input.setText(""); flushRefresh(); }), weight(1f));

        updateDir();
        refresh();
        return sc;
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
        tv.setContentDescription(label);
        tv.setOnClickListener(l);
        return tv;
    }

    // ---------- 显示模式 ----------

    private void cycleRenderMode() {
        switch (renderMode) {
            case "group8":  renderMode = "group4";  break;
            case "group4":  renderMode = "compact"; break;
            case "compact": renderMode = "hex";     break;
            default:        renderMode = "group8";  break;
        }
        try { prefs().edit().putString("render_mode", renderMode).apply(); } catch (Exception ignored) {}
        if (modeBtn != null) modeBtn.setText(modeLabel());
        flushRefresh();
    }

    private String modeLabel() {
        switch (renderMode) {
            case "group4":  return "4位";
            case "compact": return "连写";
            case "hex":     return "HEX";
            default:        return "8位";
        }
    }

    private void updateDir() {
        if (dirLabel == null) return;
        dirLabel.setText(textToBin ? "文字 → 二进制" : "二进制 → 文字");
        if (modeBtn != null) modeBtn.setText(modeLabel());
    }

    /** 输入防抖：删掉待执行的刷新，立刻算一次（读剪切板/切方向/清空时用）。 */
    private void flushRefresh() {
        if (handler != null) handler.removeCallbacks(refreshRunnable);
        refresh();
    }

    private void scheduleRefresh() {
        if (handler == null) { refresh(); return; }
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    private void refresh() {
        if (input == null || output == null || status == null) return;
        String in = input.getText().toString();
        if (in.isEmpty()) {
            fullOutput = "";
            output.setText("");
            status.setText(textToBin ? "输入文字，下方出二进制" : "粘贴 0/1 串，下方出译文");
            return;
        }
        if (textToBin) {
            // 文字 -> 二进制方向也要有上限：位串 ≈ 字节数 × 9，
            // 以前这里不设限，粘一篇几百 KB 的文章会生成几十兆字符串、
            // 主线程直接卡死（renderBits 还会再拼一份带空格的）。
            byte[] bytes = in.getBytes(java.nio.charset.Charset.forName("UTF-8"));
            long bitsLen = (long) bytes.length * 8;
            if (bitsLen > BinCore.MAX_BITS) {
                fullOutput = "";
                output.setTextColor(ERR);
                output.setText("内容太长：" + BinCore.describe(in)
                        + "，转成二进制约 " + bitsLen + " 位，超过上限 "
                        + BinCore.MAX_BITS + " 位。请分段转换。");
                status.setText("");
                return;
            }
            String bits = BinCore.textToBinary(in, true);
            String shown = BinCore.renderBits(bits, renderMode);
            fullOutput = shown;
            setOutput(shown, TEAL);
            status.setText(BinCore.describe(in) + " · " + modeLabel());
        } else {
            BinCore.DecodeResult r = BinCore.binaryToText(in);
            if (r.ok) {
                fullOutput = r.text;
                setOutput(r.text, TEXT);
                status.setText(r.message);
            } else {
                fullOutput = "";
                output.setText("无法解码：" + r.message);
                output.setTextColor(ERR);
                status.setText("");
            }
        }
    }

    private void setOutput(String s, int color) {
        output.setTextColor(color);
        if (s.length() > MAX_SHOW_CHARS) {
            // 截断点不能落在 emoji 的代理对中间，否则末尾会显示成一个乱码方块。
            int cut = MAX_SHOW_CHARS;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            output.setText(s.substring(0, cut)
                    + "\n…（已截断显示，共 " + s.length() + " 字符；点「复制」取全文）");
        } else {
            output.setText(s);
        }
    }

    private void copyOutput() {
        String s = fullOutput == null || fullOutput.isEmpty()
                ? (output == null ? "" : output.getText().toString())
                : fullOutput;
        if (s.isEmpty()) { toast("没有可复制的内容"); return; }
        // 截断前先把「原本超没超」记下来：截断后长度正好等于上限，
        // 原来那句 `if (s.length() <= MAX_COPY_CHARS) toast("已复制")` 依然成立，
        // 于是「内容过大…」后面紧跟一句「已复制」，两条 toast 打架。
        boolean truncated = s.length() > MAX_COPY_CHARS;
        if (truncated) {
            s = s.substring(0, MAX_COPY_CHARS);
            toast("内容过大，已截断到 " + (MAX_COPY_CHARS / 1000) + "K 字符复制");
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bin", s));
            if (!truncated) toast("已复制");
        } catch (Exception e) {
            // 走 binder，太大时会抛 TransactionTooLargeException，别再统一报「复制失败」。
            String name = e.getClass().getSimpleName();
            toast("复制失败：" + name);
        }
    }

    private void shareOutput() {
        String s = fullOutput == null || fullOutput.isEmpty()
                ? (output == null ? "" : output.getText().toString())
                : fullOutput;
        if (s.isEmpty()) { toast("没有可分享的内容"); return; }
        // 分享把内容塞进 Intent extras，走的也是 binder。
        // 几十万字符会抛 TransactionTooLargeException，被 catch 成一句莫名其妙的「分享失败」。
        // 与其失败，不如提前说清楚。
        if (s.length() > MAX_SHARE_CHARS) {
            toast("内容太长（" + s.length() + " 字符），分享上限 "
                    + (MAX_SHARE_CHARS / 1000) + "K 字符；长内容请先点「复制」");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, s);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, "分享结果")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("分享失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 读一次剪切板并转换。
     *
     * @param silent 自动触发时为 true：剪切板空或内容没变就不弹 toast，免得每次都吵。
     */
    private void readClip(boolean silent) {
        if (input == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                if (!silent) toast("剪切板是空的");
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                if (!silent) toast("剪切板是空的");
                return;
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            if (cs == null || cs.length() == 0) {
                if (!silent) toast("剪切板没有文字");
                return;
            }
            String text = cs.toString();
            // 和当前输入一样就不动，避免用户正在手打时被覆盖。
            // 但手动点「读剪切板」时要给一句提示，否则用户以为按钮坏了。
            if (text.equals(input.getText().toString())) {
                if (!silent) toast("剪切板内容和当前输入一样");
                return;
            }
            // 自动读（面板刚打开）时，剪切板没变过就别覆盖上次没处理完的内容。
            if (silent && text.equals(lastClip)) return;
            lastClip = text;
            // 自动判断方向，但用户手动按过 ⇄ 之后以用户为准。
            String dir = BinCore.autoDetect(text);
            if (!dirPinnedByUser && !"unknown".equals(dir)) {
                textToBin = "bin2text".equals(dir);
            }
            input.setText(text);
            updateDir();
            flushRefresh();
            if (!silent) toast(textToBin ? "已转成二进制" : "已译成文字");
        } catch (Exception e) {
            if (!silent) toast("读取剪切板失败：" + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        foregroundOk = false;
        // 服务没了，开关就得跟着回到「关」。
        // 长按面板上的 ✕ 走的是 stopSelf()，不经过主界面的开关回调；
        // 系统回收服务也一样。少这一步，主界面会一直显示「✅ 悬浮窗运行中」，
        // 但桌面上根本没有小球 —— 用户还得回软件重新开关一次才能恢复。
        setEnabledPref(false);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        hideMenu();
        if (bubble != null && wm != null) {
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
