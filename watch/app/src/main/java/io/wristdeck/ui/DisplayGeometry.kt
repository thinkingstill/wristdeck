package io.wristdeck.ui

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 圆表版面规划：把主页那条纵向链条整体收进圆里。
 *
 * ## 为什么单独一份
 * 方表（372×430 @320dpi ≈ 186×215dp）的版面是**手算完钉死在 XML** 里的，圆表（466×466
 * ≈ 233×233dp）直接套用会坏，根因有两条：
 *
 * 1. `layout_constraintWidth_percent` 按**屏宽百分比**算。圆表屏更宽 ⇒ 百分比对应的绝对值更大：
 *    播放键 0.33 × 221dp = 73dp（方表是 0.33 × 174dp = 57dp）。纵向链条因此被顶爆
 *    （55 + 57.5 + 73 + 57.5 = 243dp > 可用 221dp）。
 * 2. 屏幕是圆 ⇒ 顶部/底部弧线以内才有像素，贴边的行两端会落在圆外，**物理点不到**。
 *
 * ## 调用约定（方表回归全靠它）
 * [planFor] 在 `isRound=false` 时**恒返回 null**，调用方按"null ⇒ 一个像素都不要动"处理。
 * 所以方表走的仍是 XML 原样，逐像素不变 —— 这条有离线台断言守着，不是口头保证。
 *
 * ## 几何模型
 * 屏幕视作半径 `R = min(W,H)/2`、圆心 `(W/2, H/2)` 的圆。一条水平行占纵向区间 `[t, b]`，
 * 它能用的最大宽度 = 该区间**离圆心更远那条边**处的弦宽：
 *
 * ```
 * dy          = max(|t−cy|, |b−cy|)
 * usableWidth = 2 × (√(R² − dy²) − safe)
 * ```
 *
 * 链条（自上而下，与 XML 同构）：
 * `状态行 → 上 → 播放 → 下 → 底部按钮行`，左右键与"播放"同占一条纵向带、横向并排。
 *
 * ## 两条设计取舍（都是可调参数，改这里不用碰布局）
 * - **整体纵向居中**：链条以屏幕圆心为中心，不再贴顶。因为"链越短 ⇒ 两端离圆心越近 ⇒ 弦越宽"，
 *   居中能让最窄的两行（状态行、底部行）自动挪到圆更宽的位置。只收窄而不居中，
 *   底部两个按钮会被压到 ~30dp 一个，两个字都放不下。
 * - **收窄幅度靠二分**：用一个比例 `k` 同时缩"上/播放/下"，取**所有行都还满足最小可读/可点宽度的最大 k**
 *   （即"能不缩就不缩"），最后每行再各自取 `min(自身尺寸, 该带弦宽)`。
 *   找不到满足最小宽度的 k 时退化为"几何上放得下的最大 k"，并把差了哪几行写进 [Plan.warnings]。
 *
 * 纯逻辑、零 Android 依赖，离线台见 `.workbuddy/tests/display/`。
 */
object DisplayGeometry {

    /**
     * 一次规划的输入，长度单位一律 **px**。
     *
     * 前 13 项是版面的"事实"（来自 XML 与实测），后 8 项是**产品判断**（可调）。
     */
    data class Spec(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,

        // —— 版面事实 ——
        /** 根布局四边 padding（XML = 6dp）。 */
        val contentPadPx: Int,
        /** 状态行实测高（12sp 文字 wrap_content，只能布局后量）。 */
        val statusRowH: Int,
        /** 状态点直径（XML = 8dp）。 */
        val dotD: Int,
        /** 状态点与状态文字的水平间距（XML = 5dp）。 */
        val statusTextGapPx: Int,
        /** 底部按钮行高（XML = 20dp）。 */
        val bottomRowH: Int,
        /** 状态行 → 上键（XML marginTop = 4dp）。 */
        val gapStatus: Int,
        /** 上键 → 播放键（5dp）。 */
        val gapToggle: Int,
        /** 播放键 → 下键（5dp）。 */
        val gapDown: Int,
        /** 下键 → 底部行（5dp）。 */
        val gapBottom: Int,
        /** 左右键与播放键的横向间距（5dp）。 */
        val gapPill: Int,

        // —— 收窄前的基准尺寸：就是 XML 百分比在本屏宽度下的取值（0.26 / 0.33 / 0.26）——
        val baseUpW: Int,
        val baseToggleW: Int,
        val baseSideW: Int,

        // —— 产品判断：最小可读 / 可点宽度，只参与"该缩到多小"的决策，不参与最终取值 ——
        /** 状态行最小宽：点 + 间距 + 最长常用文案（"PIN 错误"按 4.5em 估）。 */
        val needStatusW: Int,
        /** 底部行最小宽：两个按钮各 34dp + 间距。 */
        val needBottomW: Int,
        /** 播放键最小宽。 */
        val needToggleW: Int,
        /** 上/下键最小边长。 */
        val needUpW: Int,

        /** 距圆边再留的视觉安全边（弦宽里先扣掉两条）。 */
        val safePx: Int,
        /** 建议的最小可点边长，低于它只告警不失败（表上 48dp 不现实）。 */
        val minTouchPx: Int,
    )

