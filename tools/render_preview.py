#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""渲染键盘界面的模拟图，用于装机前预览布局（不依赖 Android 渲染）。"""
from PIL import Image, ImageDraw, ImageFont
import os

W = 1080           # 模拟 1080p 宽屏
SCALE = W / 1080.0


def dp(v):
    return int(v * 3 * SCALE)  # 1dp ≈ 3px @1080p


BG = (0x0F, 0x11, 0x17)
CARD = (0x17, 0x1B, 0x24)
CARD_HI = (0x1D, 0x22, 0x30)
KEY_BG = (0x26, 0x2D, 0x3B)
TEXT = (0xE6, 0xEB, 0xF5)
MUTED = (0x89, 0x92, 0xA7)
TEAL = (0x5E, 0xEA, 0xD4)
ACCENT = (0x7A, 0xA2, 0xFF)
SEL = (0x2B, 0x3A, 0x5E)
STROKE = (0x2A, 0x31, 0x42)


CJK_FONT = None
SYM_FONT_PATH = None


def _cjk_path():
    global CJK_FONT
    if CJK_FONT:
        return CJK_FONT
    for base in ("C:/Windows/Fonts/", "/c/Windows/Fonts/"):
        for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf"):
            if os.path.exists(base + name):
                CJK_FONT = base + name
                return CJK_FONT
    CJK_FONT = ""
    return CJK_FONT


def _sym_path():
    global SYM_FONT_PATH
    if SYM_FONT_PATH:
        return SYM_FONT_PATH
    for base in ("C:/Windows/Fonts/", "/c/Windows/Fonts/"):
        for name in ("seguisym.ttf", "SegUISym.ttf"):
            if os.path.exists(base + name):
                SYM_FONT_PATH = base + name
                return SYM_FONT_PATH
    SYM_FONT_PATH = _cjk_path()
    return SYM_FONT_PATH


def _is_wide(ch):
    return ord(ch) > 0x2E7F


def _is_symbol(ch):
    o = ord(ch)
    return 0x2000 <= o <= 0x2E7F or o in (0x00D7,)


def font(size, bold=False):
    p = _cjk_path()
    if p:
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            pass
    return ImageFont.load_default()


def sym_font(size):
    p = _sym_path()
    try:
        return ImageFont.truetype(p, size)
    except Exception:
        return font(size)


def draw_mixed(d, xy, text, size, fill):
    """逐字符挑字体绘制：中文用雅黑，符号用 Segoe UI Symbol，ASCII 用雅黑。"""
    fcjk = font(size, bold=True)
    fsym = sym_font(size)
    x, y = xy
    for ch in text:
        f = fsym if _is_symbol(ch) else fcjk
        d.text((x, y), ch, font=f, fill=fill)
        x += d.textlength(ch, font=f)
    return x


def mixed_width(d, text, size):
    fcjk = font(size, bold=True)
    fsym = sym_font(size)
    total = 0
    for ch in text:
        f = fsym if _is_symbol(ch) else fcjk
        total += d.textlength(ch, font=f)
    return total


def draw_key(d, box, label, bg=None, fg=None, size=17):
    bg = KEY_BG if bg is None else bg
    fg = TEXT if fg is None else fg
    x0, y0, x1, y1 = box
    d.rounded_rectangle([x0, y0, x1, y1], radius=dp(4), fill=bg)
    px = int(dp(size * 1.0))
    tw = mixed_width(d, label, px)
    bb = font(px, bold=True).getbbox("字")
    th = bb[3] - bb[1]
    ty = (y0 + y1 - th) / 2 - bb[1]
    draw_mixed(d, ((x0 + x1 - tw) / 2, ty), label, px, fg)


