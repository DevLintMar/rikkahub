package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.AppForegroundTracker

/**
 * 前台保活服务，用于提升 App 后台存活能力。
 *
 * - START_STICKY：被系统杀死后自动重启
 * - stopWithTask="false"：用户从最近任务列表划掉 App 时服务不被停止
 * - 低优先级常驻通知，低调不打扰用户
 * - 国产 ROM 兼容：划掉任务后通过发送广播尝试自启动
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 30001
        // 与 manifest 的 ${applicationId}.RESTART_KEEP_ALIVE 一致（release/debug/pre 各不同）
        val ACTION_RESTART_KEEP_ALIVE = "${BuildConfig.APPLICATION_ID}.RESTART_KEEP_ALIVE"

        fun isRunning(context: Context): Boolean {
            return try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                manager.getRunningServices(Integer.MAX_VALUE)
                    .any { it.service.className == KeepAliveService::class.java.name }
            } catch (e: Exception) {
                Log.w(TAG, "isRunning 检查失败，默认返回 false", e)
                false
            }
        }

        fun start(context: Context) {
            if (isRunning(context)) {
                Log.d(TAG, "服务已在运行，跳过启动")
                return
            }
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (AppForegroundTracker.isForeground) {
                    // 前台用 startService：绕开「5 秒内必须 startForeground」的硬性契约
                    // （理由与真机崩溃现场见 AppForegroundTracker）。
                    context.startService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "保活服务启动请求已发送")
            } catch (e: Exception) {
                Log.e(TAG, "启动保活服务失败", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
            Log.d(TAG, "保活服务停止请求已发送")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_content))
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setShowWhen(false)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须使用 specialUse，彻底消除 dataSync 的 6 小时强制超时强杀 (ForegroundServiceDidNotStopInTimeException)
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // 类似 Termux 的 CPU 唤醒锁机制：持有 PARTIAL_WAKE_LOCK 防止息屏后系统进入深度休眠 (Doze) 冻结进程
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                    Log.d(TAG, "Termux 式 WakeLock 已获取，保持 CPU 在息屏期间持续运转")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败，停止保活服务", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock 已释放")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 WakeLock 时发生异常", e)
        } finally {
            wakeLock = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved")
        try {
            sendBroadcast(Intent(ACTION_RESTART_KEEP_ALIVE).apply {
                setPackage(packageName)
            })
        } catch (e: Exception) {
            Log.e(TAG, "发送保活重启广播失败", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.keep_alive_channel_desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
