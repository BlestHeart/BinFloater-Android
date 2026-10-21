#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把多个 Rime 拼音词典编译成紧凑的二进制，打进 APK 的 assets。

用法：
    python tools/build_dict.py [输出路径]

默认读取 build/dictsrc/ 下的：
    8105.dict.yaml    8757 条，全部单字，权重=25亿字语料字频（量级 1e7）
    base.dict.yaml    543118 条，词组，权重真实词频（量级 1e7）
    ext.dict.yaml     339186 条，词组，权重**全是 100**（无词频信息）
    others.dict.yaml  633 条，杂项，无权重列

输出格式 v2（全部大端，Java DataInputStream 默认就是大端）：
    magic       4B   "BPY2"
    version     4B   int32 = 2
    count       4B   int32          词条总数
    sylCount    4B   int32          合法音节数
    keyOff      4B*(count+1)  int32 每条 key 在 keyPool 中的起止（含 \\0）
    hzOff       4B*(count+1)  int32 每条汉字在 hzPool 中的起止（含 \\0）
    weight      4B*count      int32 归一化权重 0..1000000
    sylOff      4B*(sylCount+1) int32
    keyPool     UTF-8，\\0 结尾追加，已去空格小写
    hzPool      UTF-8，\\0 结尾追加
    sylPool     UTF-8，\\0 结尾追加

排序：先按 key 字典序（供前缀二分查找），同 key 内按权重降序。

## 权重归一化（关键）
三个来源量纲完全不同，直接合并会让 ext 的 33 万条（权重全 100）压过 base 的
真实高频词。所以按来源分别处理：

  8105   score = 1e6 * log10(1+w) / log10(1+w_max_8105)      单字，最高 1e6
  base   score = 1e6 * log10(1+w) / log10(1+w_max_base)      词组，最高 1e6
  ext    score = EXT_WEIGHT（常数 20000）                     无词频，整体压在低位
  others score = OTHERS_WEIGHT（常数 30000）                  无词频，略高于 ext

对数压缩的理由：原始词频跨度 0..7.7e7，直接用会让「的」的权重是长尾词的
上万倍，整句解码时单个高频字会压过所有词组。取 log 后跨度约 0..2，既能保持
高频优先，又不会让长尾候选饿死。

## 体积与裁剪
全量 872465 条 gzip 后 12.4MB，超过 APK 能接受的 8MB 上限。
体积主要来自三块几乎等大的数据：keyPool 10.2MB + hzPool 10.05MB +
三组 int32 偏移 10.0MB + weight 3.3MB。注意**这不是格式冗余**——
769403 个唯一 key 对应 872465 条，平均每 key 只有 1.13 个候选
（'yi' 最多也才 136 个），换成倒排索引也省不下空间。

