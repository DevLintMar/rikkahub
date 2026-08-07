package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.ui.components.richtext.registerSerifCjkFallback
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.RestorePathRebaser
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.common.android.Logging
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // Register system serif font as CJK fallback for RaTeX
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching { registerSerifCjkFallback() }
        }

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // 跨包名恢复：DB 恢复后一次性重写绝对 file:// URI 到当前包
        rewriteRestoredFileUris()

        // 跨包名恢复：把恢复时落盘的 restore_diag.txt 回放进日志页（进程重启后可见）
        replayRestoreDiagnostics()

        // 头像/背景文件存在性诊断：每次启动记录，便于定位恢复后头像丢失的环节
        logAvatarDiagnostics()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    /**
     * 跨包名恢复的一次性 URI 重写：恢复进不同包名（debug/.pre/旧包名）的 distribution 后，DB 内
     * message_node.messages 与 GenMediaEntity.source_paths 存的是绝对 file:///data/user/0/<旧包>/files/... 路径。
     * 恢复流程在重启前写哨兵文件，本方法在下次启动时把它们重定位到当前包，然后删除哨兵。
     */
    private fun rewriteRestoredFileUris() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val marker = RestorePathRebaser.markerFile(this@RikkaHubApp)
                if (!marker.exists()) {
                    return@runCatching
                }
                val db = get<AppDatabase>().openHelper.writableDatabase
                val filesDir = this@RikkaHubApp.filesDir

                var updatedNodes = 0
                var updatedMedia = 0
                var totalRefs = 0

                db.query("SELECT id, messages FROM message_node").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val messages = cursor.getString(1)
                        val foreign = RestorePathRebaser.foreignPrefixCount(messages, filesDir)
                        if (foreign > 0) {
                            db.execSQL(
                                "UPDATE message_node SET messages = ? WHERE id = ?",
                                arrayOf<Any>(RestorePathRebaser.rebase(messages, filesDir), id)
                            )
                            updatedNodes++
                            totalRefs += foreign
                        }
                    }
                }

                db.query("SELECT id, source_paths FROM GenMediaEntity WHERE source_paths IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val sourcePaths = cursor.getString(1)
                        val foreign = RestorePathRebaser.foreignPrefixCount(sourcePaths, filesDir)
                        if (foreign > 0) {
                            db.execSQL(
                                "UPDATE GenMediaEntity SET source_paths = ? WHERE id = ?",
                                arrayOf<Any>(RestorePathRebaser.rebase(sourcePaths, filesDir), id)
                            )
                            updatedMedia++
                            totalRefs += foreign
                        }
                    }
                }

                marker.delete()
                Log.i(
                    TAG,
                    "rewriteRestoredFileUris: rebased $totalRefs foreign file:// refs " +
                        "across $updatedNodes message nodes and $updatedMedia gen_media rows"
                )
            }.onFailure {
                Log.e(TAG, "rewriteRestoredFileUris failed", it)
            }
        }
    }

    /**
     * 恢复诊断回放：恢复流程把 restore_diag.txt 写入 noBackupFilesDir（恢复会 exitProcess
     * 重启，进程内 Logging 会丢失），本方法在下次启动时读回并逐行写进日志页，随后删除。
     */
    private fun replayRestoreDiagnostics() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val diagFile = File(noBackupFilesDir, "restore_diag.txt")
                if (!diagFile.exists()) {
                    return@runCatching
                }
                val lines = diagFile.readText().lineSequence().filter { it.isNotBlank() }.toList()
                lines.forEach { Logging.log(TAG, it) }
                diagFile.delete()
                Log.i(TAG, "replayRestoreDiagnostics: replayed ${lines.size} restore diagnostic lines")
            }.onFailure {
                Log.e(TAG, "replayRestoreDiagnostics failed", it)
            }
        }
    }

    /**
     * 头像/背景文件存在性诊断：每次启动记录到日志页，用于排查"恢复后没有头像"的环节。
     * 对每个 Avatar.Image / 背景 URL 输出：scheme、是否已 rebase 到当前包、文件是否存在。
     */
    private fun logAvatarDiagnostics() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                val currentPrefix = "file://${filesDir.absolutePath}/"
                fun check(label: String, url: String) {
                    val uri = runCatching { url.toUri() }.getOrNull()
                    val scheme = uri?.scheme ?: "?"
                    val fileExists = if (uri?.scheme == "file") {
                        runCatching { uri.toFile().exists() }.getOrDefault(false)
                    } else {
                        false
                    }
                    val rebased = url.startsWith(currentPrefix)
                    Logging.log(
                        TAG,
                        "avatar: $label scheme=$scheme rebased=$rebased fileExists=$fileExists url=$url"
                    )
                }
                val userAvatar = settings.displaySetting.userAvatar
                if (userAvatar is Avatar.Image) {
                    check("userAvatar", userAvatar.url)
                } else {
                    Logging.log(TAG, "avatar: userAvatar type=${userAvatar::class.simpleName}")
                }
                settings.assistants.forEach { assistant ->
                    val avatar = assistant.avatar
                    if (avatar is Avatar.Image) {
                        check("assistant[${assistant.name}]", avatar.url)
                    }
                    assistant.background?.let { check("assistant[${assistant.name}].background", it) }
                }
            }.onFailure {
                Log.e(TAG, "logAvatarDiagnostics failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
