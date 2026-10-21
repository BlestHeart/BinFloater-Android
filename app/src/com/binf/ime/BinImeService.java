package com.binf.ime;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 二进制输入法（带拼音引擎）。
 *
 * 三种模式：
 *   中文拼音：输入拼音，候选栏选词上屏
 *   英文数字：直接上屏字母
 *   二进制面板：文字 ↔ 二进制双向转换
 */
public class BinImeService extends InputMethodService {

    // ---- 配色 ----
    private static final int BG = 0xFF0F1117;
    private static final int CARD = 0xFF171B24;
    private static final int CARD_HI = 0xFF1D2230;
    private static final int STROKE = 0xFF2A3142;
    private static final int TEXT = 0xFFE6EBF5;
    private static final int MUTED = 0xFF8992A7;
    private static final int ACCENT = 0xFF7AA2FF;
    private static final int TEAL = 0xFF5EEAD4;
    private static final int OK = 0xFF4ADE80;
    private static final int KEY_BG = 0xFF262D3B;

    private static final int PAGE_PINYIN = 0;
    private static final int PAGE_SYMBOL = 1;
    private static final int PAGE_BINARY = 2;

    private int page = PAGE_PINYIN;
    private boolean shiftOn = false;
    private boolean capsOn = false;

    // ---- 布局模式 ----
    /** 全键盘（26 键）。 */
    private static final int LAYOUT_QWERTY = 0;
    /** 九键（T9）。 */
    private static final int LAYOUT_T9 = 1;
    private int layoutMode = LAYOUT_QWERTY;
    /** 九键当前数字串，如 "64626"（= nihao）。 */
    private final StringBuilder t9Digits = new StringBuilder();

    private SharedPreferences prefs;
    private PinyinComposer composer;
    private PinyinEngine engine;
    private T9Engine t9;
    private SentenceDecoder decoder;
    private CloudSuggest cloud;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 云端补词结果，按提交时的拼音串缓存，过期就丢。 */
    private String cloudPinyin = "";
    private List<String> cloudWords = Collections.emptyList();

    // 视图
    private LinearLayout rootLayout;
    private LinearLayout candidateBar;
    private HorizontalScrollView candidateScroll;
    /** 候选栏右侧的「收起键盘」按钮。 */
    private TextView hideKbButton;
    private LinearLayout keyArea;
    private LinearLayout binaryPanel;
    private TextView tabPinyin, tabSymbol, tabBinary;
    /** 键盘底部的额外留白，用来避开 ColorOS 的「切换输入法」悬浮按钮 / 手势条。 */
    private FrameLayout bottomPad;
    /** 系统最近一次给出的底部 insets（px）。0 表示还没收到过。 */
    private int lastSystemBottom = 0;

    /** 九键左侧的拼音选择列。 */
    private LinearLayout t9PinyinColumn;
    /**
     * 用户在左侧拼音列里点选的拼音。空串 = 不锁定，让引擎按概率自己挑。
     * 只在九键模式下有意义，按数字键/上屏/清空时都要复位。
     */
    private String t9PickedPinyin = "";

    /** 长按连删用的定时器。onCreate 里建，onDestroy 里清。 */
    private Handler repeatHandler;    /** 连删任务，同一个实例反复 post，靠 removeCallbacks 取消。 */
    private Runnable repeatDelete;
    /** 连删的节拍下标，越大删得越快（见 startRepeatDelete）。 */
    private int repeatTick = 0;
    /** 每档节拍对应的间隔（ms）：按住越久删得越快，跟系统输入法手感一致。 */
    private static final int[] REPEAT_DELAYS = {120, 90, 65, 45, 32};
    /** 按住多久才开始连删。比系统默认的 400ms 短一点，手感更跟手。 */
    private static final int LONG_PRESS_DELAY_MS = 320;
    /** 连删是否已经启动过（用来吞掉松手时那次多余的点击）。 */
    private boolean repeatDeleteStarted;
    /** 退格键按下后延迟启动连删的占位任务。 */
    private final Runnable deleteHoldStarter = new Runnable() {
        @Override public void run() {
            repeatDeleteStarted = true;
            startRepeatDelete();
        }
    };

    // 二进制面板
    private EditText binInput;
    private TextView binOutput;
    private TextView binStatus;
    private boolean binTextToBin = true;
    private boolean binCompact = false;

