package me.rerere.rikkahub.data.sync

import java.io.File

/**
 * 跨包名恢复的路径重定位工具。
 *
 * 备份内容里持久化的附件/头像/背景 URI 是绝对路径（如
 * `file:///data/user/0/xyz.lynsei.rikkahub/files/upload/<uuid>`），恢复进不同包名（debug/.pre/旧包名）的
 * distribution 时会指向不存在的目录。本工具把所有 `file:///data/(user/N|data)/<任意包名>/files/` 前缀
 * 重定位为当前包的 `context.filesDir`，对同包名恢复幂等无害。
 */
object RestorePathRebaser {
    /**
     * DB 恢复时写入 noBackupFilesDir 的哨兵文件名；下次启动据此执行一次性 URI 重写。
     * noBackupFilesDir 不入 Android Auto Backup，也不在当前备份 zip 覆盖范围内。
     */
    const val MARKER_FILE = "restore_path_rebase_pending"

    /** 标记文件路径（noBackupFilesDir 由系统确保存在且非空）。 */
    fun markerFile(context: android.content.Context): File =
        File(context.noBackupFilesDir, MARKER_FILE)

    // 匹配 file:///data/user/N/<pkg>/files/ 与 file:///data/data/<pkg>/files/
    // 只匹配"包名段后紧跟 /files/"，不会误伤 /android_asset 等其他 file:// 形态
    private val FILE_URI_REGEX = Regex("file:///data/(?:user/\\d+|data)/[^/]+/files/")

    /** 当前包的标准前缀，如 file:///data/user/0/<当前包>/files/ */
    private fun currentPrefix(currentFilesDir: File): String =
        "file://${currentFilesDir.absolutePath}/"

    /**
     * 把 [text] 中所有匹配的绝对 file:// 前缀重定位为当前包前缀。
     * 同包名时替换结果与原文一致（幂等）。
     */
    fun rebase(text: String, currentFilesDir: File): String =
        text.replace(FILE_URI_REGEX, currentPrefix(currentFilesDir))

    /**
     * 统计 [text] 中外来（≠当前包）文件前缀的个数，供日志/判断是否需要重写。
     */
    fun foreignPrefixCount(text: String, currentFilesDir: File): Int {
        val current = currentPrefix(currentFilesDir)
        return FILE_URI_REGEX.findAll(text).count { it.value != current }
    }
}
