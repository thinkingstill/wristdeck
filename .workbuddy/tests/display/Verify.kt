package io.wristdeck.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 圆表版面规划器 · JVM 离线回归。
 *
 * 为什么要离线台：圆表只有一块，验一轮要编译 APK、装表、点亮、截图核对，一轮好几分钟，
 * 而且"某个尺寸下按钮压到圆外 3px"这种问题肉眼未必看得出来。
 * [DisplayGeometry] 被刻意做成**零 Android 依赖**，就是为了能在 JVM 上把边界算清楚。
 *
 * 用法：`./run.sh`
 */
private var failed = 0
private var passed = 0

private fun check(name: String, cond: Boolean, hold: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("  ✗ $name ${if (hold.isEmpty()) "" else "($hold)"}")
    }
}

private fun dp(density: Float, v: Float): Int = (v * density).roundToInt()

/** 和 MainActivity 里构造 Spec 的算法逐项对齐 —— 两边不一致的话这套测试就是自欺欺人。 */
private fun specOf(w: Int, h: Int, density: Float, statusRowH: Int) = DisplayGeometry.Spec(
    widthPx = w,
    heightPx = h,
    density = density,
    contentPadPx = dp(density, 6f),
    statusRowH = statusRowH,
    dotD = dp(density, 8f),
    statusTextGapPx = dp(density, 5f),
    bottomRowH = dp(density, 20f),
    gapStatus = dp(density, 4f),
    gapToggle = dp(density, 5f),
    gapDown = dp(density, 5f),
    gapBottom = dp(density, 5f),
    gapPill = dp(density, 5f),
    baseUpW = (0.26f * (w - 2 * dp(density, 6f))).roundToInt(),
    baseToggleW = (0.33f * (w - 2 * dp(density, 6f))).roundToInt(),
    baseSideW = (0.26f * (w - 2 * dp(density, 6f))).roundToInt(),
    needStatusW = dp(density, 8f) + dp(density, 5f) + (4.5f * 11f * density).roundToInt(),
    needBottomW = 2 * dp(density, 34f) + dp(density, 5f),
    needToggleW = dp(density, 40f),
    needUpW = dp(density, 40f),
    safePx = dp(density, 4f),
    minTouchPx = dp(density, 44f),
)

private class Rect(val name: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** 四个角到圆心的最大距离：它 ≤ R 才叫"整块都在圆里"。 */
    fun worstCorner(cx: Double, cy: Double): Double {
        var m = 0.0
        for (x in intArrayOf(left, right)) {
            for (y in intArrayOf(top, bottom)) {
                val dx = x - cx
                val dy = y - cy
                m = maxOf(m, sqrt(dx * dx + dy * dy))
            }
        }
        return m
    }
}

