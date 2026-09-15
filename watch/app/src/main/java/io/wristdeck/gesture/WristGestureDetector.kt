package io.wristdeck.gesture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 手腕动作识别器：**纯逻辑，不依赖任何 Android 类**，可在 JVM 上直接跑单元测试。
 *
 * 手表上真机调参太慢（每次都要重新装、抬手腕、看日志），所以把算法和传感器采集彻底分开：
 * 采集层负责喂 accel + 时间戳，这里只输出"该发什么动作"。
 *
 * ## 输出动作（取值必须与 net/Protocol.kt 对齐，但这里刻意不 import，保持零依赖）
 * - `up` / `down`：手腕屈 / 伸（"上下摆腕"）
 * - `left` / `right`：前臂内旋 / 外旋（"转手腕"）
 * - `toggle`：把表盘整个翻过去（play/pause），由调用方按已知播放状态解析成 play 或 pause
 *
 * ## 只靠重力，不用陀螺（这是实测逼出来的，不是设计偏好）
 * OWW212 的陀螺读数**不可用**：静止在底座上时 `|ω|` 恒为 60（其自身单位），
 * 而重力方向连续 74.9 秒没有任何变化——静止的表不可能转了 12 圈，所以那是零偏；
 * 做动作时又出现量级不可能的读数（最大到 29051，远超任何陀螺量程），零偏估计在 44~187 之间乱跑。
 * 单位也存疑（该 HAL 疑似用 deg/s 而非 Android 规范的 rad/s）。
 * 结论：陀螺一路从**判定路径**里彻底移除，只在日志里留原始读数供诊断。
 *
 * 代价是明说的一条：**翻表盘必须把前臂抬起来做**。旋前/旋后是绕前臂长轴的转动，
 * 前臂垂在身侧时这条轴 ≈ 重力方向，绕它的转动不改变重力在设备系里的表示 → 重力看不见。
 * 前臂抬起（水平或斜上）时重力垂直于该轴，就能看见。这也正是"抬手翻表盘"的自然姿势。
 *
 * ## 角度怎么算
 * 基准 `g0` 是"回中"时的重力方向。`atan2(ux,uz)` 是绕设备 Y 轴（≈前臂长轴）的极角，
 * `atan2(uy,uz)` 是绕设备 X 轴的极角，两者与基准的差就是腕部姿态偏移 `φarm` / `φbend`。
 * 重力是绝对参考，**不做积分所以不存在漂移**——这是"戴一整天不会自己跑偏"的前提。
 *
 * ## ⚠️ 判"是哪根轴"绝不能比 `|φarm|` 和 `|φbend|` 谁大（2026-09-15 真机血案）
 * 这两个极角**共用 `uz` 做分母**，是重力方向在 `z=1` 平面上的球极投影：增益随位置变化、
 * 还带 ±90° 的分支切点。对**严格绕前臂轴的纯旋前**有闭式解 `φarm ≡ θ`，
 * 而 `φbend` 是**纯耦合项**，随 θ 非线性增长并**在 θ≈72° 处反超 φarm**：
 *
 * | θ（真身是 left） | φarm | φbend | 比大小判据给出的 |
 * |---|---|---|---|
 * | 45°~70° | 45~70 | +6.6 ~ +59.8 | left ✓ |
 * | **73.92°** | **73.92** | **+94.26** | **up ✗** |
 * | 80°~90° | 80~90 | +132.8 ~ +153.4 | up ✗ |
 *
 * 现场实际发生的就是这件事：用户做"向左"（纯旋前）到 74° 左右，被发成了 `up`
 * （wrist8.log 里 6 次 `φarm=+50~78 / φbend=+75~106` 的 `up` 全是 left 尝试）。
 * 佩戴姿态只要让 `u0y` 稍大（反解出的一组解只需表相对前臂轴**斜置 6°**）就必然踩中。
 * **所以判轴改用与参数化无关的量**（见下）。
 *
 * ## 方向怎么判：切空间分解（线性、与基准姿态无关）
 * 令 `t = u − (u·u0)·u0`（重力在球面上的"运动方向"，垂直于 u0）。两条参考切向是
 * 绕设备 Y 轴转的 `tArm = ŷ×u0`、绕设备 X 轴转的 `tBend = x̂×u0`，于是
 * ```
 * coefArm  = t·tArm  / |tArm|²      coefBend = t·tBend / |tBend|²
 * ```
 * 对纯单轴转动，两个系数分别**严格等于 `sin(该轴转角)`**（线性的，不会像 φ 那样爆）。
 * 判轴就回到"谁大听谁的"，但这次量的是正确的量：
 * - `|coefArm| ≥ |coefBend|` → 前臂轴 → `coefArm > 0` 发 left、否则 right
 * - 否则 → 屈伸轴 → **`coefBend > 0` 发 down**、否则 up（⚠️ 屈伸轴的符号与 φbend **相反**，
 *   理由见 [resolveDirection] 里的注释，别照抄前臂轴那半边）
 * 纯旋前时不论转到多少度、基准怎么斜，`coefBend` 都只是个零头（实测 0.96 vs −0.11）。
 *
 * ## 幅度分层（三根轴共用一个自由度，必须靠幅度分开）
 * 幅度用 **θ = acos(u0·u)**——重力从基准转过多少度，**无分支、无耦合**，就是"你转了多少"。
 * 翻表盘（≈180°）和左右转腕（几十度）**是同一条前臂轴**，所以用幅度分层：
 * - `[triggerDeg, directionMaxDeg]` = 45°~130° → 发方向
 * - `>= faceDownDeg` → 进入 faceDown，发一次 `toggle`
 * 迟滞用 140°/70° 施密特，翻回来是**静默复位**（不再发动作）。
 *
 * ⚠️ **翻转那条分支仍然用 `|φarm| ≥ faceDownDeg`，别改成 θ**：真实翻转并不是纯前臂转动，
 * 实测触发瞬间 `|φarm|` = 142~178 而 `θ` 只有 88~131（φarm 读到的 ~180 是分支跳变），
 * 换成 θ 就和方向窗口重叠、分不开了。**方向窗口用 θ、翻转用 φarm，两者各用所长。**
 * 阈值都是实测顶出来的：用户"掰到底"的深压腕 θ 到过 118°，方向窗口取 130 有余量。
 *
 * ## 误触抑制
 * - 触发要求**峰值角速度**够大：离位过程中至少有一瞬超过 [Config.crossRateDegPerSec]，
 *   且随后停稳 [Config.settleMs]。慢慢抬手停在某个角度**不算**（这条专门防"抬手看表"）。
 *   ⚠️ 判据换过一次，见 [excursionPeakRate] 的注释——**不要改回"穿越耗时"**。
 * - 两次动作之间只靠固定 [Config.refractoryMs] 静默期隔开。**这里刻意不带任何"回中"／
 *   位置条件**——曾经因为要求"回中才算过冷却"导致连续做动作时永久卡死在 COOLDOWN（实测 10 次只中 2 次）。
 *   回弹过冲的问题改由"峰值速率触发 + 停稳判定"在上游解决。
 * - 候选有 [Config.candidateTimeoutMs] 超时，干掉落一半放弃的动作。
 * - 翻转途中的方向误发由 `|φarm| ≥ faceDownDeg` 这条**前置**分支挡掉（它在 pickDirection 之前）。
 * - 识别只在"活跃窗口"内进行（[arm]/[disarm]）。
 */