    /** 整句解码时单个词的最大拼音字符数。 */
    private static final int MAX_KEY_CHARS = 20;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("binf", Context.MODE_PRIVATE);
        composer = new PinyinComposer(this);
        engine = PinyinEngine.get(this);
        t9 = T9Engine.getDefault();
        decoder = SentenceDecoder.forEngine(engine, MAX_KEY_CHARS);
        cloud = new CloudSuggest();
        repeatHandler = new Handler(Looper.getMainLooper());
        repeatDelete = new Runnable() {
            @Override public void run() {
                onBackspace();
                // 按住不退就继续排下一拍，节拍逐档加快
                if (repeatHandler != null) {
                    repeatHandler.postDelayed(this, delayForTick(repeatTick++));
                }
            }
        };
    }

    @Override
    public void onDestroy() {
        stopRepeatDelete();
        if (cloud != null) cloud.shutdown();
        super.onDestroy();
    }

    /** 长按节拍：越往后越快，到最后一档就定速。 */
    private int delayForTick(int tick) {
        return REPEAT_DELAYS[Math.min(tick, REPEAT_DELAYS.length - 1)];
    }

    /**
     * 开始连删。第一次由长按触发，删一下之后隔 delayForTick(0) 继续。
     * 为什么不用 OnLongClickListener 直接循环：那会在主线程里空转，
     * 必须靠 Handler 排队，按键事件才有机会插进来（比如松手）。
     */
    private void startRepeatDelete() {
        stopRepeatDelete();
        repeatTick = 0;
        if (repeatHandler == null) return;
        // 先立刻删一个，否则要等 120ms 才有反应，手感很钝
        onBackspace();
        repeatHandler.postDelayed(repeatDelete, delayForTick(repeatTick++));
    }

    private void stopRepeatDelete() {
        if (repeatHandler != null && repeatDelete != null) {
            repeatHandler.removeCallbacks(repeatDelete);
        }
        repeatTick = 0;
    }

    @Override
    public View onCreateInputView() {
        buildUi();
        return rootLayout;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // 每次弹出键盘都重读一遍偏好：onCreateInputView 一个会话只调一次，
        // 用户在设置页改了布局/留白/云端开关后，只靠它不会生效。
        reloadPrefs();
        // 用 resetComposing 而不是 composer.reset()：九键的数字串、云端在途结果也要一起清
        resetComposing();
        refreshCandidates();

        int cls = info == null ? InputType.TYPE_CLASS_TEXT
                : (info.inputType & InputType.TYPE_MASK_CLASS);

        // 数字/电话输入框自动切到二进制面板
        if ((cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE)
                && prefs.getBoolean("auto_binary_on_number", false)) {
            switchPage(PAGE_BINARY);
        } else if (page == PAGE_BINARY) {
            switchPage(PAGE_PINYIN);
        }
    }

    // ================= UI =================

    private void buildUi() {
        // 先读偏好，保证第一次 renderKeys 就用对布局
        layoutMode = prefs == null ? LAYOUT_QWERTY : prefs.getInt("layout_mode", LAYOUT_QWERTY);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(BG);

        // 顶部模式标签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(CARD);
        tabs.setPadding(dp(4), dp(3), dp(4), dp(3));

        tabPinyin = makeTab("拼音");
        tabSymbol = makeTab("符号");
        tabBinary = makeTab("二进制");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        tlp.setMargins(dp(3), 0, dp(3), 0);
        tabs.addView(tabPinyin, tlp);
        tabs.addView(tabSymbol, tlp);
        tabs.addView(tabBinary, tlp);
        rootLayout.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 候选栏 + 右侧「收起键盘」按钮。
        // 按钮放在这里而不是底排：候选栏每一页都看得见（符号页也留着），
        // 而且它是整块键盘里离拇指最远的地方，误触率最低。
        LinearLayout candRow = new LinearLayout(this);
        candRow.setOrientation(LinearLayout.HORIZONTAL);
        candRow.setBackgroundColor(CARD_HI);

        candidateScroll = new HorizontalScrollView(this);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setBackgroundColor(CARD_HI);
        candidateBar = new LinearLayout(this);
        candidateBar.setOrientation(LinearLayout.HORIZONTAL);
        candidateBar.setPadding(dp(6), 0, dp(6), 0);
        candidateScroll.addView(candidateBar);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        candRow.addView(candidateScroll, clp);

        // 收起键：点一下把输入法窗口收下去（等价于系统返回键的效果）
        hideKbButton = new TextView(this);
        // 用 ▼ 而不是 ⌄：U+2304 在不少安卓系统字体里缺字形，会显示成方块
        // （这个项目之前用 ⌖ 就踩过一次）。▼ 是 U+25BC，字体覆盖率高得多。
        hideKbButton.setText("▼");
        hideKbButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        hideKbButton.setGravity(Gravity.CENTER);
        hideKbButton.setTextColor(MUTED);
        hideKbButton.setBackgroundColor(CARD_HI);
        hideKbButton.setContentDescription("收起键盘");
        hideKbButton.setOnClickListener(v -> requestHideSelf(0));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(38));
        candRow.addView(hideKbButton, hlp);

        rootLayout.addView(candRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        // 键盘区
        keyArea = new LinearLayout(this);
        keyArea.setOrientation(LinearLayout.VERTICAL);
        keyArea.setPadding(dp(2), dp(2), dp(2), dp(4));
        rootLayout.addView(keyArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 二进制面板
        binaryPanel = new LinearLayout(this);
        binaryPanel.setOrientation(LinearLayout.VERTICAL);
        binaryPanel.setPadding(dp(8), dp(6), dp(8), dp(6));
        binaryPanel.setVisibility(View.GONE);
        rootLayout.addView(binaryPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部安全留白：避开 ColorOS 的「切换输入法」悬浮按钮和手势条。
        // 高度 = 用户在设置里调的值 + 系统导航栏 insets（见 applyInsets）。
        bottomPad = new FrameLayout(this);
        bottomPad.setBackgroundColor(BG);
        rootLayout.addView(bottomPad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildBinaryPanel();
        switchPage(PAGE_PINYIN);
        applyInsets();
        reloadPrefs();
    }

    /**
     * 处理 WindowInsets：
     *   · 底部：系统导航栏 / 手势条的高度，加进 bottomPad，避免按键被盖住
     *   · 左右：横屏时刘海、挖孔
     *
     * 目前输入法的 insets 在 API 30+ 由系统直接让位，但 ColorOS 把「切换输入法」
     * 悬浮按钮画在输入法之上，所以必须自己再留一截。
     */
    private void applyInsets() {
        if (rootLayout == null || bottomPad == null) return;
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                bottom = sys.bottom;
                left = sys.left;
                right = sys.right;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            applyBottomPad(bottom);
            applySideInset(left, right);
            // 交还给系统继续分发
            return insets;
        });
        rootLayout.requestApplyInsets();
    }

    /**
     * 底部留白高度。
     *
     * ColorOS 16 把「切换输入法」的胶囊按钮画在输入法窗口之上（不是 insets），
     * 系统不会给我们让位，所以必须自己留一截。取值取两者较大：
     *   · 用户在设置里调的 bottom_pad_dp（默认 26dp，够避开胶囊按钮）
     *   · 系统导航栏高度 + 8dp 呼吸位（手势条也不会压到最后一排键）
     */
    private void applyBottomPad(int systemBottom) {
        if (bottomPad == null) return;
        // 只在系统真的给了 insets 时更新；reloadPrefs 里传 0 表示「不知道」，
        // 这时沿用上次记下的真值，否则会把手势条高度抹掉。
        if (systemBottom > 0) lastSystemBottom = systemBottom;
        int extra = prefs == null ? 26 : prefs.getInt("bottom_pad_dp", 26);
        int px = Math.max(dp(Math.max(0, extra)), lastSystemBottom + dp(8));
        ViewGroup.LayoutParams lp = bottomPad.getLayoutParams();
        if (lp != null && lp.height != px) {
            lp.height = px;
            bottomPad.setLayoutParams(lp);
        }
    }

    /** 左右 insets 作为 padding，横屏/刘海屏不会被挡住。 */
    private void applySideInset(int left, int right) {
        if (rootLayout == null) return;
        if (rootLayout.getPaddingLeft() == left && rootLayout.getPaddingRight() == right) return;
        rootLayout.setPadding(left, rootLayout.getPaddingTop(), right, rootLayout.getPaddingBottom());
    }

    /** 重新按偏好刷新布局（设置页改完回来时用）。 */
    private void reloadPrefs() {
        if (prefs == null) return;
        int mode = prefs.getInt("layout_mode", LAYOUT_QWERTY);
        boolean changed = mode != layoutMode;
        layoutMode = mode;
        if (bottomPad != null) applyBottomPad(0);
        if (changed && keyArea != null) renderKeys();
    }

    private TextView makeTab(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(MUTED);
        tv.setBackgroundColor(CARD);
        tv.setOnClickListener(v -> {
            if (label.equals("拼音")) switchPage(PAGE_PINYIN);
            else if (label.equals("符号")) switchPage(PAGE_SYMBOL);
            else switchPage(PAGE_BINARY);
        });
        return tv;
    }

    private void switchPage(int p) {
        // 从二进制页切走时，先提交拼音状态无关内容
        if (page == PAGE_BINARY && p != PAGE_BINARY) {
            composer.reset();
        }
        page = p;

        boolean showKb = p != PAGE_BINARY;
        keyArea.setVisibility(showKb ? View.VISIBLE : View.GONE);
        binaryPanel.setVisibility(showKb ? View.GONE : View.VISIBLE);
        // 只藏候选列表，不藏整个候选行：右边那个收起键要一直在，
        // 否则用户在符号页/二进制页就收不起键盘了。
        // 用 INVISIBLE 而不是 GONE：候选列表带 weight=1f，GONE 掉之后
        // 收起键会滑到最左边，位置每页都在变，反而更难按。
        candidateScroll.setVisibility(p == PAGE_PINYIN ? View.VISIBLE : View.INVISIBLE);

        paintTab(tabPinyin, p == PAGE_PINYIN);
        paintTab(tabSymbol, p == PAGE_SYMBOL);
        paintTab(tabBinary, p == PAGE_BINARY);

        if (showKb) renderKeys();
        else refreshBinary();
    }

    private void paintTab(TextView tv, boolean on) {
        tv.setTextColor(on ? TEXT : MUTED);
        tv.setBackgroundColor(on ? CARD_HI : CARD);
        tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    // ---------- 键盘 ----------

    private void renderKeys() {
        keyArea.removeAllViews();

        if (page == PAGE_SYMBOL) {
            renderSymbolKeys();
            renderBottomRow();
            return;
        }
        if (layoutMode == LAYOUT_T9) {
            renderT9Keys();
            renderT9BottomRow();
            return;
        }
        renderPinyinKeys();
        renderBottomRow();
    }

    // ---------- 九键 ----------

    /**
     * 九键布局。数字键上带字母，点一下就往 t9Digits 里加一位数字，
     * 再把数字串展开成拼音去查词。
     */
    private void renderT9Keys() {
        // 左侧一列「拼音选择条」+ 右侧 3×3 数字键。
        // 九键的痛点是数字串到拼音是一对多：94664486 可能是 zhongguo 也可能是
        // xionghuo，光靠候选栏猜经常出不来想要的词。给一列可点的拼音让用户自己挑。
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        // 和全键盘的三行字母键等高：3 行 × (42 + 3 上边距)
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(45) * 3);
        keyArea.addView(wrap, wrapLp);

        t9PinyinColumn = new LinearLayout(this);
        t9PinyinColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                dp(56), ViewGroup.LayoutParams.MATCH_PARENT);
        colLp.setMargins(dp(2), dp(3), dp(1), 0);
        wrap.addView(t9PinyinColumn, colLp);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(grid, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        String[][] cells = {
                {"1", ""}, {"2", "abc"}, {"3", "def"},
                {"4", "ghi"}, {"5", "jkl"}, {"6", "mno"},
                {"7", "pqrs"}, {"8", "tuv"}, {"9", "wxyz"},
        };
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            rlp.topMargin = dp(3);
            grid.addView(row, rlp);
            for (int c = 0; c < 3; c++) {
                String[] cell = cells[r * 3 + c];
                addT9Key(row, cell[0], cell[1]);
            }
        }
        renderT9PinyinColumn();
    }

    /**
     * 左侧拼音选择列。列出当前数字串的几种可能拼音，点一下锁定。
     *
     * 锁定的拼音存进 {@link #t9PickedPinyin}：之后 buildCandidates 只用它，
     * 不再让引擎自己猜，直到用户再按数字键或者上屏。
     */
    private void renderT9PinyinColumn() {
        if (t9PinyinColumn == null) return;
        t9PinyinColumn.removeAllViews();

        List<String> pys = (t9 == null || t9Digits.length() == 0)
                ? Collections.<String>emptyList()
                : t9.pinyinListByScore(t9Digits.toString(), engine, 6);
        if (pys.isEmpty()) {
            // 没输入或切不出来时，这一列就空着，但宽度保留，避免数字键左右跳
            return;
        }

        int n = Math.min(pys.size(), 3);
        for (int i = 0; i < n; i++) {
            final String py = pys.get(i);
            boolean picked = py.equals(t9PickedPinyin);
            TextView tv = new TextView(this);
            tv.setText(py);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(picked ? BG : MUTED);
            tv.setBackgroundColor(picked ? ACCENT : CARD_HI);
            tv.setSingleLine(true);
            tv.setClickable(true);
            tv.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                // 再点一次取消锁定，回到引擎自动挑
                t9PickedPinyin = py.equals(t9PickedPinyin) ? "" : py;
                updateComposing();
                refreshCandidates();
                renderT9PinyinColumn();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.setMargins(0, dp(1), 0, dp(1));
            t9PinyinColumn.addView(tv, lp);
        }
    }

    /** 一个九键按键：上大数字，下小字母。 */
    private void addT9Key(LinearLayout row, final String digit, String letters) {
        LinearLayout key = new LinearLayout(this);
        key.setOrientation(LinearLayout.VERTICAL);
        key.setGravity(Gravity.CENTER);
        key.setBackgroundColor(KEY_BG);
        key.setFocusable(false);
        key.setClickable(true);

        TextView d = new TextView(this);
        d.setText(digit);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        d.setTextColor(TEXT);
        d.setGravity(Gravity.CENTER);
        key.addView(d, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView l = new TextView(this);
        l.setText(letters);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        l.setTextColor(MUTED);
        l.setGravity(Gravity.CENTER);
        key.addView(l, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        key.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onT9Key(digit.charAt(0));
        });
        // 长按 1 输入标点、长按其他键快速上屏
        key.setOnLongClickListener(v -> {
            if ("1".equals(digit)) {
                if (t9Digits.length() == 0) {
                    commit("，");
                    return true;
                }
            }
            onSpace();
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(key, lp);
    }

    /** 九键的底部行：符号 / 空格 / 退格。 */
    private void renderT9BottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rlp.topMargin = dp(3);
        keyArea.addView(row, rlp);

        addKey(row, "符", v -> switchPage(PAGE_SYMBOL), 1.1f, CARD_HI);
        addKey(row, "空格", v -> onSpace(), 2.4f, CARD_HI);
        addKey(row, "⌫", v -> onBackspace(), 1.3f, CARD_HI);
    }

    /** 九键点一个数字。 */
    private void onT9Key(char digit) {
        if (t9Digits.length() >= 24) return;
        t9Digits.append(digit);
        // 加了新的数字，之前锁定的拼音就对不上了，必须清掉重新猜
        t9PickedPinyin = "";
        updateComposing();
        refreshCandidates();
        renderT9PinyinColumn();
        // 云端补词：拿到一种可能的拼音串再查
        requestCloudForT9();
    }

    private void renderPinyinKeys() {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = newRow();
            String line = rows[r];

            if (r == 2) {
                addShiftKey(row);
            }
            for (int i = 0; i < line.length(); i++) {
                final char c = line.charAt(i);
                final String shown = (shiftOn || capsOn)
                        ? String.valueOf(Character.toUpperCase(c)) : String.valueOf(c);
                addKey(row, shown, v -> onLetterKey(String.valueOf(Character.toLowerCase(c))));
            }
            if (r == 2) {
                addKey(row, "⌫", v -> onBackspace(), 1.4f, CARD_HI);
            }
        }
    }

    private void renderSymbolKeys() {
        String[] rows = {"1234567890", "@#$%&*()-", "+=\"':;!?/", "[]{}<>~^\\|"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = newRow();
            String line = rows[r];
            if (r == 3) {
                addKey(row, ",", v -> commit(","), 1.0f, CARD_HI);
            }
            for (int i = 0; i < line.length(); i++) {
                final String s = String.valueOf(line.charAt(i));
                addKey(row, s, v -> commit(s));
            }
            if (r == 3) {
                addKey(row, "⌫", v -> onBackspace(), 1.4f, CARD_HI);
            }
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rlp.topMargin = dp(3);
        keyArea.addView(row, rlp);
        return row;
    }

    private void addShiftKey(LinearLayout row) {
        String label = capsOn ? "⇪" : (shiftOn ? "⬆" : "⇧");
        int bg = (shiftOn || capsOn) ? 0xFF2B3A5E : CARD_HI;
        addKey(row, label, v -> {
            if (shiftOn) {
                if (capsOn) { capsOn = false; shiftOn = false; }
                else capsOn = true;
            } else {
                shiftOn = true;
            }
            renderKeys();
        }, 1.3f, bg, (shiftOn || capsOn) ? TEAL : TEXT);
    }

    private void renderBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rlp.topMargin = dp(3);
        keyArea.addView(row, rlp);

        String modeLabel = page == PAGE_SYMBOL ? "ABC" : "?123";
        addKey(row, modeLabel, v -> {
            switchPage(page == PAGE_SYMBOL ? PAGE_PINYIN : PAGE_SYMBOL);
        }, 1.5f, CARD_HI);

        addKey(row, "中/英", v -> {
            composer.reset();
            refreshCandidates();
            switchPage(page == PAGE_PINYIN ? PAGE_SYMBOL : PAGE_PINYIN);
        }, 1.3f, CARD_HI);

        addKey(row, "，", v -> onComma(), 1.0f, CARD_HI);
        addKey(row, "空格", v -> onSpace(), 2.6f, CARD_HI);
        addKey(row, "。", v -> onPeriod(), 1.0f, CARD_HI);
        addKey(row, "回车", v -> onEnter(), 1.3f, CARD_HI);

        addKey(row, "⌫", v -> onBackspace(), 1.4f, CARD_HI);
    }

    private void addKey(LinearLayout parent, String label, View.OnClickListener l) {
        addKey(parent, label, l, 1.0f, KEY_BG);
    }

    private void addKey(LinearLayout parent, String label, View.OnClickListener l,
                        float weight, int bgColor) {
        addKey(parent, label, l, weight, bgColor, TEXT);
    }

    private void addKey(LinearLayout parent, final String label, View.OnClickListener l,
                        float weight, int bgColor, int fgColor) {
        final TextView key = new TextView(this);
        key.setText(label);
        key.setGravity(Gravity.CENTER);
        key.setTextColor(fgColor);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 2 ? 12 : 17);
        key.setBackgroundColor(bgColor);
        key.setFocusable(false);
        key.setClickable(true);
        key.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                l.onClick(v);
            }
        });
        // 长按空格切到二进制面板
        if ("空格".equals(label)) {
            key.setOnLongClickListener(v -> {
                switchPage(PAGE_BINARY);
                return true;
            });
        }
        // 长按退格连删：按住不放一直删，松手停。
        // 用 OnTouchListener 而不是 OnLongClickListener：后者只在长按触发一次，
        // 松手时机也拿不到，没法做「按住期间持续删、松手立刻停」。
        if ("⌫".equals(label)) {
            key.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            repeatDeleteStarted = false;
                            // 返回 false：点一下的点击事件照常派发，删一个的行为不变
                            v.postDelayed(deleteHoldStarter, LONG_PRESS_DELAY_MS);
                            return false;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.removeCallbacks(deleteHoldStarter);
                            stopRepeatDelete();
                            if (repeatDeleteStarted) {
                                // 连删已经启动过了，吞掉这次 up 事件，
                                // 否则松手时还会再派发一次 onClick，多删一个
                                repeatDeleteStarted = false;
                                return true;
                            }
                            return false;
                        default:
                            return false;
                    }
                }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(key, lp);
    }

    // ---------- 输入逻辑 ----------

    private void onLetterKey(String letter) {
        composer.append(letter.charAt(0));
        if (shiftOn && !capsOn) {
            shiftOn = false;
            renderKeys();
        }
        updateComposing();
        refreshCandidates();
    }

    private void onSpace() {
        if (isComposingEmpty()) {
            commit(" ");
            return;
        }
        // 上屏首选词
        String best = pickFirst();
        if (best != null && !best.isEmpty()) {
            commit(best);
        } else {
            commit(composingText());
        }
        resetComposing();
        updateComposing();
        refreshCandidates();
    }

    private void onComma() {
        if (!isComposingEmpty()) {
            onSpace();
        }
        commit("，");
    }

    private void onPeriod() {
        if (!isComposingEmpty()) {
            onSpace();
        }
        commit("。");
    }

    /** 回车：拼音未上屏时先上屏首选，再换行。 */
    private void onEnter() {
        if (!isComposingEmpty()) {
            String best = pickFirst();
            commit(best != null && !best.isEmpty() ? best : composingText());
            resetComposing();
            updateComposing();
            refreshCandidates();
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // 有的输入框不带 action，sendKeyEvent 更通用
        boolean sent = ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                && ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        if (!sent) {
            ic.commitText("\n", 1);
        }
    }

    private void onBackspace() {
        if (!isComposingEmpty()) {
            if (layoutMode == LAYOUT_T9 && page != PAGE_SYMBOL) {
                if (t9Digits.length() > 0) {
                    t9Digits.deleteCharAt(t9Digits.length() - 1);
                }
            } else {
                composer.delete();
            }
            updateComposing();
            refreshCandidates();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    // ---------- 组合态统一抽象（全键盘 / 九键共用） ----------

    /** 当前是否是九键模式（符号页除外）。 */
    private boolean isT9() {
        return layoutMode == LAYOUT_T9 && page != PAGE_SYMBOL;
    }

    /** 组合态是否为空。 */
    private boolean isComposingEmpty() {
        return isT9() ? t9Digits.length() == 0 : composer.isEmpty();
    }

    /** 组合态原始串：九键是数字串，全键盘是拼音串。 */
    private String composingText() {
        return isT9() ? t9Digits.toString() : composer.composing();
    }

    /** 清空组合态。 */
    private void resetComposing() {
        t9Digits.setLength(0);
        // 锁定状态也要清，否则下次输入会带着上一次选的拼音
        t9PickedPinyin = "";
        composer.reset();
        cloudPinyin = "";
        cloudWords = Collections.emptyList();
        if (cloud != null) cloud.cancel();
    }

    /** 把组合态同步到输入框（显示为下划线组合态）。 */
    private void updateComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.setComposingText(composingText(), 1);
    }

    /** 上屏统一入口：顺手清状态。 */
    private void commitCandidate(String text) {
        if (text == null || text.isEmpty()) return;
        commit(text);
        resetComposing();
        updateComposing();
        refreshCandidates();
    }

    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
    }

    // ---------- 候选产出 ----------

    /**
     * 当前组合态的所有候选，按优先级：
     *   1. 整句解码（九键 / 全键盘都做）—— 这是「智能整句」的核心
     *   2. 本地前缀匹配
     *   3. 云端补词（可选，联网）
     */
    private List<String> buildCandidates(int limit) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (isComposingEmpty()) return out;

        if (isT9()) {
            String digits = t9Digits.toString();
            // 用户在左侧拼音列里挑定了就只用它，别再让引擎猜。
            // 九键一对多，猜错的时候让用户自己指一下比继续猜有用得多。
            if (!t9PickedPinyin.isEmpty() && decoder != null) {
                List<SentenceDecoder.Result> rs = decoder.decode(t9PickedPinyin, 3);
                for (SentenceDecoder.Result r : rs) {
                    if (r.isFull() && !r.text.isEmpty() && seen.add(r.text)) out.add(r.text);
                }
                for (PinyinEngine.Candidate c : engine.exactKey(t9PickedPinyin, 18)) {
                    if (seen.add(c.text)) out.add(c.text);
                }
            } else {
            // 九键整句：先把数字串展开成若干拼音串，再逐个整句解码，
            // 最后把所有结果按整句分数统一排序。
            //
            // 不能「第一个成功的拼音串就收工」。音节级排序和词级分数不是一回事：
            // 94664486 按音节权重排是 zhonghuo 在 zhongguo 前面（huo 的音节权重更高），
            // 但真词库里「中国」的词权重更高、整句分更好（-12 vs -13）。
            // 只取第一个拼音串就会给出「中火」而不是「中国」。
            if (decoder != null && t9 != null) {
                List<String> pys = t9.pinyinListByScore(digits, engine, 8);
                List<SentenceDecoder.Result> all = new ArrayList<SentenceDecoder.Result>();
                for (String py : pys) {
                    for (SentenceDecoder.Result r : decoder.decode(py, 3)) {
                        // 只接受把拼音全吃掉的整句
                        if (r.isFull() && !r.text.isEmpty()) all.add(r);
                    }
                }
                Collections.sort(all, SentenceDecoder.BY_SCORE);
                for (SentenceDecoder.Result r : all) {
                    if (seen.add(r.text)) out.add(r.text);
                    if (out.size() >= limit) break;
                }
            }
            // 九键单词候选
            if (t9 != null) {
                for (PinyinEngine.Candidate c : t9.candidates(digits, engine, 18)) {
                    if (seen.add(c.text)) out.add(c.text);
                    if (out.size() >= limit) break;
                }
            }
            }
        } else {
            String py = composer.composing();
            // 整句解码
            if (decoder != null && py.length() >= 4) {
                List<SentenceDecoder.Result> rs = decoder.decode(py, 2);
                for (SentenceDecoder.Result r : rs) {
                    if (!r.isFull() || r.text.isEmpty()) continue;
                    if (seen.add(r.text)) out.add(r.text);
                }
            }
            for (PinyinEngine.Candidate c : composer.candidates(24)) {
                if (seen.add(c.text)) out.add(c.text);
                if (out.size() >= limit) break;
            }
        }

        // 云端补词
        String py = isT9() ? firstPinyinOf(t9Digits.toString()) : composer.composing();
        if (py != null && py.equals(cloudPinyin)) {
            for (String w : cloudWords) {
                if (seen.add(w)) out.add(w);
                if (out.size() >= limit) break;
            }
        }

        if (out.size() > limit) {
            out = new ArrayList<String>(out.subList(0, limit));
        }
        return out;
    }

    /** 取九键数字串的第一种拼音展开。 */
    private String firstPinyinOf(String digits) {
        if (t9 == null || digits == null || digits.isEmpty()) return "";
        // 必须用概率序，不能用 DFS 序：94664486 的 DFS 第一项是 "xiomiguo"，
        // 拿它去云端查会查出「洗哦米国」这种东西。
        List<String> l = t9.pinyinListByScore(digits, engine, 1);
        return l.isEmpty() ? "" : l.get(0);
    }

    /** 首选词：直接取候选列表第一条。 */
    private String pickFirst() {
        List<String> cs = buildCandidates(1);
        return cs.isEmpty() ? "" : cs.get(0);
    }

    /** 首选项的「上屏文本」：没有候选时退回拼音原串。 */
    private String preeditHint() {
        if (isT9()) {
            String d = t9Digits.toString();
            String py = t9 == null ? "" : t9.displayPinyin(d, engine);
            return py.isEmpty() ? d : py;
        }
        return composer.composing();
    }

    // ---------- 云端补词 ----------

    /** 触发一次云端补词（全键盘）。 */
    private void requestCloud() {
        if (cloud == null || prefs == null) return;
        if (!prefs.getBoolean("cloud_on", true)) return;
        if (isT9()) return; // 九键走 requestCloudForT9
        String py = composer.composing();
        if (py == null || py.length() < 2) return;
        if (py.equals(cloudPinyin)) return; // 已经查过
        final String req = py;
        cloudPinyin = req;
        cloudWords = Collections.emptyList();
        cloud.request(req, new CloudSuggest.Callback() {
            @Override public void onResult(String pinyin, List<String> words) {
                // 过期结果直接丢
                if (!req.equals(cloudPinyin)) return;
                cloudWords = words == null ? Collections.<String>emptyList() : words;
                refreshCandidates();
            }
        });
    }

    /** 触发一次云端补词（九键，用最可能的拼音展开）。 */
    private void requestCloudForT9() {
        if (cloud == null || prefs == null) return;
        if (!prefs.getBoolean("cloud_on", true)) return;
        String py = firstPinyinOf(t9Digits.toString());
        if (py == null || py.length() < 2) return;
        if (py.equals(cloudPinyin)) return;
        final String req = py;
        cloudPinyin = req;
        cloudWords = Collections.emptyList();
        cloud.request(req, new CloudSuggest.Callback() {
            @Override public void onResult(String pinyin, List<String> words) {
                if (!req.equals(cloudPinyin)) return;
                cloudWords = words == null ? Collections.<String>emptyList() : words;
                refreshCandidates();
            }
        });
    }

    // ---------- 候选栏 ----------

    private void refreshCandidates() {
        if (candidateBar == null) return;
        candidateBar.removeAllViews();

        List<String> cs = buildCandidates(30);
        if (cs.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(isComposingEmpty() ? "拼音输入" : preeditHint());
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setTextColor(MUTED);
            hint.setPadding(dp(10), 0, dp(10), 0);
            hint.setGravity(Gravity.CENTER_VERTICAL);
            candidateBar.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        for (int i = 0; i < cs.size(); i++) {
            final String text = cs.get(i);
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, i == 0 ? 17 : 15);
            tv.setTextColor(i == 0 ? TEAL : TEXT);
            tv.setPadding(dp(11), 0, dp(11), 0);
            tv.setGravity(Gravity.CENTER);
            tv.setClickable(true);
            tv.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                commitCandidate(text);
            });
            candidateBar.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 末尾加个"清空"方便误输
        TextView clear = new TextView(this);
        clear.setText("×");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clear.setTextColor(MUTED);
        clear.setPadding(dp(10), 0, dp(10), 0);
        clear.setGravity(Gravity.CENTER);
        clear.setClickable(true);
        clear.setOnClickListener(v -> {
            resetComposing();
            updateComposing();
            refreshCandidates();
        });
        candidateBar.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ================= 二进制面板 =================

    private void buildBinaryPanel() {
        // 方向行
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView dir = new TextView(this);
        dir.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dir.setTextColor(TEAL);
        dirRow.addView(dir, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView swap = smallBtn("⇄ 换向");
        swap.setOnClickListener(v -> { binTextToBin = !binTextToBin; refreshBinary(); });
        dirRow.addView(swap);

        TextView compact = smallBtn("紧凑");
        compact.setOnClickListener(v -> {
            binCompact = !binCompact;
            compact.setText(binCompact ? "紧凑 ✓" : "紧凑");
            compact.setTextColor(binCompact ? TEAL : MUTED);
            refreshBinary();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        dirRow.addView(compact, clp);

        binaryPanel.addView(dirRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 输入
        binInput = new EditText(this);
        binInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        binInput.setTextColor(TEXT);
        binInput.setHintTextColor(0xFF5B6579);
        binInput.setBackgroundColor(CARD_HI);
        binInput.setPadding(dp(9), dp(7), dp(9), dp(7));
        binInput.setMaxLines(2);
        binInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(5);
        binaryPanel.addView(binInput, ilp);
        binInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { refreshBinary(); }
        });

        // 输出
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(CARD_HI);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        slp.topMargin = dp(5);
        binOutput = new TextView(this);
        binOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        binOutput.setTypeface(Typeface.MONOSPACE);
        binOutput.setTextColor(TEAL);
        binOutput.setPadding(dp(9), dp(7), dp(9), dp(7));
        binOutput.setTextIsSelectable(true);
        sc.addView(binOutput);
        binaryPanel.addView(sc, slp);

        binStatus = new TextView(this);
        binStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        binStatus.setTextColor(MUTED);
        binStatus.setPadding(dp(2), dp(4), 0, 0);
        binaryPanel.addView(binStatus);

        // 按钮行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rlp.topMargin = dp(5);
        binaryPanel.addView(row, rlp);

        row.addView(actionBtn("上屏", v -> sendBinaryResult()), weight(1.2f));
        row.addView(actionBtn("读剪切板", v -> readClipToBinary()), weight(1.4f));
        row.addView(actionBtn("清空", v -> { binInput.setText(""); refreshBinary(); }), weight(1f));
        row.addView(actionBtn("隐藏", v -> requestHideSelf(0)), weight(1f));
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(MUTED);
        tv.setBackgroundColor(CARD_HI);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        return tv;
    }

    private TextView actionBtn(String label, View.OnClickListener l) {
        final TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(TEXT);
        tv.setBackgroundColor(KEY_BG);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        return tv;
    }

    private void refreshBinary() {
        if (binInput == null || binOutput == null || binStatus == null) return;
        String in = binInput.getText().toString();
        if (in.isEmpty()) {
            binOutput.setText("");
            binStatus.setText(binTextToBin ? "输入文字 → 下方出二进制" : "粘贴 0/1 → 下方出译文");
            return;
        }
        if (binTextToBin) {
            String bin = BinCore.textToBinary(in, binCompact);
            binOutput.setText(bin);
            binOutput.setTextColor(TEAL);
            binStatus.setText(BinCore.describe(in) + " · "
                    + bin.replace(" ", "").length() + " 位");
        } else {
            BinCore.DecodeResult r = BinCore.binaryToText(in);
            if (r.ok) {
                binOutput.setText(r.text);
                binOutput.setTextColor(TEXT);
                binStatus.setText(r.message);
            } else {
                binOutput.setText("无法解码：" + r.message);
                binOutput.setTextColor(0xFFF87171);
                binStatus.setText("支持空格/逗号/短横线/0b 前缀/连写");
            }
        }
    }

    private void sendBinaryResult() {
        String out = binOutput.getText().toString();
        if (out.isEmpty()) {
            toast("没有可上屏的内容");
            return;
        }
        commit(out);
        toast("已上屏");
    }

    private void readClipToBinary() {
        CharSequence clip = readClipboard();
        if (clip == null || clip.length() == 0) {
            toast("剪切板为空或无法读取");
            return;
        }
        String text = clip.toString();
        // 自动判断方向
        if (BinCore.looksLikeBinary(text)) {
            binTextToBin = false;
            binInput.setText(text);
            BinCore.DecodeResult r = BinCore.binaryToText(text);
            if (r.ok) {
                commit(r.text);
                toast("剪切板是二进制，已译成文字上屏");
            } else {
                toast("不是有效二进制：" + r.message);
            }
        } else {
            binTextToBin = true;
            binInput.setText(text);
            commit(BinCore.textToBinary(text, binCompact));
            toast("已转成二进制上屏");
        }
        refreshBinary();
    }

    private CharSequence readClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            return clip.getItemAt(0).coerceToText(this);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 工具 ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        composer.reset();
    }
}
