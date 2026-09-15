#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
轴判定调研工具（离线）。

背景：四向判定原先用 atan2(uy,uz) / atan2(ux,uz) 两个极角"谁大听谁的"，
但这两个角共用 uz 做分母，是重力方向在 z=1 平面上的**球极投影**——增益随位置变化，
所以"我转了多少前臂"并不会等量落进 φarm。实测同一个用户的 left 在不同场次
落点比从 1.08 到 15.3 都有（见 MEMORY），本轮更出现 +73.9/+94.2 被判成 up。

本工具换成**与参数化无关**的量：重力从基准 u0 转到当前 u 的旋转轴
    a = normalize(u0 × u)
a 是设备系里的固定方向——纯旋前时 a ≈ 前臂长轴，纯屈伸时 a ≈ 手腕屈伸轴。
于是只要看 a 落在设备 x-y 平面里的方位
    ψ = atan2(a_y, a_x)
就能判"转的是哪根轴"，而且**不随基准姿态漂**。

本脚本从探针日志（含 `GEST 轨迹` 全速率重力向量）里逐段算 ψ，
并按 `结果=` 标签分组，看新特征能不能把四向干净分开。

用法：python3 axischeck.py <日志文件> [...]
"""

import math
import re
import sys

TRACE_HDR = re.compile(
    r"轨迹 (\d+)帧 span=(\d+)ms 静止前置=(\d+)ms 基准=([-\d.]+),([-\d.]+),([-\d.]+)"
)
FRAME = re.compile(r"(\d+):([-\d.]+):([-\d.]+):([-\d.]+)")
SWING = re.compile(r"摆动 (\d+)ms φarm=([-\d.]+) φbend=([-\d.]+).*结果=(\S+)")
HIT = re.compile(r"命中 action=(\w+) φarm=([-\d.]+) φbend=([-\d.]+)")


def parse(path):
    """返回 [(label, durMs, base(3), frames[(t,ux,uy,uz)], fireHit)]"""
    swings = []
    cur_label = None
    cur_dur = 0
    cur_hit = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.readlines()
    i = 0
    while i < len(lines):
        ln = lines[i]
        m = HIT.search(ln)
        if m:
            cur_hit = (m.group(1), float(m.group(2)), float(m.group(3)))
            i += 1
            continue
        m = SWING.search(ln)
        if m:
            cur_dur = int(m.group(1))
            cur_label = m.group(4)
            i += 1
            continue
        m = TRACE_HDR.search(ln)
        if m:
            base = (float(m.group(4)), float(m.group(5)), float(m.group(6)))
            frames = []
            j = i + 1
            while j < len(lines):
                if TRACE_HDR.search(lines[j]) or SWING.search(lines[j]):
                    break
                fm = FRAME.search(lines[j])
                if not fm:
                    j += 1
                    break
                for k in FRAME.finditer(lines[j]):
                    frames.append(
                        (int(k.group(1)), float(k.group(2)), float(k.group(3)), float(k.group(4)))
                    )
                j += 1
            swings.append(
                {
                    "label": cur_label if cur_dur else "?",
                    "dur": cur_dur,
                    "base": base,
                    "frames": frames,
                    "hit": cur_hit,
                }
            )
            cur_dur, cur_label, cur_hit = 0, None, None
            i = j
            continue
        i += 1
    return swings


def unit(v):
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v] if n > 1e-9 else [0.0, 0.0, 0.0]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def analyse(sw):
    u0 = unit(sw["base"])
    # 静态参考角（与识别器一致）：α0 = atan2(ux,uz) 是绕设备 Y 的极角，β0 = atan2(uy,uz) 是绕设备 X 的
    a0 = math.degrees(math.atan2(u0[0], u0[2]))
    b0 = math.degrees(math.atan2(u0[1], u0[2]))
    rows = []
    for t, ux, uy, uz in sw["frames"]:
        u = unit([ux, uy, uz])
        theta = math.degrees(math.acos(max(-1.0, min(1.0, dot(u0, u)))))
        phi_arm = math.degrees(math.atan2(ux, uz)) - a0
        phi_bend = math.degrees(math.atan2(uy, uz)) - b0
        while phi_arm > 180:
            phi_arm -= 360
        while phi_arm < -180:
            phi_arm += 360
        while phi_bend > 180:
            phi_bend -= 360
        while phi_bend < -180:
            phi_bend += 360
        ax = cross(u0, u)
        if ax[0] == 0 and ax[1] == 0 and ax[2] == 0:
            continue
        # ψ：旋转轴在设备 x-y 平面里的方位。0°=绕 x 轴转（屈伸轴），90°=绕 y 轴转（前臂轴）
        psi = math.degrees(math.atan2(ax[1], ax[0]))
        rows.append((t, theta, phi_arm, phi_bend, psi, ax))
    return rows


AXIS_MARGIN = 45.0


def predict(psi):
    """新判据：按旋转轴方位定动作。|ψ|∈[45,135] → 前臂轴（左右）；否则屈伸轴（上下）。"""
    a = abs(psi)
    if 45.0 <= a <= 135.0:
        return "left" if psi > 0 else "right"
    return "up" if a > 135.0 else "down"


def check_prediction(sw):
    """用命中那一帧的旋转轴方位，验证新判据能不能复现现场动作。"""
    if not sw["hit"]:
        return None
    label, hit_arm, hit_bend = sw["hit"]
    rows = analyse(sw)
    if not rows:
        return None
    best = min(rows, key=lambda r: abs(r[2] - hit_arm) + abs(r[3] - hit_bend))
    err = abs(best[2] - hit_arm) + abs(best[3] - hit_bend)
    return {
        "actual": label,
        "predicted": predict(best[4]),
        "psi": best[4],
        "theta": best[1],
        "arm": best[2],
        "bend": best[3],
        "err": err,
    }


def summary(sw):
    rows = analyse(sw)
    if not rows:
        return None
    # 只在"已经明显离位"的帧上统计，避免静止段的噪声轴
    strong = [r for r in rows if r[1] >= 30]
    if not strong:
        strong = rows
    peak = max(strong, key=lambda r: r[1])
    # ψ 的稳健估计：方向敏感（θ 有符号 -> 轴有符号），用圆均值
    sx = sum(math.cos(math.radians(r[4])) for r in strong)
    sy = sum(math.sin(math.radians(r[4])) for r in strong)
    psi_mean = math.degrees(math.atan2(sy, sx))
    spread = math.degrees(
        math.acos(max(-1.0, min(1.0, math.hypot(sx, sy) / len(strong))))
    )
    return {
        "label": sw["label"],
        "dur": sw["dur"],
        "peakTheta": peak[1],
        "peakPsi": peak[4],
        "psiMean": psi_mean,
        "psiSpread": spread,
        "peakPhiArm": peak[2],
        "peakPhiBend": peak[3],
        "n": len(strong),
    }


def main():
    args = [a for a in sys.argv[1:] if a != "--predict"]
    do_predict = "--predict" in sys.argv[1:]
    files = args
    if not files:
        print(__doc__)
        return 1

    if do_predict:
        ok = bad = skip = 0
        print(
            f"{'文件':<12}{'现场动作':<10}{'新判据预测':<12}{'命中帧 ψ':>10}{'θ':>7}"
            f"{'φarm':>8}{'φbend':>8}{'匹配误差':>9}  结果"
        )
        print("-" * 96)
        for path in files:
            for sw in parse(path):
                if len(sw["frames"]) < 10:
                    continue
                r = check_prediction(sw)
                if r is None:
                    continue
                if r["err"] > 6.0:
                    skip += 1
                    verdict = "跳过(命中帧未对上)"
                elif r["predicted"] == r["actual"]:
                    ok += 1
                    verdict = "OK"
                else:
                    bad += 1
                    verdict = "**不一致**"
                print(
                    f"{path.split('/')[-1]:<12}{r['actual']:<10}{r['predicted']:<12}"
                    f"{r['psi']:>10.1f}{r['theta']:>7.1f}{r['arm']:>8.1f}{r['bend']:>8.1f}"
                    f"{r['err']:>9.2f}  {verdict}"
                )
        print("-" * 96)
        print(f"复现一致 {ok} / 不一致 {bad} / 无法判定 {skip}")
        return 0

    allrows = []
    for path in files:
        for sw in parse(path):
            if len(sw["frames"]) < 10:
                continue
            s = summary(sw)
            if s:
                s["file"] = path.split("/")[-1]
                allrows.append(s)

    print(
        f"{'文件':<12}{'结果':<8}{'帧数':>5}{'θ峰':>8}{'ψ峰':>9}{'ψ均值':>9}{'ψ离散':>8}"
        f"{'φarm峰':>9}{'φbend峰':>10}{'|a|/|b|':>9}"
    )
    print("-" * 96)
    for s in allrows:
        pa, pb = abs(s["peakPhiArm"]), abs(s["peakPhiBend"])
        ratio = pa / pb if pb > 1e-6 else float("inf")
        print(
            f"{s['file']:<12}{s['label']:<8}{s['n']:>5}{s['peakTheta']:>8.1f}"
            f"{s['peakPsi']:>9.1f}{s['psiMean']:>9.1f}{s['psiSpread']:>8.1f}"
            f"{s['peakPhiArm']:>9.1f}{s['peakPhiBend']:>10.1f}{ratio:>9.2f}"
        )

    print()
    print("按动作分组的 ψ 分布（旋转轴方位；0°=绕设备X轴≈屈伸轴，±90°=绕设备Y轴≈前臂轴）：")
    groups = {}
    for s in allrows:
        groups.setdefault(s["label"], []).append(s)
    for label in sorted(groups):
        rows = groups[label]
        vals = [abs(r["psiMean"]) for r in rows]
        # 折到 [0,180)：ψ 靠近 180 与 -180 等价
        folded = [min(v, 180 - v) if v > 90 else v for v in vals]
        left = [r for r in rows if abs(r["psiMean"]) > 45]
        print(
            f"  {label:<8} n={len(rows):<3} |ψ|均值={sum(folded)/len(folded):>6.1f}°  "
            f"ψ>45°(判为前臂轴)的占比={len(left)}/{len(rows)}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
