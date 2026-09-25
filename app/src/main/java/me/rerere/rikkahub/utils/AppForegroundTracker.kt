package me.rerere.rikkahub.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程当前有没有前台 Activity。
 *
 * 用途只有一个：决定「启动前台服务」用哪个 API。
 *
 * - **应用在前台 → 用 `startService`。** 它不受「5 秒内必须调用 `startForeground`」这条硬性契约
 *   约束，所以即便紧接着的 `startForeground` 被系统拒绝（例如这一瞬间应用刚好退到后台），后果只是
 *   「没进前台」——调用方按既有设计继续跑但不受保护，**而不是进程被系统杀掉**。
 * - 应用在后台 → 只能用 `ContextCompat.startForegroundService`（也常被系统的后台启动限制直接拒绝，
 *   抛异常；调用方捕获后照常继续，同样只是不受保护）。
 *
 * 真机崩溃现场（`RemoteServiceException`）：
 *
 * ```
 * Context.startForegroundService() did not then call Service.startForeground():
 * ServiceRecord{… ChatGenerationForegroundService}
 * ```
 *
 * 走的就是前者那条路：`startForegroundService` 调用成功，但几毫秒后 `startForeground` 抛了异常
 * （我们的 catch 里 `stopSelf()`），于是那条契约永远没被满足 ⇒ 5 秒后系统在主线程抛异常、进程被
 * 干掉。换成 `startService` 后这条路径不存在。
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivities = AtomicInteger(0)

    val isForeground: Boolean get() = startedActivities.get() > 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