class WristGestureDetector(private var cfg: Config = Config()) {

    /*
     * 占位说明：以下常量是与 net/Protocol.kt 的**口头契约**。
     * 之所以复制字面量而不是 import，是为了让本文件保持零依赖、能在 JVM 上单测。
     */
    companion object {
        const val ACTION_UP = "up"
        const val ACTION_DOWN = "down"
        const val ACTION_LEFT = "left"
        const val ACTION_RIGHT = "right"
        const val ACTION_TOGGLE = "toggle"

        /** 屏幕亮时每次调用刷新窗口，窗口长度取 5s：够"点亮屏幕 → 抬腕 → 翻表盘"一气呵成。 */
        const val ARM_HOLD_MS = 5_000L

        private const val PI_F = 3.14159265f

        /** 两次基准重建之间的最小间隔，挡住静止时逐帧重建的抖动。 */
        private const val MIN_REBASE_GAP_MS = 500L

        /** 二级重建基准（表在腕上转位救援）之后的静默期：让"回中"那段动作被吃掉。 */
        private const val DRIFT_QUIET_MS = 1_500L

        /**
         * 切空间参考轴退化的下限（`|tArm|² = u0x²+u0z²`、`|tBend|² = u0y²+u0z²`）。
         *
         * 低于它就说明该轴与重力几乎平行——例如前臂竖直时绕前臂轴的转动根本不改变
         * 重力在设备系里的表示（类注释里那条"翻表盘必须把前臂抬起来做"）。
         * 此时对应系数一律记 0，否则 1/极小值会把噪声放大成假的大系数，反而抢到主轴。
         */
        private const val MIN_TANGENT_SQ = 0.05f
    }

