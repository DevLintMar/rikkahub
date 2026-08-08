package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 工作区独立备份/还原：单个工作区导出为 zip（含 rootfs）、导入为新建工作区。
 *
 * zip 条目全相对路径（无包名绝对路径）→ 跨包名（debug/release/pre）天然兼容。
 * 结构：
 * ```
 * workspace.json   # 元信息：name / toolApprovals / createdAt / updatedAt / shellStatus
 * files/...        # FILES 区用户沙盒文件
 * linux/...        # rootfs 沙盒环境（可重下，但用户选择完整备份）
 * symlinks/...     # 符号链接记录：内容 = link target 文本（rootfs 全是 busybox applet 软链）
 * ```
 * 排除 `tmp/`（临时文件）与 `.l2s.*` 隐藏文件（WorkspaceFileSystem 惯例）。
 *
 * 符号链接不能按内容复制：rootfs 软链 target 多为绝对路径（`/bin/busybox`），
 * 直接读会把 target 解析到设备根导致 FileNotFound，且会丢失链接语义。
 * 导出把软链记为 `symlinks/<path>`（target 文本）；导入先解普通文件再重建软链。
 */
object WorkspaceBackup {
    const val META_ENTRY = "workspace.json"
    const val TEMP_DIR_NAME = "tmp"
    const val SYMLINKS_PREFIX = "symlinks/"

    @Serializable
    data class Meta(
        val name: String,
        val toolApprovals: String,
        val createdAt: Long,
        val updatedAt: Long,
        val shellStatus: String,
    )

    /**
     * 导出工作区到 [target] zip。
     * 递归打包 workspaceDir 下所有内容（files/ + linux/），排除 tmp/ 与 .l2s.*；
     * 软链接以 `symlinks/<path>` 条目记录 target；全流式写（rootfs 大文件逐条 ZipEntry），不整包读内存。
     */
    fun export(
        manager: WorkspaceManager,
        entity: WorkspaceEntity,
        target: File,
    ) {
        ZipOutputStream(FileOutputStream(target)).use { zipOut ->
            // 元信息首个条目：导入先读它拿 name 建 workspace，再解压其余
            val metaBytes = JsonInstant.encodeToString(
                Meta(
                    name = entity.name,
                    toolApprovals = entity.toolApprovals,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    shellStatus = entity.shellStatus,
                )
            ).toByteArray()
            zipOut.putNextEntry(ZipEntry(META_ENTRY))
            zipOut.write(metaBytes)
            zipOut.closeEntry()

            addDirectoryToZip(zipOut, manager.workspaceDir(entity.root), "")
        }
    }

    /** 从 zip 读取工作区元信息（首个条目 workspace.json） */
    fun parseMeta(zip: ZipFile): Meta {
        val entry = zip.getEntry(META_ENTRY)
            ?: error("备份文件缺少 $META_ENTRY")
        val content = zip.getInputStream(entry).use { it.readBytes() }.decodeToString()
        return JsonInstant.decodeFromString<Meta>(content)
    }

    /**
     * 解压 zip 中 files/ 与 linux/ 条目到 [rootDir]（已创建的新工作区目录）。
     * 先写普通文件，再重建 `symlinks/` 记录的符号链接（确保 target 文件已就位）；
     * 跳过目录条目、元信息条目与 tmp/；防路径穿越（目标必须在 rootDir 内）。
     */
    fun extractTo(zip: ZipFile, rootDir: File) {
        val rootCanonical = rootDir.canonicalFile
        val symlinkTargets = mutableMapOf<String, String>()

        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory || entry.name == META_ENTRY) return@forEach
            if (entry.name == TEMP_DIR_NAME || entry.name.startsWith("$TEMP_DIR_NAME/")) return@forEach

            if (entry.name.startsWith(SYMLINKS_PREFIX)) {
                // 符号链接记录：内容 = target 文本，稍后统一重建
                val linkPath = entry.name.removePrefix(SYMLINKS_PREFIX)
                val target = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                symlinkTargets[linkPath] = target
                return@forEach
            }

            val target = File(rootDir, entry.name).canonicalFile
            require(
                target.path == rootCanonical.path ||
                    target.path.startsWith(rootCanonical.path + File.separator)
            ) { "非法 zip 路径: ${entry.name}" }
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // 重建符号链接（target 保持原样：相对链接相对创建，绝对链接在 rootfs 命名空间内解析）
        for ((linkPath, linkTarget) in symlinkTargets) {
            val link = File(rootDir, linkPath).canonicalFile
            require(
                link.path == rootCanonical.path ||
                    link.path.startsWith(rootCanonical.path + File.separator)
            ) { "非法符号链接路径: $linkPath" }
            link.parentFile?.mkdirs()
            link.delete() // 若同路径已有占位（如空目录条目）先移除
            Files.createSymbolicLink(link.toPath(), Paths.get(linkTarget))
        }
    }

    private fun addDirectoryToZip(zipOut: ZipOutputStream, dir: File, prefix: String) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (file in children) {
            if (file.name == TEMP_DIR_NAME || file.name.startsWith(".l2s.")) continue
            val entryName = "$prefix${file.name}"
            if (Files.isSymbolicLink(file.toPath())) {
                // 软链接：记录 target 文本，不读内容（绝对 target 会解析到设备根）
                val target = Files.readSymbolicLink(file.toPath()).toString()
                zipOut.putNextEntry(ZipEntry("$SYMLINKS_PREFIX$entryName"))
                zipOut.write(target.toByteArray())
                zipOut.closeEntry()
                continue
            }
            if (file.isDirectory) {
                zipOut.putNextEntry(ZipEntry("$entryName/"))
                zipOut.closeEntry()
                addDirectoryToZip(zipOut, file, "$entryName/")
            } else {
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
    }
}