    /** 规划结果，长度单位一律 px。 */
    data class Plan(
        /** 状态行总宽（含点与间距）。 */
        val statusW: Int,
        val upSize: Int,
        val toggleSize: Int,
        val sideSize: Int,
        val downSize: Int,
        val bottomW: Int,
        /** 状态点相对"父 padding 之后"需要再下移多少（用于把整条链居中）。 */
        val dotMarginTop: Int,
        /** 状态点左边距（把这一行居中在圆里）。 */
        val dotMarginStart: Int,
        /** 状态文字可用宽（点之后那一段）。 */
        val statusTextW: Int,
        /** 链条总高（状态行顶 → 底部行底）。 */
        val chainH: Int,
        /** 二分求出的收窄比例，1.0 = 和百分比算出来的一样大。 */
        val scale: Float,
        /** 非空表示有行没达到最小可读/可点宽度，值可直接打日志。 */
        val warnings: List<String>,
    )

    /** 滚动页要水平/垂直各内缩多少 px。 */
    data class Inset(val hPx: Int, val vPx: Int) {
        val isZero: Boolean get() = hPx == 0 && vPx == 0

        companion object {
            val ZERO = Inset(0, 0)
        }
    }

    /**
     * 圆表**滚动页**（设置页）的内缩量：把整页内容限制在圆的内接正方形里。
     *
     * 为什么主页能用"逐行收窄"、这一页不能：主页每行的 y 是固定的，可以按各自所在带算弦宽；
     * 设置页是滚动列表，**任何一行都可能滑到视口的任意 y** —— 逐行收窄会变成"滚动时宽度忽宽忽窄"，
     * 边滚边变形的观感比留白差得多。
     *
     * 滚动页只有一种稳的做法：**让视口本身内接于圆**。视口是正方形，那么视口内任何矩形
     * 都在圆内，与滚动位置无关 —— 一次性内缩，之后不用再管。
     *
     * 内接正方形边长 = √2 × (R − margin)；`margin` 是"贴着圆边"的视觉余量
     * （正方形四角正好落在圆上，不留余量会看起来像被圆框切着）。
     *
     * `inCircle=false` 或屏幕太小 ⇒ [Inset.ZERO]，调用方不要动 padding。
     */
    fun pageInset(w: Int, h: Int, marginPx: Int, inCircle: Boolean): Inset {
        if (!inCircle || w <= 0 || h <= 0) return Inset.ZERO
        val r = min(w, h) / 2.0
        val usable = r - marginPx
        if (usable <= 0.0) return Inset.ZERO
        val side = sqrt(2.0) * usable
        return Inset(
            hPx = ((w - side) / 2.0).roundToInt().coerceAtLeast(0),
            vPx = ((h - side) / 2.0).roundToInt().coerceAtLeast(0),
        )
    }

    private const val K_MIN = 0.15
    private const val K_MAX = 1.0
    private const val BISECT = 24

    /** 圆表入口。**方表必须走 null 分支**，这是"方表零改动"的硬保证。 */
    fun planFor(spec: Spec, isRound: Boolean): Plan? {
        if (!isRound) return null
        return plan(spec)
    }