def _autocrop(img, bg=BG):
    """裁掉底部/右侧多余空白，让预览图贴合内容。"""
    px = img.load()
    w, h = img.size
    bottom = h
    for y in range(h - 1, -1, -1):
        row_has = False
        for x in range(0, w, 3):
            if px[x, y] != bg:
                row_has = True
                break
        if row_has:
            bottom = y + 1
            break
    if bottom < h:
        img = img.crop((0, 0, w, bottom))
    return img


def digits_to_pinyins(digits):
    """预览用：把演示数字串映射成几种可能的拼音。

    真实 App 里是 T9Engine.pinyinListByScore 现算的；这里只是画图，
    硬编码几组和真机一致的输出即可（数值取自 tools/T9Probe 的实测结果）。
    """
    demo = {
        "64426": ["nihao", "nigao", "nigan"],
        "94664486": ["zhongguo", "zhonghuo", "xiongguo"],
        "94": ["yi", "zi", "xi"],
    }
    return demo.get(digits, [digits])


def render(pinyin_candidates, composing, out_path, page="pinyin", digits=""):
    H = dp(380)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    y = 0

    # 顶部标签
    d.rectangle([0, y, W, y + dp(30)], fill=CARD)
    tabs = ["拼音", "符号", "二进制"]
    tw = W / 3
    for i, t in enumerate(tabs):
        on = (page in ("pinyin", "t9") and t == "拼音") or (page == "binary" and t == "二进制") \
             or (page == "symbol" and t == "符号")
        if on:
            d.rectangle([i * tw + dp(3), y + dp(3), (i + 1) * tw - dp(3), y + dp(27)], fill=CARD_HI)
        f = font(dp(12), bold=on)
        s = d.textlength(t, font=f)
        d.text((i * tw + (tw - s) / 2, y + dp(8)), t, font=f, fill=TEXT if on else MUTED)
    y += dp(30)

    # 候选栏（真实 App 里这一行无条件存在，符号页也有，只是候选列表隐藏）
    if page != "binary":
        d.rectangle([0, y, W, y + dp(38)], fill=CARD_HI)
        x = dp(6)
        # 右侧收起键占 dp(40)，候选区到那里为止；真实 App 里是 HorizontalScrollView，
        # 多出来的候选要滑出来看，所以这里也裁掉，不能画到按钮上面。
        limit_x = W - dp(40)
        if page in ("pinyin", "t9"):
            f0 = font(dp(17), bold=True)
            f1 = font(dp(15))
            for i, c in enumerate(pinyin_candidates):
                f = f0 if i == 0 else f1
                s = d.textlength(c, font=f)
                if x + s > limit_x:
                    break
                d.text((x + dp(11), y + dp(9)), c, font=f, fill=TEAL if i == 0 else TEXT)
                x += s + dp(22)
            # 拼音组合提示：真实 App 中拼音经 setComposingText 显示在输入框里，
            # 候选栏不再重复显示；这里只在有富余空间时标一下，避免和候选重叠
            if composing:
                fx = font(dp(11))
                s = d.textlength(composing, font=fx)
                if x + s + dp(12) < limit_x:
                    d.text((limit_x - s - dp(12), y + dp(13)), composing, font=fx, fill=MUTED)
        # 收起键左边加一道细分隔线，让它看起来是独立按钮而不是候选的一部分
        d.rectangle([limit_x, y + dp(9), limit_x + 1, y + dp(29)], fill=STROKE)
        # 右侧「收起键盘」按钮
        bx0 = limit_x
        fc = font(dp(14))
        s = d.textlength("▼", font=fc)
        d.text((bx0 + (dp(40) - s) / 2, y + dp(11)), "▼", font=fc, fill=MUTED)
        y += dp(38)

    # 键盘 / 面板
    if page == "t9":
        # 九键：左侧拼音选择列 + 右侧 3×3 数字网格 + 底部 符/空格/⌫
        pad = dp(2)
        letters = ["", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"]
        key_h = dp(46)
        # 左侧拼音列（dp(56) 宽），和数字网格同高
        col_w = dp(56)
        col_x0 = pad + dp(2)
        col_x1 = col_x0 + col_w
        grid_x0 = col_x1 + dp(1)
        row_step = key_h + dp(3)
        t9_pys = digits_to_pinyins(digits) if digits else []
        for i, py in enumerate(t9_pys[:3]):
            ry0 = y + dp(3) + i * row_step
            ry1 = ry0 + key_h
            on = (i == 0)
            d.rectangle([col_x0, ry0, col_x1, ry1],
                        fill=ACCENT if on else CARD_HI)
            fp = font(dp(11))
            sp = d.textlength(py, font=fp)
            d.text(((col_x0 + col_x1 - sp) / 2, ry0 + dp(14)), py, font=fp,
                   fill=BG if on else MUTED)
        unit = (W - grid_x0 - pad) / 3.0
        for r in range(3):
            ry0 = y + dp(3) + r * row_step
            ry1 = ry0 + key_h
            for c in range(3):
                i = r * 3 + c
                x0 = grid_x0 + c * unit
                draw_key(d, (x0 + dp(2), ry0, x0 + unit - dp(2), ry1), "", KEY_BG)
                digit = str(i + 1)
                # 数字大写在上，字母小写在下（和 App 的 addT9Key 一致）
                fd = font(dp(20), bold=True)
                fl = font(dp(10))
                sd = d.textlength(digit, font=fd)
                sl = d.textlength(letters[i], font=fl)
                cx = x0 + unit / 2
                d.text((cx - sd / 2, ry0 + dp(7)), digit, font=fd, fill=TEXT)
                if letters[i]:
                    d.text((cx - sl / 2, ry0 + dp(30)), letters[i], font=fl, fill=MUTED)
        y += dp(3) + row_step * 2 + key_h
        # 底部
        ry0 = y + dp(3)
        ry1 = ry0 + dp(46)
        keys = [("符", 1.1, CARD_HI), ("空格", 2.4, CARD_HI), ("⌫", 1.3, CARD_HI)]
        total = sum(k[1] for k in keys)
        unit = (W - dp(4)) / total
        x = dp(2)
        for label, wgt, bg in keys:
            draw_key(d, (x + dp(2), ry0, x + unit * wgt - dp(2), ry1), label, bg)
            x += unit * wgt
        y = ry1

    elif page in ("pinyin", "symbol"):
        rows = (["qwertyuiop", "asdfghjkl", "zxcvbnm"] if page == "pinyin"
                else ["1234567890", "@#$%&*()-", "+=\"':;!?/"])
        pad = dp(2)
        for r, line in enumerate(rows):
            ry0 = y + dp(3)
            ry1 = ry0 + dp(42)
            n = len(line)
            lead = dp(1.3) if (r == 2 and page == "pinyin") else 0
            avail = W - 2 * pad
            unit = avail / (n + lead + (1.4 if r == 2 else 0))
            x = pad
            if r == 2 and page == "pinyin":
                draw_key(d, (x, ry0, x + unit * 1.2, ry1), "⇧", CARD_HI)
                x += unit * 1.3
            for ch in line:
                draw_key(d, (x + dp(2), ry0, x + unit - dp(2), ry1), ch)
                x += unit
            if r == 2:
                draw_key(d, (x + dp(2), ry0, x + unit * 1.4, ry1), "⌫", CARD_HI)
            y = ry1

        # 底部行
        ry0 = y + dp(3)
        ry1 = ry0 + dp(44)
        keys = [("?123" if page == "pinyin" else "ABC", 1.5, CARD_HI),
                ("中/英", 1.3, CARD_HI), ("，", 1.0, KEY_BG),
                ("空格", 2.6, KEY_BG), ("。", 1.0, KEY_BG),
                ("回车", 1.3, CARD_HI), ("⌫", 1.4, CARD_HI)]
        total = sum(k[1] for k in keys)
        unit = (W - dp(4)) / total
        x = dp(2)
        for label, wgt, bg in keys:
            draw_key(d, (x + dp(2), ry0, x + unit * wgt - dp(2), ry1), label, bg)
            x += unit * wgt
        y = ry1

    elif page == "binary":
        pad = dp(8)
        y += dp(6)
        # 演示内容：真实 UTF-8 编码，和 App 输出一致
        demo_text = "Hello 世界"
        demo_bytes = demo_text.encode("utf-8")
        # 每 5 字节一行，与 BinCore 的默认分组一致
        lines = []
        for i in range(0, len(demo_bytes), 5):
            chunk = demo_bytes[i:i + 5]
            lines.append(" ".join(format(b, "08b") for b in chunk))

        # 方向行
        f = font(dp(12))
        d.text((pad, y), "文字 → 二进制", font=f, fill=TEAL)
        bx = W - pad - dp(80)
        d.rounded_rectangle([bx, y - dp(2), bx + dp(74), y + dp(20)], radius=dp(4), fill=CARD_HI)
        px = dp(11)
        tw2 = mixed_width(d, "⇄ 换向", px)
        draw_mixed(d, (bx + (dp(74) - tw2) / 2, y), "⇄ 换向", px, MUTED)
        y += dp(26)

        # 输入框
        d.rounded_rectangle([pad, y, W - pad, y + dp(30)], radius=dp(4), fill=CARD_HI)
        f3 = font(dp(13))
        d.text((pad + dp(9), y + dp(7)), demo_text, font=f3, fill=TEXT)
        y += dp(35)

        # 输出区
        out_h = dp(76)
        d.rounded_rectangle([pad, y, W - pad, y + out_h], radius=dp(4), fill=CARD_HI)
        mono = font(dp(11))
        for i, line in enumerate(lines):
            d.text((pad + dp(9), y + dp(7) + i * dp(15)), line, font=mono, fill=TEAL)
        y += out_h + dp(5)

        f4 = font(dp(10))
        d.text((pad + dp(2), y),
               "%d 字符 · %d 字节 · %d 位" % (len(demo_text), len(demo_bytes), len(demo_bytes) * 8),
               font=f4, fill=MUTED)
        y += dp(16)

        # 按钮行
        btns = [("上屏", 1.2), ("读剪切板", 1.4), ("清空", 1.0), ("隐藏", 1.0)]
        total = sum(b[1] for b in btns)
        unit = (W - 2 * pad) / total
        x = pad
        for label, wgt in btns:
            d.rounded_rectangle([x + dp(2), y, x + unit * wgt - dp(2), y + dp(42)],
                                radius=dp(4), fill=KEY_BG)
            f5 = font(dp(13))
            s = d.textlength(label, font=f5)
            d.text((x + dp(2) + (unit * wgt - dp(4) - s) / 2, y + dp(11)),
                   label, font=f5, fill=TEXT)
            x += unit * wgt

    img = _autocrop(img)
    img.save(out_path)
    print("saved", out_path, img.size)
if __name__ == "__main__":
    os.makedirs("build", exist_ok=True)
    render(["你", "尼", "泥", "逆", "拟", "妮", "倪", "呢"], "ni", "build/preview_pinyin.png", "pinyin")
    render(["中国", "中", "种", "重", "钟", "众", "终", "忠"], "zhongg", "build/preview_candidates.png", "pinyin")
    # 九键：候选来自 T9Test 实测输出（64426 → 你好 首选）
    render(["你好", "你敢", "米高扬", "你搞", "蜜柑", "你刚才"], "64426",
           "build/preview_t9.png", "t9", "64426")
    # 九键 + 整句：94664486 → 中国
    render(["中国", "中国人", "中国共产党", "中国企业"], "94664486",
           "build/preview_t9_sentence.png", "t9", "94664486")
    render([], "", "build/preview_binary.png", "binary")
    render([], "", "build/preview_symbol.png", "symbol")
