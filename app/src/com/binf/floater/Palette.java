package com.binf.floater;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

/**
 * 配色：深色 / 浅色两套。
 *
 * 为什么不直接用 values-night 资源：
 * 悬浮窗是 Service 里用代码建的 View，不跟 Activity 主题走；而且用户要能
 * 手动指定「浅色 / 深色」，光靠系统的 uiMode 做不到。所以两套色板放在代码里，
 * Service 和 Activity 都从这里取，两边颜色永远一致。
 *
 * 资源里那套 colors.xml 只负责 Activity 启动那一瞬间的 windowBackground，
 * 避免浅色系统上先闪一下深色。
 */
public final class Palette {

    /** 跟随系统。 */
    public static final int MODE_SYSTEM = 0;
    /** 强制浅色。 */
    public static final int MODE_LIGHT = 1;
    /** 强制深色。 */
    public static final int MODE_DARK = 2;

    public final int bg;
    public final int card;
    public final int cardHi;
    public final int stroke;
    public final int text;
    public final int muted;
    public final int accent;
    public final int teal;
    public final int ok;
    public final int warn;
    public final int err;

    private Palette(int bg, int card, int cardHi, int stroke, int text, int muted,
                    int accent, int teal, int ok, int warn, int err) {
        this.bg = bg;
        this.card = card;
        this.cardHi = cardHi;
        this.stroke = stroke;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.teal = teal;
        this.ok = ok;
        this.warn = warn;
        this.err = err;
    }

    /** 深色：原来那套，和 Windows 版视觉语言一致。 */
    public static final Palette DARK = new Palette(
            0xFF0F1117, 0xFF171B24, 0xFF1D2230, 0xFF2A3142,
            0xFFE6EBF5, 0xFF8992A7, 0xFF7AA2FF, 0xFF5EEAD4,
            0xFF4ADE80, 0xFFFBBF24, 0xFFF87171);

    /** 浅色：白底 + 深字，teal 换成同色系但压暗，保证在白底上对比度够。 */
    public static final Palette LIGHT = new Palette(
            0xFFF4F6FB, 0xFFFFFFFF, 0xFFEDF1F8, 0xFFD7DEEA,
            0xFF1B2230, 0xFF5C6679, 0xFF2F6BEA, 0xFF0E8C7C,
            0xFF15803D, 0xFFB45309, 0xFFDC2626);

    /** 用户设置的主题模式（0 跟随系统 / 1 浅色 / 2 深色）。 */
    public static int mode(Context c) {
        int m = MODE_SYSTEM;
        try {
            SharedPreferences p = c.getSharedPreferences("binf", Context.MODE_PRIVATE);
            m = p.getInt("theme_mode", MODE_SYSTEM);
        } catch (Exception ignored) {
        }
        if (m == MODE_LIGHT || m == MODE_DARK) return m;
        return systemMode(c);
    }

    /** 系统当前是深色还是浅色。 */
    public static int systemMode(Context c) {
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? MODE_DARK : MODE_LIGHT;
    }

    public static Palette get(Context c) {
        return mode(c) == MODE_DARK ? DARK : LIGHT;
    }

    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_LIGHT: return "浅色";
            case MODE_DARK:  return "深色";
            default:         return "跟随系统";
        }
    }

    /** 当前这套色板是不是深色。 */
    public boolean isDark() {
        return this == DARK;
    }

    /**
     * 按下态的背景色：深色主题往白里提一点，浅色主题往黑里压一点。
     *
     * 以前所有按钮都是 setBackgroundColor(单色)，TextView 本身没有按下反馈 ——
     * 点下去屏幕上一动不动，用户不知道点到没有，只好连点几下。
     */
    public int pressed(int normal) {
        return blend(normal, isDark() ? 0xFFFFFFFF : 0xFF000000, 0.16f);
    }

    /**
     * 带按下态的纯色背景。用 StateListDrawable 而不是 RippleDrawable：
     * 悬浮窗是 TYPE_APPLICATION_OVERLAY 窗口，ripple 要有宿主 View 的涟漪边界，
     * 在这种窗口里容易画不出来。
     */
    public Drawable pressedBg(int normal) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(pressed(normal)));
        sd.addState(new int[]{}, new ColorDrawable(normal));
        return sd;
    }

    /** 按比例把 a 往 b 混。t=0 是 a，t=1 是 b。 */
    public static int blend(int a, int b, float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = Math.round(aa + (ba - aa) * k);
        int rr = Math.round(ar + (br - ar) * k);
        int rg = Math.round(ag + (bg - ag) * k);
        int rb = Math.round(ab + (bb - ab) * k);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
