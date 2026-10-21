#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计 rime-ice 各词典条目量，决定打包策略。"""
import os
import re
import sys
from collections import Counter

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "dictsrc")


def parse(path):
    entries = []
    in_data = False
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not in_data:
                if line.strip() == "...":
                    in_data = True
                continue
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            hanzi = parts[0].strip()
            pinyin = parts[1].strip()
            weight = 0
            if len(parts) >= 3:
                try:
                    weight = int(parts[2])
                except ValueError:
                    weight = 0
            if not hanzi or not pinyin:
                continue
            key = re.sub(r"[^a-z]", "", pinyin.lower())
            if not key:
                continue
            entries.append((key, hanzi, weight))
    return entries


def main():
    total = 0
    allkeys = {}
    per_syl = Counter()
    for name in ["8105.dict.yaml", "base.dict.yaml", "ext.dict.yaml",
                 "others.dict.yaml", "tencent.dict.yaml"]:
        p = os.path.join(SRC, name)
        if not os.path.exists(p):
            print(f"缺 {name}")
            continue
        es = parse(p)
        total += len(es)
        for k, h, w in es:
            kk = (k, h)
            if kk not in allkeys or w > allkeys[kk]:
                allkeys[kk] = w
        print(f"{name:22s} {len(es):8d} 条")
        for k, h, w in es:
            per_syl[min(len(h), 8)] += 1

    print(f"\n原始合计 {total} 条，去重后 {len(allkeys)} 条")

    # 按汉字长度分布
    dist = Counter()
    for (k, h) in allkeys:
        dist[min(len(h), 9)] += 1
    print("\n汉字长度分布:")
    for n in sorted(dist):
        print(f"  {n}{'+' if n == 9 else ''} 字: {dist[n]}")

    # 按音节数分布
    syl = Counter()
    for (k, h) in allkeys:
        syl[min(len(k) // 2, 9)] += 1
    print("\n拼音长度分布(字母数):")
    for n in sorted(syl):
        print(f"  {n*2}{'+' if n == 9 else ''} 字母: {syl[n]}")

    # 不同阈值下的规模
    print("\n不同裁剪方案:")
    for maxlen, minw in [(len(allkeys), 0), (6, 0), (6, 1), (6, 100),
                         (8, 0), (8, 100), (8, 1000)]:
        n = sum(1 for (k, h), w in allkeys.items()
                if len(h) <= maxlen and w >= minw)
        print(f"  汉字<= {maxlen}, 权重>= {minw:5d} -> {n} 条")


if __name__ == "__main__":
    main()
