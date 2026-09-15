package io.wristdeck.gesture;

import android.hardware.SensorEvent;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

/**
 * significant_motion 这类 one-shot 传感器只能用 TriggerEventListener 接，不能用 registerListener。
 *
 * 这里用 Java 写、并同时声明两套签名，原因是编译期与运行期的契约不一样：
 *  - compileSdk 36 里 TriggerEventListener 的抽象方法是 onTrigger(TriggerEvent)（API 34 起的新签名）；
 *  - 但设备是 Android 11（API 30），框架回调的是老签名 onTrigger(SensorEvent)。
 * 只实现新签名 → 编译过、装到表上遇到老框架调用直接 AbstractMethodError；
 * 只实现老签名 → Kotlin 里根本编译不过（"overrides nothing"）。
 * 两个都声明，最终都汇聚到 onFired()，两个 Android 版本都能跑。
 */
public abstract class SigTrigger extends TriggerEventListener {

    @Override
    public void onTrigger(TriggerEvent event) {
        onFired();
    }

    /** Android 11 及以前框架实际调用的签名。不能加 @Override —— SDK 36 里已没有这个成员。 */
    @SuppressWarnings("unused")
    public void onTrigger(SensorEvent event) {
        onFired();
    }

    protected abstract void onFired();
}
