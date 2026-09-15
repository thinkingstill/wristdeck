import io.wristdeck.gesture.WristGestureDetector;

/**
 * 临时诊断台：把识别器内部量（θ / φ / 两个切空间系数）逐帧打出来，
 * 用来确认"判轴符号约定"和 A8 为什么没发。不进回归，只是排查工具。
 */
public class Dump {

    static final long DT = 20;
    static final float G = 9.81f;

    static final double A0 = -158.30, B0 = -140.34;
    static final double UZSIGN = -1.0;

    static WristGestureDetector d;
    static long t;
    static float[] u;

    static float[] poseByAngles(double dArm, double dBend) {
        double ta = Math.tan(Math.toRadians(A0 + dArm));
        double tb = Math.tan(Math.toRadians(B0 + dBend));
        double uz = UZSIGN / Math.sqrt(1 + ta * ta + tb * tb);
        return new float[]{(float) (uz * ta), (float) (uz * tb), (float) uz};
    }

    static float[] rotY(float[] v, double deg) {
        double a = Math.toRadians(deg);
        return new float[]{(float) (v[0] * Math.cos(a) + v[2] * Math.sin(a)), v[1],
                (float) (-v[0] * Math.sin(a) + v[2] * Math.cos(a))};
    }

    static void feed() {
        d.onAccel(u[0] * G, u[1] * G, u[2] * G, t);
        t += DT;
    }

    static void hold(int ms) {
        for (int i = 0; i < ms / DT; i++) feed();
    }

    static void rampY(float[] base, double from, double to, int ms) {
        int n = (int) Math.max(1L, ms / DT);
        for (int i = 1; i <= n; i++) {
            u = rotY(base, from + (to - from) * i / n);
            feed();
        }
    }

    static void rampAngles(double aFrom, double aTo, double bFrom, double bTo, int ms) {
        int n = (int) Math.max(1L, ms / DT);
        for (int i = 1; i <= n; i++) {
            u = poseByAngles(aFrom + (aTo - aFrom) * i / n, bFrom + (bTo - bFrom) * i / n);
            feed();
        }
    }

    static void line(String tag) {
        WristGestureDetector.Snapshot s = d.snapshot(t);
        System.out.printf("%-14s t=%-6d θ=%7.2f φarm=%8.2f φbend=%8.2f  coefArm=%8.4f coefBend=%8.4f st=%s%n",
                tag, t, s.getThetaDeg(), s.getPhiArm(), s.getPhiBend(),
                s.getCoefArm(), s.getCoefBend(), s.getState());
    }

    static void head(String title) {
        System.out.println("\n===== " + title + " =====");
    }

    public static void main(String[] args) {
        /* ---- 1. 平地基准：绕设备 X 轴（屈伸）正负各转 60°，看符号约定 ---- */
        head("基准 u0=(0,0,1)：绕设备 X 轴转 ±60°（谁该是 up / down？）");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        float[] flat = {0f, 0f, 1f};
        u = flat;
        hold(1500);
        line("起点");
        rampY(flat, 0, 60, 200);
        hold(400);
        line("绕X +60");
        rampY(flat, 60, 0, 300);
        hold(600);

        float[] flat2 = {0f, 0f, 1f};
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        u = flat2;
        hold(1500);
        rampY(flat2, 0, -60, 200);
        hold(400);
        line("绕X -60");

        /* ---- 2. 极角模型基准：屈伸 −118°（现场深压腕），看系数符号 ---- */
        head("基准 u0=(-0.293,-0.610,-0.736)：屈伸 −118°（现场口径的 down）");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
        line("起点");
        rampAngles(0, 0, 0, -118.06, 250);
        hold(600);
        line("屈伸 -118");

        /* ---- 3. 极角模型基准：前臂 ±60° ---- */
        head("同一基准：前臂 +60°（左）与 -60°（右）");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
        rampAngles(0, 60, 0, 0, 250);
        hold(600);
        line("前臂 +60");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
        rampAngles(0, -60, 0, 0, 250);
        hold(600);
        line("前臂 -60");

        /* ---- 4. A8 复刻：为什么没发？逐帧看 θ 有没有过 45 ---- */
        head("A8 复刻：极角模型下前臂 0→-22.8→-39.5→-45.7（现场发 right）");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        u = poseByAngles(0, 0);
        hold(1500);
        rampAngles(0, -22.8, 0, 0, 280);
        line("段1末");
        rampAngles(-22.8, -39.5, 0, 0, 202);
        line("段2末");
        rampAngles(-39.5, -45.7, 0, 0, 610);
        line("段3末");
        hold(600);
        line("hold 后");
        System.out.println("命中次数 = " + d.snapshot(t).getEventCount());

        /* ---- 5. 真机故障基准：纯旋前 74°（A10）看系数 ---- */
        head("真机故障基准 u0=(0.295,0.108,0.949)：纯旋前 74°（必须是 left）");
        d = new WristGestureDetector();
        d.setBypassArm(true);
        t = 0;
        float[] fx = {0.295f, 0.108f, 0.949f};
        u = fx;
        hold(1500);
        line("起点");
        rampY(fx, 0, 74, 250);
        hold(900);
        line("旋前 74");
        System.out.println("命中次数 = " + d.snapshot(t).getEventCount());
    }
}
