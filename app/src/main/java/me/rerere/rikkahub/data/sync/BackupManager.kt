package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.common.android.Logging
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val TAG = "BackupManager"

/** AI 生成图片目录：GenMediaEntity.path 存相对 "images/xxx"，故不在 FileFolders 常量表内。 */
private const val IMAGES_FOLDER = "images"

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            // 备份诊断：确认备份包是否含附件/头像文件
            val imageAvatarCount = settings.assistants.count { it.avatar is Avatar.Image }
            var uploadFileCount = 0
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in listOf(
                        FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, IMAGES_FOLDER
                    )) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder == FileFolders.SKILLS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            if (folder == FileFolders.UPLOAD || folder == IMAGES_FOLDER) uploadFileCount++
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            Logging.log(
                TAG,
                "createBackup: db=$includeDatabase files=$includeFiles uploadFiles=$uploadFileCount " +
                    "imageAssistantAvatars=$imageAvatarCount zipBytes=${archive.length()}"
            )
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    var stagedUploadFiles = 0
                    var stagedImages = 0
                    var rebasedSettingsRefs = 0
                    var imageAvatarCount = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    if (entry.name.startsWith("${FileFolders.UPLOAD}/")) stagedUploadFiles++
                                    if (entry.name.startsWith("$IMAGES_FOLDER/")) stagedImages++
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migratedJson = SettingsJsonMigrator.migrate(settingsFile.readText())
                        // 跨包名恢复：把 settings.json 里绝对 file:// 路径重定位到当前包（同包名时幂等无害）
                        rebasedSettingsRefs = RestorePathRebaser.foreignPrefixCount(migratedJson, context.filesDir)
                        val rebasedJson = if (rebasedSettingsRefs > 0) {
                            RestorePathRebaser.rebase(migratedJson, context.filesDir)
                        } else {
                            migratedJson
                        }
                        if (rebasedSettingsRefs > 0) {
                            Log.i(
                                TAG,
                                "stageRestore: rebased $rebasedSettingsRefs foreign file:// refs in settings.json"
                            )
                        }
                        val settings = json.decodeFromString<Settings>(rebasedJson)
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        imageAvatarCount = settings.assistants.count { it.avatar is Avatar.Image }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    val databaseRestored = stagedDatabase.exists()
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                    if (databaseRestored) {
                        // 跨包名恢复：DB 已替换，标记下次启动重写 message_node/GenMediaEntity 内绝对 file:// URI
                        runCatching {
                            RestorePathRebaser.markerFile(context).writeText("1")
                            Log.i(TAG, "stageRestore: wrote path-rebase marker for next launch")
                        }.onFailure {
                            Log.e(TAG, "stageRestore: failed to write path-rebase marker", it)
                        }
                    }
                    writeRestoreDiagnostics(
                        archive = archive,
                        entries = restoredEntries,
                        uploadFiles = stagedUploadFiles,
                        images = stagedImages,
                        rebaseCount = rebasedSettingsRefs,
                        imageAvatarCount = imageAvatarCount,
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    /**
     * 恢复诊断：写入 noBackupFilesDir/restore_diag.txt，启动时由 RikkaHubApp 回放进日志页。
     * （恢复流程会在重启前落盘，进程内 Logging 会丢失，故必须写文件）
     */
    private fun writeRestoreDiagnostics(
        archive: File,
        entries: Int,
        uploadFiles: Int,
        images: Int,
        rebaseCount: Int,
        imageAvatarCount: Int,
    ) {
        runCatching {
            File(context.noBackupFilesDir, "restore_diag.txt").writeText(
                buildString {
                    appendLine("restore: ${archive.name} entries=$entries")
                    appendLine("settings: rebaseCount=$rebaseCount imageAssistantAvatars=$imageAvatarCount")
                    appendLine("restore: stagedUploadFiles=$uploadFiles stagedImages=$images")
                }
            )
            Log.i(TAG, "stageRestore: wrote restore diagnostics")
        }.onFailure {
            Log.e(TAG, "stageRestore: failed to write restore diagnostics", it)
        }
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, IMAGES_FOLDER) ||
            '/' !in name
        ) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder == FileFolders.SKILLS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