    data class Config(
        /** 四向触发角：重力相对基准转过这么多度才算一个方向。 */
        val triggerDeg: Float = 45f,
        /**
         * 四向的**幅度**上限，量的是 **θ = acos(u0·u)**（真实转角），不是 φ。
         *
         * ⚠️ 换成 θ 是被真机逼的：φ 是共用 uz 的球极投影，纯旋前到 85° 时 `|φbend|`
         * 会涨到 132~153（见类注释那张表），拿 φ 当上限会把**深一点的合法方向动作整个拒掉**。
         * θ 无分支无耦合，直接就是"转了多少度"。
         *
         * 实测方向动作的 θ 落在 51°~118°（最深的一条是深压腕 −118°），取 130 有余量。
         * 上限本身是必须的：翻表盘时手腕必然先经过 45°，没有上限会顺带误发方向。
         */
        val directionMaxDeg: Float = 130f,
        /** 离位起点：低于它视为"还在中位"。摆动指纹的分段、速率累计的清零都认它。 */
        val crossoverStartDeg: Float = 25f,
        /**
         * 触发所需的**峰值角速度**（°/s）。低于它的离位一律视为"慢慢抬手"而不发动作。
         *
         * ⚠️ 这里换过一次判据，替换掉的是原先的 `crossoverMs`（"25°→45° 必须 400ms 内完成"）。
         * 那个判据量的是**整段穿越耗时**，而真人动作是"快速起动 → 减速到位"：
         * 实测一次干净利落的转腕 `202ms 走 16.7°（82°/s）→ 再 203ms 只走 3.7°（18°/s）→ 停在 45°`，
         * 25→45 合计约 800ms，被 400ms 窗口判成"慢慢抬手"整段拒掉（段4 漏发就是这么来的）。
         * 换成峰值速率后减速段不再拖累判据。
         *
         * 取值 60：实测"有意转腕"峰值 82°/s，而"慢慢抬手"约 30°/s，60 在两者中间偏
         * 快的一侧（宁可漏一个慢动作，也不要抬手看表就发方向键）。
         * **调它之前先看日志里打出的实测峰值**——[Swing.peakRate]/[Snapshot.rateDegPerSec]
         * 会把命中与未命中的峰值都记下来，别凭直觉改。
         */
        val crossRateDegPerSec: Float = 60f,
        /**
         * 速率差分的采样窗口。逐帧差分在 50Hz 下会被重力低通的残余噪声放大，
         * 所以每攒够这么久才取一次 `Δpeak/Δt`，整个离位过程取最大值。
         * 70ms @82°/s ≈ 5.7°，远高于噪声量级；再短就吃噪声，再长就把"快起动"抹平。
         */
        val rateWindowMs: Long = 70L,
        /** 到达阈值后要停稳这么久才敢发，防"甩过头又弹回来"。 */
        val settleMs: Long = 120L,
        /**
         * "停稳"的容差：角度必须在这一带之内真的不动 [settleMs] 才算停稳。
         *
         * 这一条**必须**有。第一版把 settle 写成"从越过阈值起算延时 120ms"，
         * 结果翻表盘时手腕匀速穿过 45°~100° 区间（约 120ms），延时照样满足 →
         * 翻转会顺带误发一个方向键。改成"角度真的停住"之后，匀速穿过的动作永远攒不满，
         * 只有到位后停住的手势才会触发。
         */
        val settleTolDeg: Float = 8f,
        /** 候选超时：进来这么久还没满足条件就放弃。 */
        val candidateTimeoutMs: Long = 1_200L,
        /** 回中阈值：低于它才算回中。 */
        val restDeg: Float = 15f,
        /**
         * 触发后的**不应期**：这段时间内不再产生任何动作，用来吞掉"甩出去之后手腕回弹"。
         *
         * 这里刻意**不要求"回中停住"**。第一版要求"回中 + 停住 250ms"才解禁，实测用户做动作是
         * **连续**的（两下之间手腕根本不停在中位），于是一整轮 8 个方向动作全被吞掉。
         * 回弹通常在 300~400ms 内结束，所以固定的 800ms 不应期既能挡住回弹，
         * 又不会挡住人类真实"一下接一下"的节奏（实测动作间隔 2~10s）。
         */
        val refractoryMs: Long = 800L,
        /** 回中并静止这么久 → 顺手刷新基准 g0。 */
        val restHoldMs: Long = 800L,
        /**
         * 重力方向变化小于它就算"没动"。
         * **静止判定只认重力，不认陀螺**——陀螺零偏偏大且不稳，拿它判静止永远判不出来。
         */
        val stillTolDeg: Float = 3f,
        /**
         * 兜底重建基准：姿态长时间不动这么久，即使角度不在中位附近也重建。
         * 用来救"表在腕上慢慢转位"导致 φ 被永久偏置、四向全部失准的情形。
         * 必须足够长：太短会在连续做动作的间隙里**误触发**，把基准搬到一个动作中的姿态上，
         * 之后所有角度全乱（第一版取 3s 就踩了这个坑）。只在 IDLE 里生效。
         */
        val driftHoldMs: Long = 8_000L,
        /* 原先这里有个 `crossAxisDeg = 80f`（"副轴超过它就算斜着乱甩"）。
         * 已删除，**别再请它回来**：它卡的是 φ，而 φ 的耦合量本身就随转角非线性增长
         * （纯旋前到 85° 时副轴 φ 就有 132°），对合法动作太紧；
         * 而"是哪根轴"现在由切空间系数 coefArm/coefBend 直接给出，不再需要靠副轴大小兜底。 */
        /** 翻转判定：重力口径，转过这么多度视为表盘朝下。实测触发时 |φarm| ≥143，取 140 有余量。 */
        val faceDownDeg: Float = 140f,
        /** 翻转判定的施密特回退阈值：回到这个角度以下才算翻回来（迟滞防抖）。 */
        val faceUpHystDeg: Float = 70f,
        /** 重力低通时间常数（秒）。太大则穿越判定被拖慢，太小则手的线性加速度会污染角度。
         *  0.06s 是权衡：0.1s 会让"转到 45°"晚约 150ms 才认出来，手势显得迟钝。 */
        val gravityTauSec: Float = 0.06f,
        /** 投影退化保护的阈值：|uz| 与 |ux| 同时小于它时 atan2 不可信。 */
        val degenerateThreshold: Float = 0.25f,
        /** 戴哪只手。**只是默认符号的兜底**，真实映射以实测校准为准。 */
        val armLeft: Boolean = true,
        /** 实测校准：显式指定前臂轴正/负角对应的动作，null 表示按 [armLeft] 推。 */
        val armPosAction: String? = null,
        val armNegAction: String? = null,
        /** 屈伸轴正/负角对应的动作。 */
        val bendPosAction: String = ACTION_UP,
        val bendNegAction: String = ACTION_DOWN,
    )

    enum class State {
        /** 还没建立基准（刚启动，或长时间没有稳定的中位姿态）。 */
        NEED_BASELINE,

        /** 中位待命，可触发。 */
        IDLE,

        /** 已越过阈值，正在等停稳确认。 */
        CANDIDATE,

        /** 刚发过动作，必须回中停住才解禁。 */
        COOLDOWN,

        /** 表盘朝下（翻转到位），四向识别停用。 */
        FACE_DOWN,
    }

    data class Event(
        val action: String,
        val state: State,
        /** 触发时前臂轴角度（度），用于事后核对是哪根轴、多大。 */
        val phiArm: Float,
        val phiBend: Float,
        val ts: Long,
        /**
         * 触发时的**判定依据**：真实转角 θ 与两个切空间系数。
         * 发不发方向由 `coefArm` / `coefBend` 谁大决定，φ 只是留着手工交叉核对。
         */
        val thetaDeg: Float = 0f,
        val coefArm: Float = 0f,
        val coefBend: Float = 0f,
    )

    /**
     * 一次"摆动"的指纹：从角度越过阈值到掉回中位以下，**无论有没有触发动作都会报一次**。
     *
     * 存在的理由：标定必须回答"哪个动作动的是哪根轴、多大、符号如何"。
     * 只看"命中"日志不够——没命中的动作恰恰是标定最需要的信息。
     */
    data class Swing(
        val durMs: Long,
        /** 有符号：取 |φarm| 最大的那一帧的值（不是最大值本身，符号要保留）。 */
        val armPeak: Float,
        val bendPeak: Float,
        /** 这次摆动最终发出的动作；null = 没触发。 */
        val fired: String?,
        /**
         * 本次摆动的峰值角速度（°/s）。**"发不发"完全由它决定**，所以必须和结果一起报出来——
         * 标定时最需要的信息恰恰是"没发的那次速率是多少"，不然只能靠猜着调阈值。
         */
        val peakRate: Float,
        /** 本次摆动达到的最大真实转角 θ（度）。幅度窗口（45°~130°）就是拿它卡的。 */
        val thetaPeak: Float = 0f,
        /**
         * 只按"轴 + 幅度窗口"算，这次摆动**本该**判成什么（null = 不构成方向动作）。
         * 与 [fired] 分开记：没触发的原因可能是速率不够/没停稳，而 [axisAction] 告诉你轴判对了没有。
         */
        val axisAction: String? = null,
    )

