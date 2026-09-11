package me.rerere.rikkahub.data.files

import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * 把 markdown 链接、工具参数里的 `file://` 路径解析为宿主 File。
 *
 * **`file://` 的根就是工作区沙箱根 `/`**——路径一律按 Rootfs 逻辑路径解释，
 * 与 [WorkspaceManager.resolveRootfsPath] 的规则一致（两处改动需同步）：
 * - `/upload/<name>`     → `<filesDir>/upload/<name>`（全局 bind mount，**无需 workspaceId**，没有工作区也能用）
 * - `/skills/...`、`/tool_outputs/...` → 对应的 bind mount 宿主目录
 * - `/workspace/<rel>`   → `<filesDir>/workspaces/<workspaceId>/files/<rel>`（需 workspaceId）
 * - 其它绝对路径（`/tmp/chart.png`、`/root/...`）
 *   → `<filesDir>/workspaces/<workspaceId>/linux/<path>`（需 workspaceId）
 *
 * **不解析真机（宿主）路径**：`file:///data/user/0/<pkg>/files/...`、`file:///sdcard/...`
 * 这类设备上的绝对路径不会被还原成真实文件，一律返回 null，调用方据此报错或原样交给
 * Coil / 系统处理。模型给不出也读不到沙箱外的任何文件。
 *
 * AI 侧引用示例（`file://` 前缀可写可不写，漏写也能解析）：
 * - `![alt](file:///workspace/notes.png)` / `![alt](file:///upload/photo.png)`
 * - `![alt](file:///tmp/chart.png)`
 *
 * 返回 null 的情况（由调用方决定兜底方式）：
 * - 非绝对路径、空串；http/https/data/content 等其它 scheme
 * - 真机（宿主）绝对路径，含应用自己的私有目录 `file:///data/user/0/<pkg>/files/...`
 * - 内核伪文件系统（`/proc`、`/dev`、`/sys`）：只能通过 shell 访问，不是可读文件
 * - 需要工作区但没给 workspaceId
 *
 * 相对路径做百分号解码（%XX，**不解码 `+`**——路径中 `+` 是合法字符）；防路径穿越
 * （canonicalFile 后必须仍在根目录内，同时挡住指向根外的符号链接）。
 */
object WorkspaceFileUrlResolver {

    private const val PREFIX_WORKSPACE = WorkspaceManager.ROOTFS_WORKSPACE_DIR

    /**
     * @param filesDir    应用 files 目录（`context.filesDir`）
     * @param workspaceId 当前会话绑定的工作区 id（= workspace root），工作区内路径解析必需
     * @param href        markdown 链接目标 / 工具参数（可带 `file://` 前缀）
     * @return 解析出的宿主 File；无法按 Rootfs 逻辑路径解析时返回 null
     */
    fun resolveFile(filesDir: File, workspaceId: String?, href: String): File? {
        if (href.isBlank()) return null
        // 去掉可选的 file:// 前缀，得到 Rootfs 绝对逻辑路径
        val path = href.removePrefix("file://")
        if (!path.startsWith("/")) return null
        if (isKernelFs(path)) return null

        // bind mount：宿主目录直接映射（/upload 因此无工作区也可用）
        FileFolders.ROOTFS_BIND_MOUNTS.forEach { (target, folder) ->
            if (path == target || path.startsWith("$target/")) {
                return resolveInside(File(filesDir, folder), path.removePrefix(target).trimStart('/'))
            }
        }

        // 工作区文件区
        val id = workspaceId?.takeIf { it.isNotBlank() }
        if (path == PREFIX_WORKSPACE || path.startsWith("$PREFIX_WORKSPACE/")) {
            val workspaceIdOrNull = id ?: return null
            val filesArea = File(workspaceRootDir(filesDir, workspaceIdOrNull), WorkspaceManager.FILES_DIR)
            return resolveInside(filesArea, path.removePrefix(PREFIX_WORKSPACE).trimStart('/'))
        }

        // 其余绝对路径：真机（宿主）路径不解析（file:// 的根是沙箱根，不是设备根）；
        // 剩下的按 Rootfs 内部路径落到 linux 区
        val workspaceIdOrNull = id ?: return null
        if (isAppPrivatePath(filesDir, path)) return null
        return resolveInside(
            base = File(workspaceRootDir(filesDir, workspaceIdOrNull), WorkspaceManager.LINUX_DIR),
            relative = path.trimStart('/'),
        )
    }

    private fun workspaceRootDir(filesDir: File, workspaceId: String): File =
        File(File(filesDir, WorkspaceManager.WORKSPACES_BASE_DIR), workspaceId)

    private fun isKernelFs(path: String): Boolean =
        WorkspaceManager.KERNEL_FS_MOUNTS.any { path == it || path.startsWith("$it/") }

    /**
     * 真机（宿主）绝对路径：即使它恰好是应用自己的私有目录也不放行——`file://` 的根是工作区
     * 沙箱根，不是设备根。**符号链接两种拼写都认**（`/data/data` ↔ `/data/user/0`）。
     */
    private fun isAppPrivatePath(filesDir: File, path: String): Boolean {
        val base = runCatching { filesDir.canonicalFile.path }.getOrNull() ?: return false
        val canonical = runCatching { File(path).canonicalFile.path }.getOrNull() ?: return false
        return canonical == base || canonical.startsWith(base + File.separator)
    }

    /**
     * 在 [base] 内解析 [relative]（已去掉逻辑前缀；空串表示逻辑根本身）。
     * canonicalFile 后必须仍在 [base] 内，否则视为路径穿越返回 null。
     */
    private fun resolveInside(base: File, relative: String): File? {
        val baseCanonical = base.canonicalFile
        if (relative.isBlank()) return baseCanonical
        val decoded = decodePercent(relative)
        if (decoded.isBlank() || decoded == ".") return baseCanonical
        val target = File(base, decoded).canonicalFile
        return if (target.path == baseCanonical.path || target.path.startsWith(baseCanonical.path + File.separator)) {
            target
        } else {
            null
        }
    }

    /** 百分号解码（%XX → 字符）；不解码 '+'（'+' 在文件路径中是合法字符，不是空格） */
    private fun decodePercent(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    sb.append(value.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