    fun plan(spec: Spec): Plan? {
        val w = spec.widthPx
        val h = spec.heightPx
        if (w <= 0 || h <= 0) return null

        val r = min(w, h) / 2.0
        val cy = h / 2.0
        val contentW = w - 2 * spec.contentPadPx
        val contentH = h - 2 * spec.contentPadPx
        if (contentW <= 0 || contentH <= 0) return null

        val fixedH = (spec.statusRowH + spec.gapStatus + spec.gapToggle + spec.gapDown +
                spec.gapBottom + spec.bottomRowH).toDouble()
        val baseSum = (2 * spec.baseUpW + spec.baseToggleW).toDouble()
        // 连最小的 k 都塞不下 → 这个屏幕不适合这套版面，交给原布局，别硬凑。
        if (contentH <= fixedH + 2 * K_MIN * baseSum) return null

        fun geo(k: Double): Geo = Geo(spec, k, r, cy, contentW, fixedH)

        // 先找"满足所有最小宽度"的最大 k（几何上放得下且不憋屈）；
        // 达不到就退一步，找"几何上放得下"的最大 k，并把缺口记进 warnings。
        var k = bisect { geo(it).fitsNeed() && geo(it).chainH <= contentH }
        val warnings = mutableListOf<String>()
        if (k == null) {
            k = bisect { geo(it).chainH <= contentH }
            if (k == null) return null
            warnings += "没找到满足最小可读宽度的比例，已退化为几何最优"
        }

        val g = geo(k)
        fun warn(name: String, avail: Double, need: Int) {
            warnings += "%s 只有 %.0fdp，低于最小可读 %.0fdp".format(
                name, avail / spec.density, need / spec.density
            )
        }
        if (g.availStatus < spec.needStatusW) warn("状态行", g.availStatus, spec.needStatusW)
        if (g.availBottom < spec.needBottomW) warn("底部行", g.availBottom, spec.needBottomW)
        if (g.availToggle < spec.needToggleW) warn("播放键带", g.availToggle, spec.needToggleW)
        if (g.availUp < spec.needUpW) warn("方向键带", g.availUp, spec.needUpW)

        val upSize = g.up.roundToInt().coerceAtLeast(1)
        val toggleSize = g.toggle.roundToInt().coerceAtLeast(1)
        val sideSize = g.side.roundToInt().coerceAtLeast(1)
        val downSize = g.down.roundToInt().coerceAtLeast(1)
        if (upSize < spec.minTouchPx) {
            warnings += "方向键 %.1fdp 低于建议可点 %.0fdp".format(
                upSize / spec.density, spec.minTouchPx / spec.density
            )
        }

        val chainH = (fixedH + upSize + toggleSize + downSize).roundToInt()
        val top = cy - chainH / 2.0
        // 状态文字垂直居中在状态点上（XML 里状态文字上下都约束到点上），
        // 所以"状态行的顶" = 点的中心 − 文字半高 ⇒ 反解出点的 marginTop。
        val dotMarginTop =
            (top + spec.statusRowH / 2.0 - spec.dotD / 2.0 - spec.contentPadPx).roundToInt()
        val statusW = g.statusW.roundToInt().coerceAtLeast(1)

        return Plan(
            statusW = statusW,
            upSize = upSize,
            toggleSize = toggleSize,
            sideSize = sideSize,
            downSize = downSize,
            bottomW = g.bottomW.roundToInt().coerceAtLeast(1),
            dotMarginTop = dotMarginTop.coerceAtLeast(0),
            /*
             * ⚠️ marginStart 是**叠加在父 padding 之内**的（子视图左边界 = paddingStart + marginStart），
             * 所以居中量必须再减掉 contentPad。竖向当初减了、横向漏了，实测整行右偏 12px
             * （正好一个 6dp padding），状态行右上角因此探到圆外 1.9px。
             */
            dotMarginStart = ((w - statusW) / 2.0 - spec.contentPadPx).roundToInt().coerceAtLeast(0),
            statusTextW = (statusW - spec.dotD - spec.statusTextGapPx).coerceAtLeast(1),
            chainH = chainH,
            scale = k.toFloat(),
            warnings = warnings,
        )
    }

