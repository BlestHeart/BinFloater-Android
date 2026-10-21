#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分析 rime-ice 词库权重分布，确定裁剪阈值。"""
import os
import re
from collections import Counter

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "dictsrc")


def parse(path):
    out = []
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
            p = line.split("\t")
            if len(p) < 3:
                continue
            hz, py = p[0].strip(), p[1].strip()
            try:
                w = int(p[2])
            except ValueError:
                continue
            key = re.sub(r"[^a-z]", "", py.lower())
            if key and hz:
                out.append((key, hz, w))
    return out


def estimate(n, avg_key, avg_hz):
    """估算二进制体积（未压缩 / gzip 约 1/3）。"""
    raw = n * (12 + avg_key + 1 + avg_hz + 1)
    return raw, raw / 3.0


def main():
    merged = {}
    for name in ["8105.dict.yaml", "base.dict.yaml", "ext.dict.yaml", "others.dict.yaml"]:
        p = os.path.join(SRC, name)
        if not os.path.exists(p):
            print("缺", name)
            continue
        for k, h, w in parse(p):
            kk = (k, h)
            if kk not in merged or w > merged[kk]:
                merged[kk] = w
    print(f"合并去重 {len(merged)} 条\n")

    ws = sorted(merged.values())
    n = len(ws)
    print("权重分位数:")
    for pct in [10, 25, 50, 60, 70, 75, 80, 85, 90, 95, 99]:
        print(f"  {pct:2d}% -> {ws[int(n * pct / 100)]}")

    # 单字必须全留
    singles = {k: h for (k, h) in merged if len(h) == 1}
    print(f"\n单字 {len(singles)} 个（全部保留）")

    print("\n裁剪方案对比 (汉字数, 权重阈值) -> 条数, 估算未压缩, gzip:")
    for maxhz, minw in [(99, 0), (99, 1), (6, 1), (6, 10), (6, 100),
                        (5, 10), (5, 100), (4, 100), (4, 500),
                        (6, 500), (6, 1000), (99, 100)]:
        sel = [(k, h) for (k, h), w in merged.items()
               if (len(h) <= maxhz and w >= minw) or len(h) == 1]
        if not sel:
            continue
        avg_k = sum(len(k) for k, h in sel) / len(sel)
        avg_h = sum(len(h.encode()) for k, h in sel) / len(sel)
        raw, gz = estimate(len(sel), avg_k, avg_h)
        print(f"  汉字<={maxhz:2d}, 权重>={minw:5d} -> {len(sel):7d} 条  "
              f"raw {raw/1e6:6.2f}MB  gz {gz/1e6:5.2f}MB")

    # 看几个具体词的权重，确定"常用词"阈值
    probe = ["中国", "你好", "计算机", "人工智能", "区块链", "量子纠缠",
             "社会主义核心价值观", "我想吃饭", "今天天气", "莫名其妙"]
    print("\n常见词权重抽样:")
    for kw in probe:
        cands = [(k, w) for (k, h), w in merged.items() if h == kw]
        if cands:
            print(f"  {kw:12s} {cands[0][1]}")
        else:
            print(f"  {kw:12s} -")


if __name__ == "__main__":
    main()
