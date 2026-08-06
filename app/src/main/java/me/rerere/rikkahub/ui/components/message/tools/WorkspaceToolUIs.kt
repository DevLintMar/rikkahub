package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工作空间编辑文件: 摘要显示增删统计与精简 diff, 详情为完整 diff view
 */
object EditFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_edit"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_edit_file, path) else stringResource(R.string.tool_ui_edit_file_default)
    }

    /**
     * 执行后读取输出部件 metadata 中的全文件 diff;
     * 未执行 (如等待审批) 时基于入参的 old_text/new_text 片段生成预览 diff
     */
    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            return context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
        }
        val path = context.arguments.getStringContent("path") ?: return null
        val oldText = context.arguments.getStringContent("old_text") ?: return null
        val newText = context.arguments.getStringContent("new_text") ?: return null
        return generateUnifiedDiff(oldText, newText, path)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") != null || diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("message")?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean = diffOf(context) != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.arguments.getStringContent("path") ?: toolName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 工作空间读取文件: 摘要显示内容首部预览, 详情为带语法高亮的完整内容
 */
object ReadFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_read"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_read_file, path) else stringResource(R.string.tool_ui_read_file_default)
    }

    /** 已执行时从输出 JSON 读取文件内容 */
    private fun textOf(context: ToolUIContext): String? =
        context.content.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") != null || textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("message")?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/**
 * 工作空间写入文件: 内容取自入参 (未执行也可预览), 摘要为内容首部, 详情为完整内容
 */
object WriteFileToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_write"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_write_file, path) else stringResource(R.string.tool_ui_write_file_default)
    }

    private fun textOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") != null || textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("message")?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

private const val FILE_SUMMARY_MAX_LINES = 10

/** 内联摘要: 按扩展名语法高亮展示文件内容首部若干行 */
@Composable
private fun FileContentSummary(text: String, path: String?, loading: Boolean) {
    val preview = remember(text) {
        text.lineSequence().take(FILE_SUMMARY_MAX_LINES).joinToString("\n")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        HighlightText(
            code = preview,
            language = languageOf(path),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = FILE_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BottomSheet 详情: 文件路径 + 按扩展名语法高亮的完整内容 */
@Composable
private fun FileContentPreview(path: String?, code: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = path ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        HighlightCodeBlock(
            code = code,
            language = languageOf(path),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 工作空间执行 Shell: 摘要显示退出状态与输出首部, 详情为命令 + stdout/stderr
 */
object ShellToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = "workspace_shell"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command") ?: return stringResource(R.string.tool_ui_shell_default)
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_shell, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        // 异常信封(无 exitCode): 直接显示错误消息, 不再走 ShellExitStatus 的 "exit code ?" 分支
        if (content.getStringContent("error") != null) {
            Text(
                text = content.getStringContent("message")
                    ?: stringResource(R.string.chat_message_tool_failed, toolName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }
        val combined = remember(content) {
            listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShellExitStatus(content, MaterialTheme.typography.labelSmall)
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val command = context.arguments.getStringContent("command").orEmpty()
        val cwd = context.arguments.getStringContent("cwd")
        val stdout = content.getStringContent("stdout").orEmpty()
        val stderr = content.getStringContent("stderr").orEmpty()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_shell_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ShellExitStatus(content, MaterialTheme.typography.labelMedium)
            }
            HighlightCodeBlock(
                code = if (cwd.isNullOrBlank()) command else "# cwd: $cwd\n$command",
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (stdout.isNotEmpty()) {
                Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                HighlightCodeBlock(
                    code = stdout,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stderr.isNotEmpty()) {
                Text(
                    text = "stderr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HighlightCodeBlock(
                    code = stderr,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Shell 退出状态文本: exit code 为 0 显示绿色, 超时或非零显示错误色 */
@Composable
private fun ShellExitStatus(content: JsonElement, style: androidx.compose.ui.text.TextStyle) {
    val exitCode = content.int("exitCode")
    val timedOut = content.boolean("timedOut") ?: false
    val ok = !timedOut && exitCode == 0
    Text(
        text = when {
            timedOut -> stringResource(R.string.tool_ui_shell_timeout)
            else -> stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}

/** 从工具输出 JSON 读取布尔字段 */
private fun JsonElement?.boolean(key: String): Boolean? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull

/** 从工具输出 JSON 读取整型字段 */
private fun JsonElement?.int(key: String): Int? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull

/** 从工具输出 JSON 读取长整型字段 */
private fun JsonElement?.long(key: String): Long? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.longOrNull

/** 从工具输出 JSON 读取字符串字段 */
private fun JsonElement?.string(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

/** 由文件扩展名推断语法高亮语言 */
private fun languageOf(path: String?): String = when (
    path?.substringAfterLast('.', "")?.lowercase().orEmpty()
) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}

/**
 * 工作空间 glob: 详情为索引文件列表（路径 + 目录/大小标记）
 */
object GlobToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_glob"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder02

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern != null) {
            stringResource(R.string.tool_ui_glob, pattern)
        } else {
            stringResource(R.string.tool_ui_glob_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val files = files(context)
        if (files.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tool_ui_glob_count, files.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null || content.getStringContent("error") != null) {
            DefaultToolPreview(context = context)
            return
        }
        val files = files(context)
        ToolDetailContainer {
            Text(
                text = context.arguments.getStringContent("pattern") ?: toolName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_glob_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                files.forEachIndexed { index, f ->
                    val isDir = f.boolean("isDirectory") ?: false
                    val size = f.long("sizeBytes")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.width(28.dp),
                        )
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = f.string("path") ?: f.string("name").orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (isDir) {
                            ToolPill(stringResource(R.string.tool_ui_dir))
                        } else if (size != null) {
                            ToolPill(formatFileSize(size))
                        }
                    }
                }
            }
        }
    }

    private fun files(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("files")?.jsonArray ?: emptyList()
}

/**
 * 工作空间 grep: 详情为按路径分组的行号匹配列表
 */
object GrepToolUI : ToolUIRenderer {
    private data class GrepMatch(val path: String, val line: Int?, val text: String)

    override val toolName: String = "workspace_grep"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern != null) {
            stringResource(R.string.tool_ui_grep, pattern)
        } else {
            stringResource(R.string.tool_ui_grep_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val matches = matches(context)
        if (matches.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tool_ui_grep_count, matches.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null || content.getStringContent("error") != null) {
            DefaultToolPreview(context = context)
            return
        }
        val matches = matches(context)
        ToolDetailContainer {
            Text(
                text = context.arguments.getStringContent("pattern") ?: toolName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_grep_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                matches.groupBy { it.path }.forEach { (path, pathMatches) ->
                    Text(
                        text = path.ifBlank { stringResource(R.string.tool_ui_file_unknown) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pathMatches.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ToolPill(m.line?.toString() ?: "–")
                                Spacer(Modifier.width(8.dp))
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = m.text,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun matches(context: ToolUIContext): List<GrepMatch> =
        (context.content?.jsonObjectOrNull?.get("matches") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                GrepMatch(
                    path = item.string("path").orEmpty(),
                    line = item.int("line"),
                    text = item.string("text").orEmpty(),
                )
            }
            .orEmpty()
}