    data class Snapshot(
        val state: State,
        val phiArm: Float,
        val phiBend: Float,
        /**
         * 真实转角 θ（度）与切空间系数。**判轴看 coefArm/coefBend，不看上面那两个 φ**——
         * 调试页实时打出来，才能在真机上当场看出"轴判得对不对"。
         */
        val thetaDeg: Float = 0f,
        val coefArm: Float = 0f,
        val coefBend: Float = 0f,
        /** 距上一次真正运动过了多久（由重力判定）。静止判定能不能工作，全看这个数会不会长大。 */
        val stillMs: Long,
        /** 不应期剩余毫秒数；0 表示可触发。 */
        val coolRemainMs: Long,
        /**
         * 本次离位至今的峰值角速度（°/s）。触发判据就是它 ≥ [Config.crossRateDegPerSec]，
         * 实时打出来才能在真机上当场看出"阈值是高了还是低了"。
         */
        val rateDegPerSec: Float,
        /** 陀螺原始读数的模（**未换算、单位存疑、不参与判定**，只用于诊断）。 */
        val gyroRaw: Float,
        val armed: Boolean,
        val armRemainMs: Long,
        val lastEvent: Event?,
        val eventCount: Int,
        val baselineAt: Long,
    )

    /**
     * 全速率采样回调（每次 [onAccel] 结束时触发一次，设备上约 50Hz）。
     *
     * 存在的理由：`onSwing` 只给**每条摆动的峰值**，而"发不发"取决于触发那一瞬的角度。
     * 光看峰值调阈值会被误导——曾经就因为只有峰值数据，把一次深压腕的漏发归错了因。
     * **传的是低通后的重力单位向量，不是算出来的 φ。** 这一点是被逼出来的：
     * 只记 φarm/φbend 的话，离线回放必须先把它反推回重力，而两条极角共用同一个 uz，
     * |主轴| 越过 ±90° 时反推会落到另一个分支，回放出的角度与现场不符
     * （实测喂 +88° 会被读成 -92°）。记原始向量就没有这个损失，回放与现场逐帧等价。
     * 纯诊断用，正式功能不接。
     */
    var onSample: ((ts: Long, ux: Float, uy: Float, uz: Float) -> Unit)? = null

    /* ---------------- 输入状态 ---------------- */

    private var lastAccelTs = 0L

    /** 低通后的重力估计（设备坐标系）。 */
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gravityReady = false

    /** 基准重力方向。 */
    private var u0x = 0f
    private var u0y = 0f
    private var u0z = 1f
    private var baselineAt = 0L

    /** 当前角度（相对基准），单位度。 */
    private var phiArm = 0f
    private var phiBend = 0f

    /**
     * 真实转角 `θ = acos(u0·u)`，单位度。
     *
     * 方向判定的**幅度**认它，不认 φ：φ 是共用 uz 的球极投影，纯旋前到 85° 时副轴 φ 会涨到
     * 132~153（见类注释），拿它当上限会把深一点的合法动作整个拒掉。θ 无分支、无耦合。
     */
    private var thetaDeg = 0f

    /**
     * 切空间系数：重力运动方向 `t = u − (u·u0)u0` 在两条参考切向上的投影
     * `coefArm = t·(ŷ×u0)/|ŷ×u0|²`、`coefBend = t·(x̂×u0)/|x̂×u0|²`。
     *
     * 对**纯单轴转动**它们分别严格等于 `sin(该轴转角)`——线性的，不会像 φ 那样在
     * 分支附近爆掉。判"是哪根轴"就比这两个数，**别再去比 |φarm| 和 |φbend|**。
     */
    private var coefArm = 0f
    private var coefBend = 0f

    /** 投影退化时保留上一帧角度，避免 atan2(0,0) 跳变。 */
    private var armDegenerate = false

    /* ---------------- 状态机 ---------------- */

    private var state = State.NEED_BASELINE
    private var stateAt = 0L

    /**
     * 本次"离位"（peak ≥ [Config.restDeg]）过程中的**峰值角速度**（°/s）。
     *
     * 用来区分"有意摆腕"和"慢慢抬手看表"。这里换过一次判据，原因值得记牢：
     * 原先用"25°→45° 必须在 400ms 内完成"，但那量的是**整段**穿越耗时，
     * 而真人动作是"快速起动 → 减速到位"，减速段被算进去判据就废了。
     * 实测用户一次干净利落的转腕：
     *   `202ms 走 16.7°（82°/s）→ 203ms 走 3.7°（18°/s）→ 停在 45° 附近`
     * 25→45 合计约 800ms，被 400ms 的窗口判成"慢慢抬手"而整段拒掉（段4 漏发）。
     * 峰值角速度不受减速段拖累，能干净分开（实测 82°/s vs 慢慢抬手的 ~30°/s）。
     *
     * 累计与清零都在 [trackExcursionRate] 里。
     */
    private var excursionPeakRate = 0f

    /** 是否处在一次"离位"中（peak 起过 [Config.restDeg] 之后、还没掉回中位）。 */
    private var excursionActive = false

    /** 速率差分的上一次采样基准（时间 + 当时的 peak）。见 [Config.rateWindowMs]。 */
    private var rateAt = 0L
    private var rateRefPeak = 0f

    /** "停稳"检测：角度与 [settleRef] 相差超过容差就认为还在动，计时重来。 */
    private var settleRef = 0f
    private var settleSince = 0L

    /** 静止判定用的重力参考向量：与当前重力夹角超过容差就重置 → [stillSince] 即"上次真正运动的时间"。 */
    private var sx = 0f
    private var sy = 0f
    private var sz = 1f
    private var stillSince = 0L

    /** 不应期结束时刻：到点即回到 IDLE（不看当前角度，否则连续动作会再次死锁）。 */
    private var cooldownUntil = 0L

    /* 摆动指纹的累计器（见 [Swing]）。 */
    private var recording = false
    private var recStart = 0L
    private var recArm = 0f
    private var recBend = 0f
    private var recArmMax = 0f
    private var recBendMax = 0f
    private var recFired: String? = null

    /** 摆动期间 θ 最大那一帧的 θ 与两个切空间系数，用来算出"这次本该判成什么"。 */
    private var recTheta = 0f
    private var recCoefArm = 0f
    private var recCoefBend = 0f

    /** 摆动期间抄下来的一份峰值速率（速率累计器会被 [enterCooldown] 清掉，见 [Swing.peakRate]）。 */
    private var recPeakRate = 0f

