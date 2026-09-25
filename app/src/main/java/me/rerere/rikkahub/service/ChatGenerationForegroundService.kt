package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.ProcessInfo
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFgs"

/**
 * 通知文案：只要有一个聊天生成在跑就显示「正在生成」，否则显示「后台任务运行中」。
 * 抽成顶层函数是为了能在 JVM 单测里钉住这条判定。
 */
internal fun foregroundNotificationLabelRes(activeBackgroundFlags: Collection<Boolean>): Int =
    if (activeBackgroundFlags.isNotEmpty() && activeBackgroundFlags.all { it }) {
        R.string.notification_sub_agent_running
    } else {
        R.string.notification_live_update_title
    }

/**
 * Keeps the app process in the foreground while one or more chat generations are active.
 *
 * Generation itself remains owned by [ChatService]. This service only provides the Android
 * foreground-service lifetime required for streaming to continue after the activity is hidden.
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_BACKGROUND_TASK = "background_task"

        const val NOTIFICATION_ID = 2002

        fun acquire(
            context: Context,
            generationId: Uuid,
            conversationId: Uuid,
            backgroundTask: Boolean = false,
        ): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                putExtra(EXTRA_BACKGROUND_TASK, backgroundTask)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
                Logging.log(TAG, "acquire 失败（startForegroundService 抛异常）backgroundTask=$backgroundTask ${ProcessInfo.describe()}: $it")
            }.getOrDefault(false)
        }

        fun release(context: Context, generationId: Uuid) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release chat generation foreground service", it)
            }
        }
    }

    private data class ActiveGeneration(val conversationId: String, val backgroundTask: Boolean)

    private val activeGenerations = linkedMapOf<String, ActiveGeneration>()
    private var isForeground = false
    private val appScope: AppScope by inject()
    private val chatService: ChatService by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeGenerations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it.conversationId) }.getOrNull() }
            .distinct()
            .forEach { conversationId ->
                appScope.launch {
                    chatService.stopGeneration(conversationId)
                }
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopService()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopService()
        val backgroundTask = intent.getBooleanExtra(EXTRA_BACKGROUND_TASK, false)
        activeGenerations[generationId] = ActiveGeneration(conversationId, backgroundTask)
        updateForegroundNotification()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            stopService()
        } else {
            updateForegroundNotification()
        }
    }

    private fun updateForegroundNotification() {
        val conversationId = activeGenerations.values.lastOrNull()?.conversationId
        val labelRes = foregroundNotificationLabelRes(activeGenerations.values.map { it.backgroundTask })
        try {
            val notification = buildNotification(conversationId, labelRes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            // 诊断：前台服务真的进前台了没有——「切屏后子代理还能不能跑完」全靠这一条。
            // 应用内「日志」页里能看到它；若整段测试里没有这一行，说明保护从来没生效过。
            Logging.log(TAG, "进前台成功 labelRes=$labelRes holders=${activeGenerations.size} ${ProcessInfo.describe()}")
        } catch (e: Exception) {
            // 进前台失败时把**所有**持有者一起清掉并停服务，是有意的：Android 要求
            // startForegroundService 之后必须在几秒内真的 startForeground，否则系统直接
            // 抛 RemoteServiceException 杀掉进程——服务已不可能合法存活。
            // 代价是持有者对此毫不知情（聊天生成、以及 Task 7 起的子代理网络流），它们会继续跑
            // 但失去前台保护；它们无法自救，所以这里只记日志、不做通知。
            Log.e(TAG, "Failed to enter foreground", e)
            // 这条失败是**静默**的：所有持有者（聊天生成 + 后台子代理）都会失去前台保护，
            // 系统随后可以随时回收本进程。写进应用内日志页，让它不再静默。
            Logging.log(TAG, "进前台失败，已清空全部持有者（进程失去前台保护）${ProcessInfo.describe()}: $e")
            activeGenerations.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        Logging.log(TAG, "停服务 holders=${activeGenerations.size} ${ProcessInfo.describe()}")
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(conversationId: String?, labelRes: Int) =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(labelRes))
            .setContentIntent(conversationId?.let { getConversationPendingIntent(it) })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
