import io.wristdeck.gesture.WristGestureDetector;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 边界回归 + 真实轨迹回放。
 *
 * 两种用法：
 *   java Sim2                     跑边界回归套件
 *   java Sim2 <logfile>           回放日志里 GestureProbe dumpTrace 打出的真实轨迹
 *
 * ## 建模边界（踩了三轮才说清楚，别急着"修"下面的 SKIP 项）
 * 识别器用两个极角表达腕部姿态：φarm=atan2(ux,uz)、φbend=atan2(uy,uz)（各减基准），
 * 两条式子**共用同一个 uz**。所以 |主轴| 一旦越过 ±90°，uz 必趋于 0 并在某点变号，
 * atan2 跳到另一分支，φ 出现约 180° 的伪跳变。这不是 bug，是这套表示的固有性质，
 * 也正是不设方向上限就会"翻转顺带发个方向"的根本原因。
 *
 * ⚠️ 2026-09-15 起**判轴已经不看 φ 谁大了**，改成切空间系数
 * `coefArm = t·(ŷ×u0)/|ŷ×u0|²`、`coefBend = t·(x̂×u0)/|x̂×u0|²`（`t = u − (u·u0)u0`），
 * 幅度窗口改用 `θ = acos(u0·u)`。原因见 A10~A12 的注释：φ 的耦合项会在 θ≈72° 处反超主轴，
 * 把用户的 left 判成 up。**φ 现在只用于翻转判定（|φarm| ≥ 140）和日志**。
 * 下面"极角模型"造姿态的用法没变——它造的是姿态，识别器读出来仍然对得上现场。
 *
 * 基准 u0 是从现场日志**反解**的，不是拍的：
 *   观测 1（翻转）：绕前臂轴转 180° 会让两条轴都读到 ±180 → u0 大致垂直于前臂轴；
 *   观测 2（深压腕）：φarm=4.91、φbend=-118.06，且 退化=true（|uz|<0.25 且 |ux|<0.25）。
 * 两条观测共同把 u0 逼成唯一解 u0=(-0.2929,-0.6103,-0.7360)（极角 A0=-158.30°、B0=-140.34°）。
 *
 * 代价是这个基准下"上抬"方向只到 +39.66° 就撞分支切点，所以**上抬（+φbend）不做合成断言**，
 * 只能靠真实轨迹回放验证。同理，两轴同时大角度（斜甩）的仲裁也不做断言。
 */
public class Sim2 {

    static final long DT = 20;
    static final float G = 9.81f;

    static WristGestureDetector d;
    static long t;
    static float[] base;
    static float[] u;
    static int hits;
    static String lastAction = "-";
    static StringBuilder out = new StringBuilder();
    static int fails = 0;

    /**
     * 本场景中出现过的最大峰值速率（°/s）。
     *
     * 记它是为了**让阈值有据可依**：A8/A9 只证明了"快的发、慢的不发"，
     * 但阈值到底该放 60 还是 50，得看两个场景实测各是多少。不打印就只能靠猜。
     */
    static float rateMax = 0f;

    /* ---------- 模型 1：极角参数化（方向，φbend ≤ +39° / φarm ±90°） ---------- */

    static final double A0 = -158.30, B0 = -140.34;
    static final double UZSIGN = -1.0;

    static float[] poseByAngles(double dArm, double dBend) {
        double ta = Math.tan(Math.toRadians(A0 + dArm));
        double tb = Math.tan(Math.toRadians(B0 + dBend));
        double uz = UZSIGN / Math.sqrt(1 + ta * ta + tb * tb);
        return new float[]{(float) (uz * ta), (float) (uz * tb), (float) uz};
    }

    /* ---------- 模型 2：绕轴 180°（翻转） ---------- */

    static float[] rotY(float[] v, double deg) {
        double a = Math.toRadians(deg);
        return new float[]{(float) (v[0] * Math.cos(a) + v[2] * Math.sin(a)), v[1],
                (float) (-v[0] * Math.sin(a) + v[2] * Math.cos(a))};
    }

    /* ---------- 喂数据 ---------- */

    /** 非 null 时进入"录制"模式：不喂识别器，只把帧写成真机日志格式。 */
    static StringBuilder rec = null;