    /** 二分：`ok(k)` 关于 k 单调（k 越大 → 按钮越大 → 链越长 → 两端离圆心越近 → 弦越窄）。 */
    private inline fun bisect(ok: (Double) -> Boolean): Double? {
        var lo = K_MIN
        var hi = K_MAX
        if (!ok(lo)) return null
        if (ok(hi)) return hi
        repeat(BISECT) {
            val mid = (lo + hi) / 2.0
            if (ok(mid)) lo = mid else hi = mid
        }
        return lo
    }

    /**
     * 给定 k 试算一次版面（浮点，最后才取整）。
     *
     * 内部最多迭代两轮：第一轮按"比例算出来的"播放键高算链长；若左右键 + 播放键并排那条带
     * 放不下，就把这组按弦宽等比缩小，**再重算一遍链长**（播放键变矮 ⇒ 链变短 ⇒ 两端更宽）。
     * 第二轮只会让约束更松，所以两轮足够。
     */
    private class Geo(
        private val spec: Spec, k: Double, r: Double, cy: Double, contentW: Int, fixedH: Double,
    ) {
        val up: Double
        val toggle: Double
        val side: Double
        val down: Double
        val chainH: Double
        val availStatus: Double
        val availBottom: Double
        val availToggle: Double
        val availUp: Double
        val statusW: Double
        val bottomW: Double

        init {
            var toggleH = spec.baseToggleW * k
            var upH = spec.baseUpW * k
            var sideW = spec.baseSideW * k
            var chain = 0.0
            var aStatus = 0.0
            var aBottom = 0.0
            var aToggle = 0.0
            var aUp = 0.0
            var sW = 0.0
            var bW = 0.0

            repeat(2) {
                val downH = spec.baseUpW * k
                chain = fixedH + upH + toggleH + downH
                val top = cy - chain / 2.0
                val sTop = top
                val uTop = sTop + spec.statusRowH + spec.gapStatus
                val tTop = uTop + upH + spec.gapToggle
                val dTop = tTop + toggleH + spec.gapDown
                val bTop = dTop + downH + spec.gapBottom

                aStatus = chord(r, cy, sTop, sTop + spec.statusRowH, spec.safePx)
                aUp = chord(r, cy, uTop, uTop + upH, spec.safePx)
                aToggle = chord(r, cy, tTop, tTop + toggleH, spec.safePx)
                val aDown = chord(r, cy, dTop, dTop + downH, spec.safePx)
                aBottom = chord(r, cy, bTop, bTop + spec.bottomRowH, spec.safePx)

                sW = min(contentW.toDouble(), aStatus)
                bW = min(contentW.toDouble(), aBottom)

                // 左右 + 播放并排：放不下就整组等比缩，缩完把播放键也变矮（保持方形）→ 下一轮链更短
                val sideFit = min(sideW, aToggle)
                val toggleFit = min(toggleH, aToggle)
                val trio = 2 * sideFit + 2 * spec.gapPill + toggleFit
                if (trio > aToggle && trio > 0.0) {
                    val f = aToggle / trio
                    sideW *= f
                    toggleH *= f
                } else {
                    sideW = sideFit
                    toggleH = toggleFit
                }
                upH = min(upH, aUp)
            }

            up = upH
            toggle = toggleH
            side = sideW
            down = upH
            chainH = chain
            availStatus = aStatus
            availBottom = aBottom
            availToggle = aToggle
            availUp = aUp
            statusW = sW
            bottomW = bW
        }

        /** 每行都还够最小可读 / 可点宽度。 */
        fun fitsNeed(): Boolean =
            availStatus >= spec.needStatusW && availBottom >= spec.needBottomW &&
                    availToggle >= spec.needToggleW && availUp >= spec.needUpW
    }

    /** 纵向带 [t, b] 的可用宽度：取离圆心更远的那条边算弦，并扣掉两侧安全边。 */
    private fun chord(r: Double, cy: Double, t: Double, b: Double, safePx: Int): Double {
        val dy = maxOf(abs(t - cy), abs(b - cy))
        val sq = r * r - dy * dy
        if (sq <= 0.0) return 0.0
        return (2.0 * (sqrt(sq) - safePx)).coerceAtLeast(0.0)
    }
}