真正的低价值数据是 ext 的 32.9 万条：它们的权重全是常数 100（没有词频），
且大量是 base 里根本没有的孤儿 key（组合噪声）。所以按 PROGRESSIVE_PRUNE
从轻到重逐级裁剪，**取第一个能压到 ≤8MB 的级别**，保证裁得刚好够用。
"""
import gzip
import math
import os
import re
import struct
import sys

MAGIC = b"BPY2"
VERSION = 2

EXT_WEIGHT = 20000
OTHERS_WEIGHT = 30000

# 音节合法性：只允许 a-z 以及 ü 的两种 Rime 写法（v / u:）
# 词条 key 会把 ü 统一成 v（lv / nv / lve / nve），与 rime-ice 的注音习惯一致。
MAX_KEY_CHARS = 24
MAX_WORD_CHARS = 12

# APK 里 assets 的目标上限（gzip 后字节数）
TARGET_GZ_BYTES = 8 * 1024 * 1024


def is_hanzi(s):
    """全部是汉字（CJK 统一表意文字，含扩展区），不含标点/字母/数字。"""
    if not s:
        return False
    for ch in s:
        o = ord(ch)
        if not (0x4E00 <= o <= 0x9FFF        # CJK 基本区
                or 0x3400 <= o <= 0x4DBF      # 扩展 A
                or 0x20000 <= o <= 0x2A6DF    # 扩展 B
                or 0xF900 <= o <= 0xFAFF      # 兼容表意
                or 0x30000 <= o <= 0x3134F):  # 扩展 G
            return False
    return True


def parse_dict(path):
    """解析 Rime dict.yaml，返回 (rows, stats)。

    rows: [(key, hanzi, raw_weight, has_weight)]
    兼容两列（无权重）、三列；跳过表头、注释、空行。
    """
    rows = []
    stats = {"lines": 0, "no_weight": 0, "bad": 0}
    in_data = False
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n").rstrip("\r")
            if not in_data:
                if line.strip() == "...":
                    in_data = True
                continue
            if not line.strip() or line.startswith("#"):
                continue
            stats["lines"] += 1
            parts = line.split("\t")
            if len(parts) < 2:
                stats["bad"] += 1
                continue
            hanzi = parts[0].strip()
            pinyin = parts[1].strip()
            weight = 0
            has_weight = len(parts) >= 3
            if has_weight:
                try:
                    weight = int(parts[2])
                except (ValueError, IndexError):
                    weight = 0
                    has_weight = False
            if not has_weight:
                stats["no_weight"] += 1
            if not hanzi or not pinyin:
                stats["bad"] += 1
                continue
            if not is_hanzi(hanzi):
                stats["bad"] += 1
                continue
            # key: 去空格，转小写，只保留 a-z（ü 在 rime-ice 里写作 v，已经是字母）
            key = re.sub(r"[^a-z]", "", pinyin.lower())
            if not key or len(key) > MAX_KEY_CHARS:
                stats["bad"] += 1
                continue
            if len(hanzi) > MAX_WORD_CHARS:
                stats["bad"] += 1
                continue
            rows.append((key, hanzi, weight, has_weight))
    return rows, stats


def log_norm(weight, w_max):
    """对数归一化到 0..1e6。"""
    if w_max <= 0 or weight <= 0:
        return 0
    v = 1000000.0 * math.log10(1.0 + weight) / math.log10(1.0 + w_max)
    return int(round(v))


def normalize_source(rows, mode, src):
    """把某来源的全部行加上归一化权重和来源标记。

    返回 [(key, hanzi, norm_weight, src, raw_weight)]
    """
    if mode == "flat":
        const = EXT_WEIGHT if src == "ext" else OTHERS_WEIGHT
        return [(k, h, const, src, w) for (k, h, w, hw) in rows]
    w_max = max((r[2] for r in rows), default=0)
    return [(k, h, log_norm(w, w_max), src, w) for (k, h, w, hw) in rows]


# ---------------- 分级裁剪 ----------------
#
# 每级都保留 8105（单字，整句解码的基石）+ base（真实词频）+ others（少量）。
# 差别只在 ext 怎么砍——它是唯一的常数权重来源，价值最低。

def prune(entries, level):
    """按 level 过滤词条列表。level 越大砍得越狠。"""
    if level <= 0:
        return entries
    base_keys = {e[0] for e in entries if e[3] == "base"}
    if level == 1:
        # 只留 ext 里 key 在 base 中已出现过的（这两个来源音同则大概率是真词）
        return [e for e in entries if e[3] != "ext" or e[0] in base_keys]
    if level == 2:
        # 再要求 ext 词长 <= 4（长组合噪声砍掉）
        return [e for e in entries
                if e[3] != "ext" or (e[0] in base_keys and len(e[1]) <= 4)]
    if level == 3:
        # ext 全砍
        return [e for e in entries if e[3] != "ext"]
    if level == 4:
        # ext 全砍 + base 里汉字 > 6 的也砍（超长词组，输入法基本用不到）
        return [e for e in entries if e[3] != "ext" and len(e[1]) <= 6]
    return [e for e in entries if e[3] not in ("ext",) and e[4] >= 2]


LEVEL_DESC = {
    0: "全量，不裁剪",
    1: "ext 只保留 key 在 base 中出现过的",
    2: "level1 + ext 词长 <= 4",
    3: "ext 全部砍掉",
    4: "ext 全砍 + base 汉字长度 > 6 的砍掉",
    5: "level4 + base 原始词频 < 2 的砍掉",
}


def dedupe(entries):
    """同一 (key, 汉字) 只留最高权重。"""
    best = {}
    for (k, h, w, s, rw) in entries:
        kk = (k, h)
        if kk not in best or w > best[kk]:
            best[kk] = w
    return [(k, h, w) for (k, h), w in best.items()]


def serialize(entries, syllables):
    """把排好序的 [(key, hanzi, weight)] 序列化成 v2 字节串。"""
    count = len(entries)
    key_pool = bytearray()
    hz_pool = bytearray()
    key_off = [0]
    hz_off = [0]
    weights = []
    for (key, hanzi, w) in entries:
        key_pool += key.encode("utf-8") + b"\0"
        key_off.append(len(key_pool))
        hz_pool += hanzi.encode("utf-8") + b"\0"
        hz_off.append(len(hz_pool))
        weights.append(w)

    syl_pool = bytearray()
    syl_off = [0]
    for s in syllables:
        syl_pool += s.encode("utf-8") + b"\0"
        syl_off.append(len(syl_pool))

    body = bytearray()
    body += struct.pack(">%di" % len(key_off), *key_off)
    body += struct.pack(">%di" % len(hz_off), *hz_off)
    body += struct.pack(">%di" % len(weights), *weights)
    body += struct.pack(">%di" % len(syl_off), *syl_off)
    body += key_pool
    body += hz_pool
    body += syl_pool

    # 头：magic(4B) + version + count + sylCount，正好 3 个 int32。
    # 千万别多加 reserved —— Java 端按这个顺序读，多一个 int 会让后面整体错位。
    header = MAGIC + struct.pack(">iii", VERSION, count, len(syllables))
    return bytes(header + body)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_dir = os.path.join(root, "build", "dictsrc")
    dst = (sys.argv[1] if len(sys.argv) > 1
           else os.path.join(root, "app", "assets", "pinyin.dict.gz"))

    # 来源 -> 归一化方式
    plan = [
        ("8105", "log"),       # 单字，真实字频
        ("base", "log"),       # 词组，真实词频
        ("others", "others"),  # 杂项，常数
        ("ext", "flat"),       # 33 万条，权重全 100，常数低位
    ]

    all_rows = []
    syl_set = set()
    for name, mode in plan:
        path = os.path.join(src_dir, name + ".dict.yaml")
        if not os.path.exists(path):
            print("  [跳过] 不存在:", path)
            continue
        rows, stats = parse_dict(path)
        norm = normalize_source(rows, mode, name)
        # 音节表只从单字来源取（单字 key 就是一个音节）
        if name == "8105":
            for (k, h, w, s, rw) in norm:
                if len(h) == 1:
                    syl_set.add(k)
        all_rows.extend(norm)
        print("  %-8s 行=%d 有效=%d 无权重=%d 丢弃=%d 方式=%s"
              % (name, stats["lines"], len(rows), stats["no_weight"],
                 stats["bad"], mode))

    print("  合并前总数:", len(all_rows))
    syllables = sorted(syl_set)
    print("  合法音节:", len(syllables))

    # 从轻到重逐级裁剪，取第一个能压到 TARGET_GZ_BYTES 以内的
    chosen = None
    for level in range(0, 6):
        kept = prune(all_rows, level)
        entries = dedupe(kept)
        entries.sort(key=lambda e: (e[0], -e[2], e[1]))
        raw = serialize(entries, syllables)
        gz_bytes = gzip.compress(raw, compresslevel=9)
        single = sum(1 for e in entries if len(e[1]) == 1)
        print("  level %d [%s] 词条=%d 单字=%d 未压缩=%.2fMB gzip=%.2fMB"
              % (level, LEVEL_DESC[level], len(entries), single,
                 len(raw) / 1048576.0, len(gz_bytes) / 1048576.0))
        if chosen is None:
            chosen = (level, entries, raw, gz_bytes, single)
        if len(gz_bytes) <= TARGET_GZ_BYTES:
            chosen = (level, entries, raw, gz_bytes, single)
            break
        # 还没到目标，继续加严；但保留最后一次的结果作为兜底
        chosen = (level, entries, raw, gz_bytes, single)

    level, entries, raw, gz_bytes, single = chosen

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as fh:
        fh.write(gz_bytes)

    print()
    print("  采用级别    : %d (%s)" % (level, LEVEL_DESC[level]))
    print("  词条数      :", len(entries), "（其中单字 %d）" % single)
    print("  音节数      :", len(syllables))
    print("  未压缩      : %.2f MB" % (len(raw) / 1048576.0))
    print("  gzip 后     : %.2f MB  (%d B)" % (len(gz_bytes) / 1048576.0, len(gz_bytes)))
    print("  写出        :", dst)
    if len(gz_bytes) > TARGET_GZ_BYTES:
        print("  [警告] 仍然超过 8MB 目标")
    return len(entries)


if __name__ == "__main__":
    main()