/** 从 Plan **独立反推**每行的矩形（不调用规划器内部函数），再去圆里核对。 */
private fun rects(p: DisplayGeometry.Plan, spec: DisplayGeometry.Spec): List<Rect> {
    val cx = spec.widthPx / 2.0
    val cy = spec.heightPx / 2.0
    val top = (cy - p.chainH / 2.0).roundToInt()
    val r = ArrayList<Rect>()

    fun centered(name: String, w: Int, t: Int, h: Int) {
        val l = (cx - w / 2.0).roundToInt()
        r += Rect(name, l, t, l + w, t + h)
    }

    /*
     * 状态行是唯一**手动居中**的一行（点贴左、文字跟着点），所以这里按布局引擎的
     * 真实语义推导：子视图左/上 = 父 padding + margin。
     *
     * 当初写这段时天真地按"以屏幕中心左右对称"推，真机上整行右偏 12px（正好一个 6dp padding）、
     * 右上角探到圆外 1.9px 才被发现。测试必须复现引擎语义，否则测得再全也抓不到这类坑。
     */
    val sTop = spec.contentPadPx + p.dotMarginTop + spec.dotD / 2 - spec.statusRowH / 2
    val sLeft = spec.contentPadPx + p.dotMarginStart
    r += Rect("状态行", sLeft, sTop, sLeft + p.statusW, sTop + spec.statusRowH)

    val uTop = top + spec.statusRowH + spec.gapStatus
    centered("上", p.upSize, uTop, p.upSize)
    val tTop = uTop + p.upSize + spec.gapToggle
    centered("播放", p.toggleSize, tTop, p.toggleSize)
    // 左右键与播放键并排
    val trioW = 2 * p.sideSize + 2 * spec.gapPill + p.toggleSize
    val lLeft = (cx - trioW / 2.0).roundToInt()
    r += Rect("左", lLeft, tTop + (p.toggleSize - p.sideSize) / 2, lLeft + p.sideSize,
        tTop + (p.toggleSize - p.sideSize) / 2 + p.sideSize)
    val rLeft = lLeft + p.sideSize + spec.gapPill + p.toggleSize + spec.gapPill
    r += Rect("右", rLeft, tTop + (p.toggleSize - p.sideSize) / 2, rLeft + p.sideSize,
        tTop + (p.toggleSize - p.sideSize) / 2 + p.sideSize)
    val dTop = tTop + p.toggleSize + spec.gapDown
    centered("下", p.downSize, dTop, p.downSize)
    val bTop = dTop + p.downSize + spec.gapBottom
    centered("底部行", p.bottomW, bTop, spec.bottomRowH)
    return r
}

private fun report(tag: String, w: Int, h: Int, density: Float, statusRowH: Int, isRound: Boolean) {
    val spec = specOf(w, h, density, statusRowH)
    val plan = DisplayGeometry.planFor(spec, isRound)
    println("─".repeat(78))
    println("$tag   ${w}x${h}px @density=$density   statusRowH=${statusRowH}px   isRound=$isRound")
    if (plan == null) {
        println("  → null（调用方一个像素都不动）")
        check("$tag 非圆屏必须返回 null", !isRound)
        return
    }
    val px2dp = { v: Int -> "%.1f".format(v / density) }
    println("  k=${"%.3f".format(plan.scale)}  链=${px2dp(plan.chainH)}dp  状态=${px2dp(plan.statusW)}dp  " +
            "上/下=${px2dp(plan.upSize)}dp  播放=${px2dp(plan.toggleSize)}dp  左右=${px2dp(plan.sideSize)}dp  " +
            "底=${px2dp(plan.bottomW)}dp  顶端=${px2dp(((h / 2.0) - plan.chainH / 2.0).roundToInt())}dp")
    if (plan.warnings.isNotEmpty()) plan.warnings.forEach { println("  ⚠ $it") }

    val cx = w / 2.0
    val cy = h / 2.0
    val r = minOf(w, h) / 2.0
    var minClear = Double.MAX_VALUE
    rects(plan, spec).forEach { rect ->
        val worst = rect.worstCorner(cx, cy)
        minClear = minOf(minClear, r - worst)
        check("$tag ${rect.name} 整块在圆内", worst <= r, "越界 ${"%.1f".format(worst - r)}px")
    }
    println("  最小圆内余量 = %.1fpx (%.1fdp)".format(minClear, minClear / density))

    val contentH = h - 2 * spec.contentPadPx
    check("$tag 链条不超可用高", plan.chainH <= contentH,
        "${plan.chainH} > $contentH")
    check("$tag 每行都是正尺寸", plan.upSize > 0 && plan.toggleSize > 0 &&
            plan.sideSize > 0 && plan.downSize > 0 && plan.bottomW > 0 && plan.statusW > 0)
    check("$tag 收窄不超过基准", plan.upSize <= spec.baseUpW + 1 &&
            plan.toggleSize <= spec.baseToggleW + 1 && plan.bottomW <= w - 2 * spec.contentPadPx)
    check("$tag 左右键与播放键横向不重叠",
        2 * plan.sideSize + 2 * spec.gapPill + plan.toggleSize <= spec.widthPx)
    // 状态行居中量必须扣掉父 padding（marginStart 是叠加在 padding 之内的）
    val wantStart = ((w - plan.statusW) / 2.0).roundToInt() - spec.contentPadPx
    check("$tag 状态行居中量已扣父 padding",
        abs(plan.dotMarginStart - wantStart) <= 1,
        "dotMarginStart=${plan.dotMarginStart} 期望≈$wantStart")
}