    static void feed() {
        if (rec != null) {
            rec.append(String.format("%d:%.3f:%.3f:%.3f ", t, u[0], u[1], u[2]));
            t += DT;
            return;
        }
        d.onAccel(u[0] * G, u[1] * G, u[2] * G, t);
        float r = d.snapshot(t).getRateDegPerSec();
        if (r > rateMax) rateMax = r;
        t += DT;
    }

    static void hold(int ms) {
        for (int i = 0; i < ms / DT; i++) feed();
    }

    static void rampAngles(double aFrom, double aTo, double bFrom, double bTo, int ms) {
        int n = (int) Math.max(1L, ms / DT);
        for (int i = 1; i <= n; i++) {
            u = poseByAngles(aFrom + (aTo - aFrom) * i / n, bFrom + (bTo - bFrom) * i / n);
            feed();
        }
    }

    static void rampY(double fromDeg, double toDeg, int ms) {
        int n = (int) Math.max(1L, ms / DT);
        for (int i = 1; i <= n; i++) {
            u = rotY(base, fromDeg + (toDeg - fromDeg) * i / n);
            feed();
        }
    }

    static String snap() {
        WristGestureDetector.Snapshot s = d.snapshot(t);
        return String.format("φarm=%6.1f φbend=%6.1f st=%-9s 退化=%s",
                s.getPhiArm(), s.getPhiBend(), s.getState(), d.isArmDegenerate());
    }

    static int expectHits;
    static String expectAction = "";

    static void step(String name, Runnable body, String action, int count) {
        int before = hits;
        rateMax = 0f;
        body.run();
        int got = hits - before;
        String act = got == 0 ? "无动作" : lastAction;
        boolean ok = got == count && (count == 0 || act.equals(action));
        if (!ok) fails++;
        out.append(String.format("%-40s 期望=%-6s×%d 实际=%-6s×%d %s | 峰值速率=%5.1f°/s(阈60) %s%n",
                name, action, count, act, got, ok ? "OK  " : "FAIL", rateMax, snap()));
    }

    static void newDetector() {
        d = new WristGestureDetector();
        d.setBypassArm(true);
        d.setOnEvent(new Function1<WristGestureDetector.Event, Unit>() {
            public Unit invoke(WristGestureDetector.Event e) {
                hits++;
                lastAction = e.getAction();
                return Unit.INSTANCE;
            }
        });
    }

    static void resetAngles() {
        newDetector();
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
    }

    static void resetFlat() {
        newDetector();
        t = 0;
        base = new float[]{0f, 0f, 1f};
        u = base;
        hold(1500);
    }

    /**
     * 本轮真机故障复刻用的佩戴基准（2026-09-15，wrist8.log）。
     *
     * 这个向量是**从现场读数反解**出来的，不是拍的：该姿态下"严格绕前臂轴纯旋前 73.92°"
     * 会读成 `φarm=73.92 / φbend=+94.26`，与日志里那 6 次被发成 `up` 的 left 尝试逐位吻合
     * （误差 0.01°）。它的物理含义只是——**表相对前臂轴斜置了 6°**，正常佩戴就会这样。
     *
     * 于是暴露了旧判据的结构性缺陷：φ 的两条式子共用 uz 做分母，纯旋前有闭式解 φarm ≡ θ，
     * 而 φbend 是纯耦合项，在 θ≈72° 处反超 φarm ⇒ "谁大听谁的"开始把 left 判成 up。
     */
    static final float[] FX_BASE = {0.295f, 0.108f, 0.949f};

    static void resetTo(float[] baseVec) {
        newDetector();
        t = 0;
        base = baseVec.clone();
        u = base;
        hold(1500);
    }

    /**
     * 自检：模型造出的姿态，识别器必须自己算出与现场日志一致的角。
     * 只断言 φbend 与退化标志——φarm 在我们模型里恒为 0（dArm 恒 0，退化时冻结在 0），
     * 现场读到的 4.91 来自"基准与挥手前静止姿态的微小偏差"，不是压腕本身产生的。
     */
    static void selfCheck() {
        newDetector();
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
        u = poseByAngles(0, -118.06);
        hold(400);
        WristGestureDetector.Snapshot s = d.snapshot(t);
        boolean ok = Math.abs(s.getPhiBend() + 118.06) < 2.0
                && d.isArmDegenerate() && Math.abs(s.getPhiArm()) < 10f;
        if (!ok) fails++;
        out.append(String.format(
                "自检 复现现场深压腕姿态  期望 φbend≈-118.1 退化=true  实际 φbend=%.1f φarm=%.1f 退化=%s %s%n%n",
                s.getPhiBend(), s.getPhiArm(), d.isArmDegenerate(), ok ? "OK" : "FAIL"));
    }

