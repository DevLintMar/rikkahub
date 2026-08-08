package me.rerere.rikkahub.ui.components.richtext

import java.io.File

/**
 * 把 markdown 链接中的工作区逻辑路径解析为宿主 File。
 *
 * AI 侧的工作区路径是 Rootfs 逻辑路径：
 * - `/workspace/<rel>` → 工作区 FILES 区（`<filesDir>/workspaces/<workspaceId>/files/<rel>`，需 workspaceId）
 * - `/upload/<name>`   → 用户上传文件区（`<filesDir>/upload/<name>`，全局 bind mount，无需 workspaceId）
 *
 * markdown 里用 `file://` 引用（也容忍不带前缀的裸 `/workspace/...` / `/upload/...`，AI 漏写时不空白）：
 * - `![alt](file:///workspace/notes.png)` / `![alt](file:///upload/photo.png)`
 * - `[text](file:///workspace/notes.md)`
 *
 * 其余 scheme（http/https/data/content/宿主绝对路径）返回 null，保持原样交给 Coil / 系统。
 * 相对路径做百分号解码（%XX，不解码 '+'——路径中 '+' 是合法字符）；防路径穿越（canonicalFile 必须在根内）。
 */
object WorkspaceFileUrlResolver {

    private const val PREFIX_WORKSPACE = "/workspace"
    private const val PREFIX_UPLOAD = "/upload"

    /**
     * @param filesDir    应用 files 目录（`context.filesDir`）
     * @param workspaceId 当前会话绑定的工作区 id（= workspace root），`/workspace` 解析必需
     * @param href        markdown 链接目标（LINK_DESTINATION 原文）
     * @return 解析出的宿主 File；非工作区链接、缺 workspaceId 或路径穿越时返回 null
     */
    fun resolveFile(filesDir: File, workspaceId: String?, href: String): File? {
        if (href.isBlank()) return null
        // 去掉可选的 file:// 前缀，得到 Rootfs 绝对逻辑路径
        val path = href.removePrefix("file://")
        val (base, relative) = when {
            path == PREFIX_WORKSPACE || path.startsWith("$PREFIX_WORKSPACE/") -> {
                val id = workspaceId?.takeIf { it.isNotBlank() } ?: return null
                File(filesDir, "workspaces").resolve(id).resolve("files") to
                    path.removePrefix(PREFIX_WORKSPACE).trimStart('/')
            }
            path == PREFIX_UPLOAD || path.startsWith("$PREFIX_UPLOAD/") ->
                File(filesDir, "upload") to path.removePrefix(PREFIX_UPLOAD).trimStart('/')
            else -> return null
        }
        if (relative.isBlank()) return base
        val decoded = decodePercent(relative)
        if (decoded.isBlank() || decoded == ".") return null
        val baseCanonical = base.canonicalFile
        val target = File(base, decoded).canonicalFile
        // 防路径穿越：target 必须等于根或在根内
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