    /** 陀螺原始读数模，仅诊断。 */
    private var gyroRaw = 0f

    /** 翻转已发过就不会重复发，直到翻回来。 */
    private var flipFired = false

    /** 二级重建基准之后的静默期：这段时间内一个动作都不发。 */
    private var quietUntil = 0L

    /* ---------------- 活跃窗口 ---------------- */

    private var armedUntil = 0L
    private var lastEvent: Event? = null
    private var eventCount = 0

    fun isArmDegenerate(): Boolean = armDegenerate

    fun config(): Config = cfg

    /** 实测校准后回写配置。会重建基准，因为轴定义可能整个换了。 */
    fun updateConfig(next: Config) {
        cfg = next
        resetBaseline()
    }

    /**
     * 打开活跃窗口。调用方在**屏幕亮着时反复调**，窗口就持续滑动；
     * 屏幕灭后自然过期 → 日常佩戴（屏幕常灭）等于识别关闭，几乎零误触。
     *
     * 实现成"滑动窗口"而不是"当前屏幕亮"，是因为翻表盘那一刻屏幕可能已经灭了，
     * 若按瞬时状态判定，play/pause 会被自己掐掉。
     */
    fun arm(now: Long, holdMs: Long = ARM_HOLD_MS) {
        val next = now + holdMs
        if (next > armedUntil) armedUntil = next
    }

    fun disarm() {
        armedUntil = 0L
    }

    /**
     * 调试用：旁路活跃窗口，永远视为已激活。
     *
     * 标定阈值时必须这样——否则离座后屏幕几秒就灭，窗口过期，识别被自己的闸门掐死，
     * 根本没法量"这个动作有多少度"。正式使用时保持 false。
     */
    var bypassArm = false

    fun isArmed(now: Long): Boolean = bypassArm || now < armedUntil

    /* ---------------- 基准的导出与注入（调试 / 离线回放） ---------------- */

    /**
     * 当前基准重力方向。配合 [seedBaseline] 使用。
     * 轨迹日志必须把它一起写下来，否则离线回放时会按错误基准算 φ。
     */
    fun baselineVector(): FloatArray = floatArrayOf(u0x, u0y, u0z)

    fun baselineAtMs(): Long = baselineAt

    /**
     * 把基准与静止参考直接钉在给定重力方向上，并置为 [State.IDLE]。
     *
     * 两个用途，都是被实测逼出来的：
     *
     * 1. **离线回放**：回放一段真实轨迹时，若让识别器从零重建基准，它要求连续
     *    [Config.restHoldMs] 静止——而真实用户在做动作前**是在动的**（抬手、找位置），
     *    那段前置根本不静止。实测 10 段里 6 段因基准建不起来而回放成"无动作"，
     *    差点误判成阈值调错。有了它，回放出的 φ 与现场逐帧一致。
     * 2. **跨亮屏复用**：屏幕灭/进程被冻结期间完全没有加速度样本（实测 `accel=0`），
     *    每次亮屏都要重新等静止才能建基准，用户抬手就做动作必然丢前几下。
     *    调用方可在屏幕灭前存下这个向量、亮屏后注回。
     *
     * ⚠️ 注入**不会**校验姿态是否仍然合理：表在腕上被转过就不要再注旧基准
     *    （那正是 [Config.driftHoldMs] 二级重建要救的场）。调用方负责控制时效。
     *
     * @return 向量无效（接近零）时返回 false，此时不改动任何状态。
     */
    fun seedBaseline(ux: Float, uy: Float, uz: Float, now: Long): Boolean {
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (n < 1e-3f) return false
        // 先清掉所有瞬态（注意 resetBaseline 会把 baselineAt 归零、状态退回 NEED_BASELINE，
        // 所以它必须排在我们写基准之前），再钉住基准并直接进入可触发状态。
        resetBaseline()
        u0x = ux / n
        u0y = uy / n
        u0z = uz / n
        // 静止参考一起钉住，否则第一帧就会被判成"动过"，又要等一整轮静止。
        sx = u0x
        sy = u0y
        sz = u0z
        baselineAt = now
        stillSince = now
        state = State.IDLE
        stateAt = now
        return true
    }

    /* ---------------- 传感器输入 ---------------- */

    /** 加速度计，单位 m/s²。内部做低通求重力，线性加速度（甩动的手感）本身不参与角度。 */
    fun onAccel(ax: Float, ay: Float, az: Float, ts: Long) {
        val dt = if (lastAccelTs == 0L) 0.02f else ((ts - lastAccelTs).coerceIn(1L, 200L) / 1000f)
        lastAccelTs = ts

        val alpha = 1f - exp(-dt / cfg.gravityTauSec)
        if (!gravityReady) {
            gx = ax; gy = ay; gz = az
            gravityReady = true
        } else {
            gx += (ax - gx) * alpha
            gy += (ay - gy) * alpha
            gz += (az - gz) * alpha
        }

        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1f) return
        val ux = gx / n
        val uy = gy / n
        val uz = gz / n

        // 极角：绕设备 Y 轴（≈前臂长轴）的转角 = atan2(ux, uz)；绕设备 X 轴的转角 = atan2(uy, uz)。
        // 两者都用同一套 z 做分母，差就是"相对基准转了多少"。
        armDegenerate = abs(uz) < cfg.degenerateThreshold && abs(ux) < cfg.degenerateThreshold
        if (!armDegenerate) {
            phiArm = wrapDeg(toDeg(atan2(ux, uz)) - toDeg(atan2(u0x, u0z)))
        }
        phiBend = wrapDeg(toDeg(atan2(uy, uz)) - toDeg(atan2(u0y, u0z)))

        // 真实转角（幅度）：无分支、无耦合，比 φ 可靠得多，方向窗口与停稳判定都认它。
        val cosBase = (ux * u0x + uy * u0y + uz * u0z).coerceIn(-1f, 1f)
        thetaDeg = toDeg(acos(cosBase))