    static void suite() {
        out.append("========== 边界回归（四向 45~130，死区 130~140，翻转 >=140）==========\n\n");
        selfCheck();

        out.append("【A】方向轴（极角模型，基准由现场日志反解）\n");
        resetAngles();
        step("A1 屈伸 -118.1° 深压腕（现场漏发那条）", () -> {
            rampAngles(0, 0, 0, -118.06, 250); hold(600); rampAngles(0, 0, -118.06, 0, 350); hold(400);
        }, "down", 1);

        resetAngles();
        step("A2 屈伸 -60° 常规压腕", () -> {
            rampAngles(0, 0, 0, -60, 250); hold(600); rampAngles(0, 0, -60, 0, 350); hold(400);
        }, "down", 1);

        resetAngles();
        step("A3 屈伸 -85° 贴可表达上限", () -> {
            rampAngles(0, 0, 0, -85, 250); hold(600); rampAngles(0, 0, -85, 0, 350); hold(400);
        }, "down", 1);

        resetAngles();
        step("A4 前臂 +60° 左转", () -> {
            rampAngles(0, 60, 0, 0, 250); hold(600); rampAngles(60, 0, 0, 0, 350); hold(400);
        }, "left", 1);

        resetAngles();
        step("A5 前臂 -60° 右转", () -> {
            rampAngles(0, -60, 0, 0, 250); hold(600); rampAngles(-60, 0, 0, 0, 350); hold(400);
        }, "right", 1);

        resetAngles();
        step("A6 慢慢压到 -60°（2s，防抬手看表）", () -> {
            rampAngles(0, 0, 0, -60, 2000); hold(400); rampAngles(0, 0, -60, 0, 400); hold(500);
        }, "无动作", 0);

        resetAngles();
        step("A7 深压腕 -118° 连做两次（隔 1s）", () -> {
            rampAngles(0, 0, 0, -118, 250); hold(600); rampAngles(0, 0, -118, 0, 300); hold(1000);
            rampAngles(0, 0, 0, -118, 250); hold(600); rampAngles(0, 0, -118, 0, 300); hold(400);
        }, "down", 2);

        /*
         * A8 是**真机段4 的逐段复刻**，也是"穿越耗时 → 峰值速率"这次改判据的守门测试。
         *
         * 现场日志（φarm，已反解基准）：
         *   47.322 -22.83 → 47.524 -39.49   （202ms 走 16.66° = 82°/s）
         *   48.134 -45.67                    （再 610ms 只走 6.18° = 10°/s）
         * 即"快起动 + 慢到位"。整段 25°→45° 用了约 715ms，
         * 旧判据（必须在 400ms 内完成）把它判成"慢慢抬手"整段拒掉 —— 所以现场漏发。
         * **谁要是把判据改回耗时型，这条必挂。**
         *
         * ⚠️ 2026-09-15 把这条的基准从"极角模型反解基准"换成了 [FX_BASE]（纯前臂转动）：
         * 幅度窗口改量 θ 之后，极角模型的极端基准（uz<0）会让 45.7° 的 φarm 只对应 35.9° 的
         * 真实转角，于是这条会在**测时序之前**就被"幅度不够"挡掉、失去意义。
         * 换基准后 φarm ≡ θ，这条测的就纯粹是"快起动+慢到位能不能发"。
         * 角度数与时间剖面**保持与现场一致**，判据守门的作用不变。
         */
        resetTo(FX_BASE);
        step("A8 真机段4复刻：快起动+慢到位（旧耗时判据会漏发）", () -> {
            rampY(0, -22.8, 280);      // 起动段 ~81°/s
            rampY(-22.8, -39.5, 202);  // 主冲段 ~82°/s
            rampY(-39.5, -45.7, 610);  // 减速到位 ~10°/s
            hold(600);
            rampY(-45.7, 0, 400); hold(500);
        }, "right", 1);

        // A9 = 与 A8 同形状、但整体速率都在阈值以下（峰值 ~44°/s）：必须拒掉。
        // 没有它，A8 只证明"快的能发"，证明不了判据真的在量速率而不是量形状/幅度。
        // 同 A8，基准也用 FX_BASE：否则极角模型会把 θ 压到 45 以下，
        // 这条就变成"因为幅度不够而被拒"，测不到速率判据。
        resetTo(FX_BASE);
        step("A9 同形状但峰值仅 ~44°/s（不该发）", () -> {
            rampY(0, -25, 700);
            rampY(-25, -56, 700);
            hold(600);
            rampY(-56, 0, 800); hold(500);
        }, "无动作", 0);

        /*
         * A10~A12：**真机「向左没反应」故障的守门测试**（2026-09-15）。
         *
         * 现场：用户做 left（纯旋前）到 74° 左右，被发成 `up`——25 次发送里 6 次
         * `φarm=+50~78 / φbend=+75~106` 的 `up` 全是 left 尝试。根因不是漏识别，
         * 而是判轴用了"|φarm| 和 |φbend| 谁大"，而 φbend 是纯耦合项、会反超。
         *
         * **谁把判轴改回比 φ 的大小，A10 必挂（判成 up）。**
         * A11 额外压住"幅度上限"那条：θ=85 时旧口径的 |φbend| 已涨到 132~153，
         * 用 φ 当上限会把整段合法动作拒掉；改用 θ 才有余量。
         */
        resetTo(FX_BASE);
        step("A10 真机复刻：斜置6°基准下纯旋前 74°（旧判据发 up）", () -> {
            rampY(0, 74, 250); hold(900);
            rampY(74, 0, 300); hold(400);
        }, "left", 1);

        resetTo(FX_BASE);
        step("A11 同基准纯旋前 85°（旧口径 φ 上限也会拒掉它）", () -> {
            rampY(0, 85, 300); hold(900);
            rampY(85, 0, 300); hold(400);
        }, "left", 1);

        // A12 反向对照：同一基准下旋后（right）的耦合换到另一侧，
        // 旧判据本来就能过——加它是防止"修 left 顺手把 right 弄反"。
        resetTo(FX_BASE);
        step("A12 同基准纯旋后 74°（反向对照，必须是 right）", () -> {
            rampY(0, -74, 250); hold(900);
            rampY(-74, 0, 300); hold(400);
        }, "right", 1);

        out.append("── 以下两项**不做断言**（见类注释的建模边界，不是识别器问题）\n");
        out.append("   · 上抬 +φbend：反解出的基准只到 +39.66°，再往上必撞分支切点\n");
        out.append("   · 两轴同时大角度（斜甩）：切空间分解能给出偏向哪根轴，但用户到底想做哪个无唯一答案\n\n");

        out.append("【B】翻转（反向模型；抬上限后最大风险是翻转蹭出一个方向）\n");
        resetFlat();
        step("B1 翻转 0→180° 400ms", () -> { rampY(0, 180, 400); hold(600); }, "toggle", 1);
        resetFlat();
        step("B2 翻转 0→180° 900ms（慢翻转）", () -> { rampY(0, 180, 900); hold(600); }, "toggle", 1);
        resetFlat();
        step("B3 翻转 0→180° 180ms（猛甩）", () -> { rampY(0, 180, 180); hold(600); }, "toggle", 1);
        // B4 原写"只翻到 120° 就停（分支切点附近，不该发）"，判据换成 θ/切空间后本行会发
        // left。**这是设计使然，不是回归**，理由三条（都已核对，别照旧标签改回去）：
        //   ① 本模型里 rampY 那根轴同时是"前臂旋前/旋后"轴和"翻转"轴（真人也是同一根），
        //      区分全靠**幅度分层**：45~130=方向、130~140=死区、≥140=翻转。120 落在方向
        //      窗口内 ⇒ 发 left 才是对的；"绕另一姿态只翻到 120°"纯 Y 旋转建模不出来。
        //   ② 旧判据在这里发不出，正是因为要修的那个缺陷：θ=120 时 u=(0.866,0,-0.5)、uz<0
        //      触发 φ 分支切点，φbend 被读成 180（纯伪值，真耦合是 0），主轴判给屈伸、幅度
        //      又超 130 ⇒ 静默。θ 是真实转角、无分支 ⇒ 这里"多"出方向，与 A11 同源。
        //   ③ 真实翻转不会蹭出方向：翻转是**连续**穿过 120° 的，而方向必须"停稳"才发
        //      ⇒ 见 B1/B2/B3 只出 toggle、零方向。只有在 120° 真停住才会发 left，符合预期。
        resetFlat();
        step("B4 绕 Y 转 120°（<130 仍在方向窗口内，应发 left 而非翻转）", () -> {
            rampY(0, 120, 250); hold(900); rampY(120, 0, 350); hold(400);
        }, "left", 1);
        resetFlat();
        step("B5 翻到 143° 停住（刚过翻转线）", () -> { rampY(0, 143, 250); hold(600); }, "toggle", 1);
        resetFlat();
        step("B6 翻到 139° 停住（>130 死区，不进翻转）", () -> {
            rampY(0, 139, 250); hold(900); rampY(139, 0, 350); hold(400);
        }, "无动作", 0);
        resetFlat();
        step("B7 翻过去再翻回来（静默复位，不重复发）", () -> {
            rampY(0, 180, 400); hold(600); rampY(180, 0, 400); hold(700);
        }, "toggle", 1);

        out.append("\n【C】连招与恢复（状态机别被锁死）\n");
        resetFlat();
        step("C1 翻转 → 翻回 → 再做一个 +60° 前臂", () -> {
            rampY(0, 180, 400); hold(600);
            rampY(180, 0, 400); hold(700);
            rampY(0, 60, 200); hold(500); rampY(60, 0, 300); hold(400);
        }, "left", 2);

        out.append(String.format("%n========== 失败项：%d ==========%n", fails));
        System.out.print(out);
        if (fails > 0) System.exit(1);
    }

