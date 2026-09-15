import io.wristdeck.gesture.WristGestureDetector;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * 在 Mac 上直接跑识别器（喂合成重力），验证状态机。
 * 用真表试一轮要装 APK、离座、做动作、读日志，代价太高——这正是识别器被设计成零 Android 依赖的原因。
 *
 * 坐标约定：base 是建基准时的重力方向；绕 Y 轴转 = 前臂旋前/旋后（东西），绕 X 轴转 = 腕屈伸（上下）。
 */
public class Sim {

    static final long DT = 20;
    static final float G = 9.81f;

    static WristGestureDetector d;
    static long t;
    static float[] base;
    static float[] u;
    static int hits;
    static String lastAction = "-";
    static StringBuilder out = new StringBuilder();

    static float[] rotY(float[] v, double deg) {
        double a = Math.toRadians(deg);
        return new float[]{(float) (v[0] * Math.cos(a) + v[2] * Math.sin(a)), v[1],
                (float) (-v[0] * Math.sin(a) + v[2] * Math.cos(a))};
    }

    static float[] rotX(float[] v, double deg) {
        double a = Math.toRadians(deg);
        return new float[]{v[0], (float) (v[1] * Math.cos(a) - v[2] * Math.sin(a)),
                (float) (v[1] * Math.sin(a) + v[2] * Math.cos(a))};
    }

    static void feed() {
        d.onAccel(u[0] * G, u[1] * G, u[2] * G, t);
        t += DT;
    }

    static void hold(int ms) {
        for (int i = 0; i < ms / DT; i++) feed();
    }

    static void pose(char axis, double deg) {
        u = (axis == 'Y') ? rotY(base, deg) : rotX(base, deg);
    }

    /** 从 fromDeg 匀速转到 toDeg，耗时 ms。 */
    static void ramp(char axis, double fromDeg, double toDeg, int ms) {
        int n = (int) Math.max(1L, ms / DT);
        for (int i = 1; i <= n; i++) {
            pose(axis, fromDeg + (toDeg - fromDeg) * i / n);
            feed();
        }
    }

    static String snap() {
        WristGestureDetector.Snapshot s = d.snapshot(t);
        return String.format("st=%-13s φarm=%7.1f φbend=%7.1f 中=%4.0f 静=%5.0f",
                s.getState(), s.getPhiArm(), s.getPhiBend(),
                (float) s.getCoolRemainMs(), (float) s.getStillMs());
    }

    static void step(String name, Runnable body, String expectAction, int expectCount) {
        int before = hits;
        body.run();
        int got = hits - before;
        String act = got == 0 ? "无动作" : lastAction;
        boolean ok = got == expectCount && (expectCount == 0 || act.equals(expectAction));
        out.append(String.format("%-32s 期望=%-8s×%d 实际=%-8s×%d %s | %s%n",
                name, expectAction, expectCount, act, got, ok ? "OK  " : "FAIL", snap()));
    }

    static void reset(float[] newBase) {
        d = new WristGestureDetector();
        d.setBypassArm(true);
        d.setOnEvent(new Function1<WristGestureDetector.Event, Unit>() {
            public Unit invoke(WristGestureDetector.Event e) {
                hits++;
                lastAction = e.getAction();
                return Unit.INSTANCE;
            }
        });
        base = newBase;
        u = newBase;
        t = 0;
        hold(1500);
    }

    public static void main(String[] args) {
        out.append("===== 组 1：基准 = 表盘朝上（抬腕后的自然姿势）=====\n");
        reset(new float[]{0f, 0f, 1f});
        out.append("基准建立后 " + snap() + "\n");

        step("1a 前臂轴 +60° 快转", () -> {
            ramp('Y', 0, 60, 200); hold(500); ramp('Y', 60, 0, 300); hold(400);
        }, "left", 1);

        step("1b 前臂轴 -60° 快转", () -> {
            ramp('Y', 0, -60, 200); hold(500); ramp('Y', -60, 0, 300); hold(400);
        }, "right", 1);

        step("1c 屈伸轴 +60°（绕 X）", () -> {
            ramp('X', 0, 60, 200); hold(500); ramp('X', 60, 0, 300); hold(400);
        }, "down", 1);

        step("1d 屈伸轴 -60°（绕 X）", () -> {
            ramp('X', 0, -60, 200); hold(500); ramp('X', -60, 0, 300); hold(400);
        }, "up", 1);

        step("2  慢慢抬到 60°（2s，防抬手）", () -> {
            ramp('Y', 0, 60, 2000); hold(400); ramp('Y', 60, 0, 400); hold(500);
        }, "无动作", 0);

        step("3a 翻表盘 0→180° 必须只发 toggle", () -> {
            ramp('Y', 0, 180, 400); hold(500);
        }, "toggle", 1);

        step("3b 翻回来（静默复位）", () -> {
            ramp('Y', 180, 0, 400); hold(600);
        }, "无动作", 0);

        step("3c 再翻一次（可重复）", () -> {
            ramp('Y', 0, 180, 400); hold(500);
        }, "toggle", 1);

        step("4a 回弹过冲：甩到+60 后立刻弹到-50", () -> {
            ramp('Y', 180, 0, 400); hold(600);
        }, "无动作", 0);
        int mark = hits;
        step("4b   —— 过冲不应连发反向动作", () -> {
            ramp('Y', 0, 60, 150); hold(250);          // 甩出去 → 应发 left
            ramp('Y', 60, -50, 250); hold(200);        // 回弹过冲到 -50
            ramp('Y', -50, 0, 150); hold(700);         // 弹回中位
        }, "left", 1);
        out.append(String.format("      （本步实际命中 %d 次：>1 说明回弹被当成了反向动作）%n", hits - mark));

        step("4c 不应期内做的第二个动作（快节奏）", () -> {
            ramp('Y', 0, 60, 200); hold(400); ramp('Y', 60, 0, 200); hold(100);
            ramp('Y', 0, -60, 200); hold(400); ramp('Y', -60, 0, 300); hold(600);
        }, "right", 2);

        step("4d 过不应期（隔 1.2s）做第二个动作", () -> {
            ramp('Y', 0, 60, 200); hold(400); ramp('Y', 60, 0, 200); hold(1200);
            ramp('Y', 0, -60, 200); hold(400); ramp('Y', -60, 0, 300); hold(600);
        }, "right", 2);

        step("4e 旧场景：回中后连续两个反向动作", () -> {
            ramp('Y', 0, -60, 200); hold(500); ramp('Y', -60, 0, 300); hold(350);
            ramp('Y', 0, 60, 200); hold(500); ramp('Y', 60, 0, 300); hold(400);
        }, "left", 2);

        out.append("\n===== 组 2：基准 = 前臂竖直（重力沿前臂轴）——已知盲区复现 =====\n");
        reset(new float[]{0f, 1f, 0f});
        out.append("基准建立后 " + snap() + "\n");
        step("5  翻表盘（绕前臂轴，重力看不见）", () -> {
            ramp('Y', 0, 180, 400); hold(600);
        }, "无动作", 0);

        System.out.print(out);
    }
}