        // 判轴用切空间分解，**不要再去比 |φarm| 和 |φbend|**（见 coefArm/coefBend 的类注释）。
        // t = u − (u·u0)u0 是"重力在球面上的运动方向"；参考切向是 tArm = ŷ×u0、tBend = x̂×u0。
        val tx = ux - cosBase * u0x
        val ty = uy - cosBase * u0y
        val tz = uz - cosBase * u0z
        val armDen = u0x * u0x + u0z * u0z                       // |ŷ×u0|²
        coefArm = if (armDen < MIN_TANGENT_SQ) 0f else (tx * u0z - tz * u0x) / armDen
        val bendDen = u0y * u0y + u0z * u0z                      // |x̂×u0|²
        coefBend = if (bendDen < MIN_TANGENT_SQ) 0f else (tz * u0y - ty * u0z) / bendDen

        updateStillness(ux, uy, uz, ts)
        step(ts)
        onSample?.invoke(ts, ux, uy, uz)
    }

    /**
     * 陀螺**不参与任何判定**，只记原始读数模供诊断（见类注释：本机陀螺读数不可用）。
     * 刻意不做 rad→deg 换算——单位本身存疑，换算只会把存疑也藏起来。
     */
    fun onGyro(gxs: Float, gys: Float, gzs: Float, ts: Long) {
        gyroRaw = sqrt(gxs * gxs + gys * gys + gzs * gzs)
    }

    /**
     * 静止判定：[stillSince] 的含义是"**上一次真正运动的时间**"。
     *
     * 判据是"重力方向有没有变过"，不是陀螺读数（陀螺零偏偏大且不稳）。
     * 这个量同时供基准重建、回中驻留、状态机解禁使用。
     */
    private fun updateStillness(ux: Float, uy: Float, uz: Float, ts: Long) {
        val dot = (ux * sx + uy * sy + uz * sz).coerceIn(-1f, 1f)
        if (toDeg(acos(dot)) > cfg.stillTolDeg) {
            // 动了：把静止参考挪到当前姿态，静默计时从头开始。
            sx = ux; sy = uy; sz = uz
            stillSince = ts
            return
        }
        if (stillSince == 0L) stillSince = ts
    }

    /* ---------------- 核心状态机 ---------------- */

    private fun step(now: Long) {
        val absArm = abs(phiArm)
        val absBend = abs(phiBend)
        val peak = maxOf(absArm, absBend)

        // 角速度测量必须跑在状态机之前：触发要用的速率是在"25°→45° 那段上升"里攒出来的，
        // 等进了 CANDIDATE 才开始量就已经晚了。见 [excursionPeakRate]。
        trackExcursionRate(peak, now)

        val stillMs = if (stillSince == 0L) 0L else now - stillSince

        // 摆动指纹：从越过阈值到掉回中位以下算一次摆动，结束时把峰值报出去。
        // 标定时"一个动作 = 一行日志"，不必再从时间轴上去猜哪段对应哪个动作。
        if (peak >= cfg.triggerDeg) {
            if (!recording) {
                recording = true
                recStart = now
                recFired = null
                recArm = 0f; recBend = 0f; recArmMax = 0f; recBendMax = 0f
                recPeakRate = 0f
                recTheta = 0f; recCoefArm = 0f; recCoefBend = 0f
            }
            if (absArm > recArmMax) { recArmMax = absArm; recArm = phiArm }
            if (absBend > recBendMax) { recBendMax = absBend; recBend = phiBend }
            // 速率累计器会在 enterCooldown 里被清掉（防连做动作时串味），
            // 所以摆动期间要单独把最大值抄一份，否则"发了动作的那些摆动"会报成 0。
            if (excursionPeakRate > recPeakRate) recPeakRate = excursionPeakRate
            // 判轴依据也抄 θ 最大那一帧：这样"这次摆动本该发什么"能与"实际发了什么"分开看。
            if (thetaDeg > recTheta) {
                recTheta = thetaDeg
                recCoefArm = coefArm
                recCoefBend = coefBend
            }
        } else if (recording && peak < cfg.crossoverStartDeg) {
            recording = false
            onSwing?.invoke(
                Swing(
                    durMs = now - recStart,
                    armPeak = recArm,
                    bendPeak = recBend,
                    fired = recFired,
                    peakRate = recPeakRate,
                    thetaPeak = recTheta,
                    axisAction = resolveDirection(recTheta, recCoefArm, recCoefBend),
                )
            )
        }

        if (isArmed(now)) {
            when (state) {
                State.NEED_BASELINE -> {
                    // 启动期：任何稳定姿态都先收下来当基准——没有基准就算不出 φ，
                    // 而"随便一个姿态"远比"用户特意摆一次中位"容易等到。
                    maintainBaseline(peak, stillMs, now)
                }

                State.IDLE -> {
                    if (maintainBaseline(peak, stillMs, now)) return
                    if (absArm >= cfg.faceDownDeg && !flipFired && now >= quietUntil) {
                        enterFaceDown(now)
                        return
                    }
                    // ⚠️ 触发认 **θ**，不认 peak：peak 是 max(|φarm|,|φbend|)，副轴 φ 的耦合量
                    // 会在转角大时反超主轴（纯旋前 74° 时 |φbend|=94 > φarm=74），
                    // 拿它当触发量会让"什么时候开始判"也被耦合带偏。
                    if (thetaDeg >= cfg.triggerDeg) {
                        // 速率已由 trackExcursionRate 在上升段里攒好，这里只管起停稳计时。
                        settleRef = thetaDeg
                        settleSince = now
                        state = State.CANDIDATE
                        stateAt = now
                    }
                }

                State.CANDIDATE -> {
                    // 翻转优先：一路转过去超过 faceDownDeg 就按翻转处理，不再当方向。
                    if (absArm >= cfg.faceDownDeg && !flipFired && now >= quietUntil) {
                        enterFaceDown(now)
                        return
                    }
                    if (thetaDeg < cfg.triggerDeg || now - stateAt > cfg.candidateTimeoutMs) {
                        // 掉回阈值以下 / 超时 → 本次动作没成形，退回中位等待。
                        state = State.IDLE
                        return
                    }
                    // 停稳 = 角度**真的**不动。匀速穿过阈值区间（翻表盘就是这种）永远攒不满计时，
                    // 所以不会在翻转途中误发方向键。这里同样量 θ（与幅度窗口同一个量）。
                    if (abs(thetaDeg - settleRef) > cfg.settleTolDeg) {
                        settleRef = thetaDeg
                        settleSince = now
                    }
                    if (now - settleSince >= cfg.settleMs) {
                        // 快不快只看**峰值角速度**，不看穿越耗时：人的动作是快起动+慢收尾，
                        // 拿"整段耗时"当判据会把减速段算进去，然后整段拒掉（段4 漏发的根因）。
                        val fastEnough = excursionPeakRate >= cfg.crossRateDegPerSec
                        // 慢慢抬上来的（fastEnough 为假）也进 COOLDOWN：
                        // 否则它会一直挂在 CANDIDATE，用户停住不动就永远走不掉。
                        if (fastEnough && now >= quietUntil) {
                            pickDirection()?.let { fire(it, now) }
                        }
                        // 无论有没有真的发出动作都进不应期：这样"一次摆手"最多只被处理一次，
                        // 不会在同一个动作里反复进出候选。
                        enterCooldown(now)
                    }
                }

                State.COOLDOWN -> {
                    // 不应期到点即解禁，**不看当前角度**。
                    // 一旦这里附加"必须在某个姿态"的条件，连续做动作时就会再次死锁——
                    // 这是第一版和第二版连踩两次的坑，别再往里加条件了。
                    if (absArm >= cfg.faceDownDeg && !flipFired && now >= quietUntil) {
                        enterFaceDown(now)
                        return
                    }
                    if (now >= cooldownUntil) {
                        state = State.IDLE
                        maintainBaseline(peak, stillMs, now)
                    }
                }

                State.FACE_DOWN -> {
                    // 施密特迟滞：必须一路翻回 faceUpHystDeg 以下才算翻回来。
                    if (absArm < cfg.faceUpHystDeg) {
                        // 翻回来是**静默复位**，不再发动作——否则翻回来会再触发一次 play/pause。
                        flipFired = false
                        enterCooldown(now)
                    }
                }
            }
        } else {
            // 窗口关闭：不产生任何动作，但要保持在"随时可触发"的干净状态。
            if (now >= cooldownUntil || peak < cfg.restDeg) {
                state = State.IDLE
                flipFired = false
                maintainBaseline(peak, stillMs, now)
            }
        }
    }

    private fun enterFaceDown(now: Long) {
        fire(ACTION_TOGGLE, now)
        flipFired = true
        state = State.FACE_DOWN
        stateAt = now
    }

    /**
     * 离位过程中的**峰值角速度**估计，供 [Config.crossRateDegPerSec] 判"是有意摆腕还是慢慢抬手"。
     *
     * 一次"离位"的定义：peak 起过 [Config.restDeg]（=离开中位）到再掉回它以下。
     * 期间每攒够 [Config.rateWindowMs] 取一次 `Δpeak/Δt`，全程留**最大**值。
     * 取 max 而不是均值，正是为了不被"减速到位"那段拉低——人类动作的特征是
     * "快起动 + 慢收尾"，平均会把这个特征抹平，最大值不会。这也正是换掉旧判据的原因：
     * 旧的"25°→45° 耗时"测的就是**整段**（含减速），实测干净转腕要 800ms 而被整段拒掉。
     *
     * ⚠️ 不要用逐帧差分代替这里的窗口采样：50Hz 下重力低通的残余噪声会被 1/dt 放大成
     * 几百 °/s 的假峰，阈值就形同虚设。
     */
    private fun trackExcursionRate(peak: Float, now: Long) {
        if (peak < cfg.restDeg) {
            // 回到中位：本次离位结束，下一段从头量。
            excursionActive = false
            excursionPeakRate = 0f
            rateRefPeak = 0f
            return
        }
        if (!excursionActive) {
            excursionActive = true
            excursionPeakRate = 0f
            rateRefPeak = peak
            rateAt = now
            return
        }
        val dtMs = now - rateAt
        if (dtMs < cfg.rateWindowMs) return
        val rate = (peak - rateRefPeak) * 1000f / dtMs
        if (rate > excursionPeakRate) excursionPeakRate = rate
        rateRefPeak = peak
        rateAt = now
    }

    /**
     * 方向仲裁：**比切空间系数 [coefArm] / [coefBend]，不比 `|φarm|` / `|φbend|`**。
     * 前臂轴赢 → 左右；屈伸轴赢 → 上下。幅度窗口由 θ 单独卡。
     *
     * ⚠️ 这一条是 2026-09-15 真机血案换来的，**别改回"谁的 φ 大听谁的"**：
     * 用户做"向左"（纯旋前）转到 74° 时，两个极角读成 `φarm=+73.92 / φbend=+94.25`，
     * 比大小判据判成 `up` 发了出去（轮次 8 里 6 次 `up` 全是 left 尝试，用户看到的就是
     * "向左没反应"）。根因是 φ 的两条式子**共用 uz 做分母**：纯旋前有闭式解 `φarm ≡ θ`，
     * 而 `φbend` 是纯耦合项，在 θ≈72° 处反超 φarm——佩戴姿态只要让 u0y 稍大就必然踩中。
     *
     * 换成切空间系数后，同一段动作读成 `coefArm=+0.96 / coefBend=−0.11`，
     * 不论转到多少度、基准怎么斜，判轴都稳。
     */
    private fun pickDirection(): String? = resolveDirection(thetaDeg, coefArm, coefBend)

    /**
     * 判轴 + 幅度窗口的**纯函数**版，也是 [Swing.axisAction] 用的那个版本。
     *
     * 单独抽出来是为了让"这次摆动本该判成什么"能被离线打印出来（含没触发动作的摆动）——
     * 标定时最需要的恰恰是那些没发的动作，只看命中日志永远不知道为什么。
     */
    private fun resolveDirection(theta: Float, cArm: Float, cBend: Float): String? {
        if (theta < cfg.triggerDeg || theta > cfg.directionMaxDeg) return null
        return if (abs(cArm) >= abs(cBend)) {
            // 前臂轴：coefArm 的符号与 φarm 相同（正 = 左），沿用实测校准的 armPos/armNeg。
            if (cArm > 0f) cfg.armPosAction ?: defaultArmAction(true)
            else cfg.armNegAction ?: defaultArmAction(false)
        } else {
            // 屈伸轴：⚠️ coefBend 的符号与 φbend **相反**，别照抄 arm 那半边。
            // 依据：绕设备 +x̂ 的**右手**旋转给 coefBend = +sinθ，而同一次转动 φbend = −θ
            // （平地基准 u0=(0,0,1) 时 Rx(θ)u0 = (0,−sinθ,cosθ) → atan2(uy,uz) = −θ）。
            // 而上下映射是在 φ 口径下校准的（φbend > 0 = up），所以这里必须翻过来。
            // 实测佐证：现场深压腕 φbend = −118.06 时 coefBend = +0.92（Dump 台）。
            if (cBend > 0f) cfg.bendNegAction else cfg.bendPosAction
        }
    }

    /**
     * 前臂轴符号的默认映射。**这只是兜底**——腕部几何（表戴得正不正、表冠朝哪边）
     * 极易推反，真实映射必须靠调试页实测校准，见 [Config.armPosAction]。
     */
    private fun defaultArmAction(positive: Boolean): String {
        val leftFirst = if (positive) ACTION_LEFT else ACTION_RIGHT
        val rightFirst = if (positive) ACTION_RIGHT else ACTION_LEFT
        return if (cfg.armLeft) leftFirst else rightFirst
    }

    private fun fire(action: String, now: Long) {
        if (!isArmed(now)) return
        val e = Event(action, state, phiArm, phiBend, now, thetaDeg, coefArm, coefBend)
        lastEvent = e
        eventCount++
        recFired = action
        onEvent?.invoke(e)
    }

    /** 进入不应期。所有产生动作的路径都必须走这里，否则会漏设冷却。 */
    private fun enterCooldown(now: Long) {
        state = State.COOLDOWN
        stateAt = now
        cooldownUntil = now + cfg.refractoryMs
        // 顺手收掉本次离位的速率累计：否则"不回中就连做两个动作"时，
        // 后一个慢动作会继承前一个快动作的峰值速率而误发。
        excursionActive = false
        excursionPeakRate = 0f
        rateRefPeak = 0f
    }

    /**
     * 基准 g0 的维护。分三档：
     *
     * 1. **启动期**（NEED_BASELINE）：任何稳定姿态都收下当基准 —— 没有基准就算不出 φ。
     * 2. **一级（正常回中）**：稳定 [Config.restHoldMs] 且已经贴着当前基准（peak < restDeg）。
     * 3. **二级（表在腕上转位兜底）**：稳定 [Config.driftHoldMs] 就收，**不管角度**。
     *
     * 二级是必要的：表戴久了会在腕上转一点角度，此时"中位"和旧基准差了 30°，
     * 一级（贴基准）永远不成立 → φ 被永久偏置、四向阈值全失准、解禁也出不来。
     * 但二级**天然危险**：它在"用户连续做动作的间隙"里也满足条件，会把基准搬到一个
     * 动作中间的姿态上，之后角度全乱。所以：只在 IDLE / NEED_BASELINE 里调用它，
     * 且重建后进入 [quietUntil] 静默期。
     *
     * FACE_DOWN 里**一律不重建**：翻着表停几秒就把基准搬到朝下姿态上，会毁掉翻转状态。
     */
    private fun maintainBaseline(peak: Float, stillMs: Long, now: Long): Boolean {
        if (state == State.FACE_DOWN) return false
        // 刚重建过就别再重建：静止时一级条件每帧都成立，不挡一下会 50Hz 无限重建，
        // 而且"基准建立于多久前"这个调试信息会永远显示 0s，看不出基准跟没跟上。
        if (baselineAt != 0L && now - baselineAt < MIN_REBASE_GAP_MS) return false
        val boot = state == State.NEED_BASELINE
        val tier1 = stillMs >= cfg.restHoldMs && peak < cfg.restDeg
        val tier2 = stillMs >= cfg.driftHoldMs
        if ((boot && stillMs >= cfg.restHoldMs) || tier1 || tier2) {
            // 二级且不在基准附近 → 是"转位救援"，需要静默期；正常回中不需要。
            val drifting = tier2 && !tier1 && !(boot && stillMs >= cfg.restHoldMs)
            rebuildBaseline(now)
            if (drifting) quietUntil = now + DRIFT_QUIET_MS
            return true
        }
        return false
    }

    private fun rebuildBaseline(now: Long) {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1f) return
        u0x = gx / n
        u0y = gy / n
        u0z = gz / n
        baselineAt = now
        phiArm = 0f
        phiBend = 0f
        thetaDeg = 0f
        coefArm = 0f
        coefBend = 0f
        flipFired = false
        state = State.IDLE
        stateAt = now
    }

    private fun resetBaseline() {
        state = State.NEED_BASELINE
        baselineAt = 0L
        stateAt = 0L
        stillSince = 0L
        cooldownUntil = 0L
        excursionActive = false
        excursionPeakRate = 0f
        rateRefPeak = 0f
        rateAt = 0L
        phiArm = 0f
        phiBend = 0f
        thetaDeg = 0f
        coefArm = 0f
        coefBend = 0f
        flipFired = false
        quietUntil = 0L
    }

    /** 动作回调。调用方接 bridge 时把这里连到 sendAction；调试模式只记日志。 */
    var onEvent: ((Event) -> Unit)? = null

    /** 每次摆动结束都会回调一次（**含未触发动作的**），用于标定轴→动作映射。 */
    var onSwing: ((Swing) -> Unit)? = null

    fun snapshot(now: Long): Snapshot = Snapshot(
        state = state,
        phiArm = phiArm,
        phiBend = phiBend,
        thetaDeg = thetaDeg,
        coefArm = coefArm,
        coefBend = coefBend,
        stillMs = if (stillSince == 0L) 0L else now - stillSince,
        coolRemainMs = (cooldownUntil - now).coerceAtLeast(0L),
        rateDegPerSec = excursionPeakRate,
        gyroRaw = gyroRaw,
        armed = isArmed(now),
        armRemainMs = (armedUntil - now).coerceAtLeast(0L),
        lastEvent = lastEvent,
        eventCount = eventCount,
        baselineAt = baselineAt,
    )

    private fun toDeg(rad: Float) = rad * 180f / PI_F
    private fun wrapDeg(d: Float): Float {
        var v = d
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