    /* ==================== 真实轨迹回放 ==================== */

    /** 回放时逐帧打印（排查"为什么没发"用）。 */
    static boolean verbose = false;

    /** 匹配 dumpTrace 打的 `1234:-0.293:-0.610:-0.736` 帧。 */
    static final Pattern FRAME = Pattern.compile(
            "(\\d+):(-?\\d+(?:\\.\\d+)?):(-?\\d+(?:\\.\\d+)?):(-?\\d+(?:\\.\\d+)?)");

    /** 匹配轨迹表头的 `基准=-0.151,-0.185,0.971`。 */
    static final Pattern BASE = Pattern.compile(
            "基准=(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");

    /**
     * 回放日志里的真实轨迹。
     *
     * 每个 `GEST 轨迹 N帧` 头起一段，喂给**全新的**识别器（一段 = 一次摆动），
     * 并把表头里的 `基准=` **注回**识别器（[WristGestureDetector.seedBaseline]）。
     *
     * 为什么必须注基准：识别器从零建基准要求连续静止 [Config.restHoldMs]（800ms），
     * 但真机上用户是**连着做动作**的——实测段1 那 2.3s 窗口里 `静` 从没超过 64ms，
     * 于是整段都停在 NEED_BASELINE，回放出来全是"无动作/峰值 0"，而现场明明命中了。
     * 不注基准就会把"回放工具的局限"误判成"阈值调错了"（这个坑已经踩过一次）。
     * 用相对时间而非挂钟时间喂进去：状态机只依赖 dt，而 GestureProbe 存的正是帧间增量。
     */
    static void replay(String path) throws Exception {
        List<List<float[]>> segs = new ArrayList<>();
        List<Integer> durs = new ArrayList<>();
        List<float[]> bases = new ArrayList<>();
        List<float[]> cur = null;
        Pattern dur = Pattern.compile("摆动 (\\d+)ms");
        int pendingDur = 0;
        boolean sawFormatTag = false;
        int shortSegs = 0;
        int seeded = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.contains("GEST")) continue;
                if (line.contains("轨迹")) {
                    // 「摆动」行打在「轨迹」行**之前**，所以时长要先挂起，等段开出来再落。
                    cur = new ArrayList<>();
                    segs.add(cur);
                    durs.add(pendingDur);
                    pendingDur = 0;
                    Matcher bm = BASE.matcher(line);
                    bases.add(bm.find() ? new float[]{
                            Float.parseFloat(bm.group(1)),
                            Float.parseFloat(bm.group(2)),
                            Float.parseFloat(bm.group(3))} : null);
                    if (line.contains("ux:uy:uz")) sawFormatTag = true;
                    continue;
                }
                if (line.contains("摆动")) {
                    Matcher m = dur.matcher(line);
                    if (m.find()) pendingDur = Integer.parseInt(m.group(1));
                    continue;
                }
                // 只吃轨迹行：GEST 后面跟 "  " 缩进的那些
                if (cur == null || line.indexOf("GEST  ") < 0) continue;
                Matcher m = FRAME.matcher(line);
                while (m.find()) {
                    cur.add(new float[]{
                            Float.parseFloat(m.group(1)),   // 累计毫秒
                            Float.parseFloat(m.group(2)),   // ux
                            Float.parseFloat(m.group(3)),   // uy
                            Float.parseFloat(m.group(4)),   // uz
                    });
                }
            }
        }
        if (segs.isEmpty()) {
            out.append("日志里没有任何「GEST 轨迹」段。\n");
            out.append("说明这份日志是旧版本打的（轨迹记录是后加的），重新做一轮动作再回放。\n");
            System.out.print(out);
            return;
        }

        out.append("========== 真实轨迹回放（" + segs.size() + " 段）==========\n");
        out.append(String.format("%-5s %-6s %-9s %-9s %-10s %s%n",
                "段", "帧数", "φarm峰值", "φbend峰值", "回放结果", "备注"));
        for (int si = 0; si < segs.size(); si++) {
            List<float[]> seg = segs.get(si);
            if (seg.size() < 3) {
                shortSegs++;
                continue;
            }
            newDetector();
            t = 0;
            float[] b0 = bases.get(si);
            if (b0 != null && d.seedBaseline(b0[0], b0[1], b0[2], 0L)) seeded++;
            float minA = 0, maxA = 0, minB = 0, maxB = 0;
            int before = hits;
            String act = "-";
            int frameNo = 0;
            for (float[] f : seg) {
                /*
                 * 轨迹第一个字段是**累计**毫秒（dumpTrace 里 relMs 是前缀和），不是帧间增量，
                 * 所以这里必须是赋值而不是累加。写成 t += f[0] 会让时间轴平方级膨胀：
                 * 实测 10 帧就走掉 1.1s，接着 stillMs 超过 driftHoldMs(8000) 触发二级重建，
                 * 基准被搬到动作中间、角度全清零 → 所有段都回放成"无动作"。
                 */
                t = (long) f[0];
                u = new float[]{f[1], f[2], f[3]};
                d.onAccel(u[0] * G, u[1] * G, u[2] * G, t);
                WristGestureDetector.Snapshot s = d.snapshot(t);
                // 基准建立前读到的只是"原始极角"（与基准无关），算进峰值会误导，
                // 所以只在离开 NEED_BASELINE 之后统计。
                if (s.getState() != WristGestureDetector.State.NEED_BASELINE) {
                    minA = Math.min(minA, s.getPhiArm()); maxA = Math.max(maxA, s.getPhiArm());
                    minB = Math.min(minB, s.getPhiBend()); maxB = Math.max(maxB, s.getPhiBend());
                }
                if (verbose && si == 0 && (frameNo % 10 == 0 || Math.abs(s.getPhiBend()) > 100)) {
                    out.append(String.format("    #%-4d t=%-6d u=(%6.3f,%6.3f,%6.3f) 读φ=%6.1f/%7.1f st=%-13s 静=%-6d 命中=%d%n",
                            frameNo, t, f[1], f[2], f[3], s.getPhiArm(), s.getPhiBend(),
                            s.getState(), s.getStillMs(), hits - before));
                }
                frameNo++;
            }
            if (hits > before) act = lastAction;
            float pa = Math.abs(maxA) >= Math.abs(minA) ? maxA : minA;
            float pb = Math.abs(maxB) >= Math.abs(minB) ? maxB : minB;
            out.append(String.format("%-5d %-6d %-9.1f %-9.1f %-10s 记录摆动=%dms 命中=%d%n",
                    si + 1, seg.size(), pa, pb, hits > before ? act : "无动作", durs.get(si), hits - before));
        }
        out.append("\n峰值列是回放中两个角各自的最值（不含基准建立前）；「回放结果」是当前阈值下的真实判定。\n");
        out.append("已注入表头基准的段：" + seeded + "/" + (segs.size() - shortSegs)
                + "（未注入的段若显示「无动作」很可能是回放限制，不是阈值问题）。\n");
        if (!sawFormatTag) {
            out.append("⚠️ 这些段的表头没有「格式=t:ux:uy:uz」标记 —— 很可能是**旧版本**打的日志\n");
            out.append("   （旧版每帧只记两个角 φarm:φbend，无法精确回放）。请用当前版本重新做一轮动作。\n");
        }
        if (shortSegs > 0) out.append("（有 " + shortSegs + " 段帧数不足 3，已跳过）\n");
        System.out.print(out);
    }

    /* ==================== 夹具生成 ==================== */

    static final int SIM_CHUNK = 28;

    /**
     * 生成一份与真机 `dumpTrace` 格式**完全一致**的夹具。
     *
     * 为什么不手写夹具：手写的格式容易和真机脱节（第一版就是），
     * 而且姿态值要么靠手算、要么靠反解，都会引入与真机不一致的误差。
     * 由生成器直接吐真机格式，夹具就永远跟得上。
     */
    static void emit(String path) throws Exception {
        StringBuilder sb = new StringBuilder();

        emitSeg(sb, "摆动 620ms θ峰=118.06 φarm=0.00 φbend=-118.06 轴判=down 结果=down", 620, () -> {
            t = 0;
            u = poseByAngles(0, 0);
            hold(1200);
            rampAngles(0, 0, 0, -118.06, 250);
            hold(600);
            rampAngles(0, 0, -118.06, 0, 300);
            hold(400);
        });

        emitSeg(sb, "摆动 560ms θ峰=60.04 φarm=60.04 φbend=0.00 轴判=left 结果=left", 560, () -> {
            t = 0;
            u = poseByAngles(0, 0);
            hold(1200);
            rampAngles(0, 60, 0, 0, 250);
            hold(600);
            rampAngles(60, 0, 0, 0, 300);
            hold(400);
        });

        emitSeg(sb, "摆动 700ms θ峰=179.22 φarm=179.22 φbend=-170.67 轴判=left 结果=toggle", 700, () -> {
            t = 0;
            base = new float[]{0f, 0f, 1f};
            u = base;
            hold(1200);
            rampY(0, 180, 400);
            hold(600);
        });

        Files.write(Paths.get(path), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("已写出夹具 " + path);
    }

    static void emitSeg(StringBuilder sb, String swingLine, int durMs, Runnable body) {
        rec = new StringBuilder();
        body.run();
        String joined = rec.toString().trim();
        String[] toks = joined.isEmpty() ? new String[0] : joined.split(" ");
        long span = toks.length == 0 ? 0 : t - DT;
        sb.append("09-15 01:00:00.000  8901  8901 I WristDeck: GEST ").append(swingLine).append('\n');
        sb.append("09-15 01:00:00.000  8901  8901 I WristDeck: GEST 轨迹 ").append(toks.length)
                .append("帧 span=").append(span).append("ms 静止前置=").append(span - durMs)
                .append("ms 格式=t:ux:uy:uz\n");
        for (int i = 0; i < toks.length; i += SIM_CHUNK) {
            sb.append("09-15 01:00:00.000  8901  8901 I WristDeck: GEST   ");
            for (int j = i; j < Math.min(i + SIM_CHUNK, toks.length); j++) {
                if (j > i) sb.append(' ');
                sb.append(toks[j]);
            }
            sb.append('\n');
        }
        rec = null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--emit")) {
            emit(args[1]);
            return;
        }
        int start = 0;
        if (args.length > 0 && args[0].equals("-v")) {
            verbose = true;
            start = 1;
        }
        if (args.length > start) {
            replay(args[start]);
            return;
        }
        suite();
    }
}