private fun reportInset(
    tag: String, w: Int, h: Int, density: Float, marginDp: Float, isRound: Boolean,
) {
    val margin = (marginDp * density).roundToInt()
    val inset = DisplayGeometry.pageInset(w, h, margin, isRound)
    println("─".repeat(78))
    println("$tag   ${w}x${h}px  margin=${margin}px  isRound=$isRound")
    if (inset.isZero) {
        println("  → 不内缩（ZERO），方表逐像素不变")
        check("$tag 非圆屏必须不内缩", !isRound)
        return
    }
    val sideW = w - 2 * inset.hPx
    val sideH = h - 2 * inset.vPx
    val r = minOf(w, h) / 2.0
    val corner = sqrt(sideW * sideW.toDouble() + sideH * sideH.toDouble()) / 2.0
    println("  → 水平内缩 ${inset.hPx}px 垂直内缩 ${inset.vPx}px " +
            "视口 ${sideW}x${sideH}px（%.1fdp 宽）".format(sideW / density))
    println("  → 视口四角距圆心 %.1fpx，余量 %.1fpx".format(corner, r - corner))
    check("$tag 视口必须是正方形", abs(sideW - sideH) <= 2, "$sideW vs $sideH")
    check("$tag 视口四角在圆内且留出余量", corner + margin <= r + 1,
        "角距 %.1f + 余量 $margin > R %.1f".format(corner, r))
    check("$tag 内缩不能吃掉半屏", inset.hPx < w / 2 && inset.vPx < h / 2)
}

fun main() {
    println("WristDeck 圆表版面规划器 · 离线回归")
    println("═".repeat(78))

    // ① 方表：必须原样返回 null —— 这条是"方表逐像素不变"的硬保证
    report("方表(实测)", 372, 430, 2.0f, 28, isRound = false)
    report("方表(误报圆屏)", 372, 430, 2.0f, 28, isRound = true)

    // ② 圆表实测机型：statusRowH=30px 是**真机量出来的**（11sp 状态文字，466x466@320 → 30px）
    report("圆表 OPWW234(实测)", 466, 466, 2.0f, 30, isRound = true)

    // ③ 同族其它圆表 / 别的字体比例（状态行更高会更挤，是真实风险）
    report("圆表 454", 454, 454, 2.0f, 30, isRound = true)
    report("圆表 480", 480, 480, 2.0f, 30, isRound = true)
    report("圆表 416", 416, 416, 2.0f, 30, isRound = true)
    report("圆表 466@340dpi", 466, 466, 2.125f, 32, isRound = true)
    report("圆表 466 大字体", 466, 466, 2.0f, 38, isRound = true)

    // ④ 退化输入不许崩
    check("0 尺寸返回 null", DisplayGeometry.plan(specOf(0, 0, 2f, 24)) == null)
    check("极小屏返回 null", DisplayGeometry.plan(specOf(64, 64, 2f, 24)) == null)

    // ⑤ 滚动页（设置页）内缩
    reportInset("设置页·方表", 372, 430, 2.0f, 8f, isRound = false)
    reportInset("设置页·圆表 OPWW234", 466, 466, 2.0f, 8f, isRound = true)
    reportInset("设置页·圆表 454", 454, 454, 2.0f, 8f, isRound = true)
    reportInset("设置页·圆表 416", 416, 416, 2.0f, 8f, isRound = true)
    reportInset("设置页·极小圆屏", 120, 120, 2.0f, 8f, isRound = true)

    println("═".repeat(78))
    println("通过 $passed / 失败 $failed")
    if (failed > 0) System.exit(1)
}
