package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * 「点开一个本地文件」的应用内入口。
 *
 * **为什么必须有它**：把 `file://` 交给系统 `ACTION_VIEW` 会踩 Android 的 StrictMode 策略，
 * 抛 `FileUriExposedException` —— 未捕获就直接崩掉整个会话。2026-09-19 的崩溃栈就是这条：
 * `AndroidUriHandler.openUri` → `Intent.prepareToLeaveProcess` → `FileUriExposedException`。
 * 所以只要链接能解析成宿主 File，就先问这个 opener 能不能接管；不能（或没人提供）才走安全兜底。
 *
 * **必须装在应用根部**（RouteActivity）：`ModalBottomSheet` 这类弹出层是独立的组合子树，
 * 只在 markdown 子树里替换 `LocalUriHandler` 盖不住它们 —— 那次崩溃正是点了弹出层里的链接。
 */
fun interface LocalFileOpener {
    /**
     * @param workspaceId 解析出来的工作区 id；null = 这个文件不在任何工作区里（如 `/upload`）
     * @param sandboxPath Rootfs 逻辑路径（`/workspace/a.md`、`/upload/a.png`），非沙箱文件为空串
     * @return true 表示已接管，调用方不要再走系统
     */
    fun open(workspaceId: String?, sandboxPath: String, file: File): Boolean
}

val LocalLocalFileOpener = staticCompositionLocalOf<LocalFileOpener?> { null }

/**
 * `file://` 链接 / 裸沙箱路径 → `(所在工作区 id 或 null) to 宿主 File`；解析不出来返回 null。
 *
 * 三条路依次试：
 * 1. 调用方给的工作区（markdown 子树知道会话绑的是哪个）；
 * 2. 与工作区无关的 bind mount（`/upload`、`/skills`、`/tool_outputs`）—— `/upload` 因此
 *    没有工作区也能打开；
 * 3. **按文件反查**：`/workspace/<rel>` 遍历 `workspaces/*/files/<rel>` 找它在哪个工作区。
 *    这条是给弹出层准备的 —— 那些组合子树里拿不到会话的 workspaceId，只能拿文件去问。
 */
fun resolveLocalFile(filesDir: File, workspaceId: String?, href: String): Pair<String?, File>? {
    val path = href.removePrefix("file://")
    if (path.isBlank() || !path.startsWith("/")) return null

    workspaceId?.takeIf { it.isNotBlank() }?.let { id ->
        WorkspaceFileUrlResolver.resolveFile(filesDir, id, path)
            ?.takeIf { it.isFile }
            ?.let { return id to it }
    }
    WorkspaceFileUrlResolver.resolveFile(filesDir, null, path)
        ?.takeIf { it.isFile }
        ?.let { return null to it }

    val prefix = WorkspaceManager.ROOTFS_WORKSPACE_DIR // "/workspace"
    if (path != prefix && !path.startsWith("$prefix/")) return null
    val relative = path.removePrefix(prefix).trimStart('/')
    if (relative.isEmpty()) return null

    val workspaces = File(filesDir, WorkspaceManager.WORKSPACES_BASE_DIR)
    val id = workspaces.listFiles()?.firstOrNull { candidate ->
        File(File(candidate, WorkspaceManager.FILES_DIR), relative).isFile
    }?.name ?: return null
    return id to File(File(File(workspaces, id), WorkspaceManager.FILES_DIR), relative)
}
