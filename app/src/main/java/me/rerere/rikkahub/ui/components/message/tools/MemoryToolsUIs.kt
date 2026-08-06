package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookmarkCheck02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Redo
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

/** 详情页动作类型: 写/编辑/删除/读（保存与活跃两种记忆同构，差异在 isActive 标志与串） */
private enum class MemoryDetailKind { WRITE, EDIT, DELETE, READ }

/**
 * 解析记忆工具概览标题: 信封 title → 入参 title → 内容前 40 字。
 * 均取不到时返回无参标题串（titleResId 不含占位符）。
 */
@Composable
private fun memoryToolTitle(context: ToolUIContext, titleResId: Int): String {
    val title = context.content.getStringContent("title")
        ?.takeIf { it.isNotBlank() }
        ?: context.arguments.getStringContent("title")
            ?.takeIf { it.isNotBlank() }
        ?: context.content.getStringContent("content")
            ?.trim()
            ?.take(40)
            ?.takeIf { it.isNotBlank() }
    return if (title != null) {
        stringResource(titleResId, title)
    } else {
        stringResource(titleResId)
    }
}

/** 读记忆 */
object ReadMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "read_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.BookmarkCheck02

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_read_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.READ,
            isActive = false,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 写入记忆 */
object WriteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "write_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_write_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.WRITE,
            isActive = false,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 编辑记忆 */
object EditMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "edit_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.PencilEdit01

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_edit_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.EDIT,
            isActive = false,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 删除记忆 */
object DeleteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "delete_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Eraser

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_delete_memory)

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        MemoryDeletedSummary(context = context)
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.DELETE,
            isActive = false,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 新建活跃记忆 */
object CreateActiveMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "create_active_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_create_active_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.WRITE,
            isActive = true,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 编辑活跃记忆 */
object EditActiveMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "edit_active_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.PencilEdit01

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_edit_active_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.EDIT,
            isActive = true,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 删除活跃记忆 */
object DeleteActiveMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "delete_active_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Eraser

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(context, R.string.chat_message_tool_delete_active_memory)

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        MemoryDeletedSummary(context = context)
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.getStringContent("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        MemoryDetailPreview(
            context = context,
            kind = MemoryDetailKind.DELETE,
            isActive = true,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** 删除类记忆的折叠摘要: 展示记忆内容（旧数据/失败信封退回标题） */
@Composable
private fun MemoryDeletedSummary(context: ToolUIContext) {
    val summary = context.content.getStringContent("content")
        ?: context.arguments.getStringContent("content")
        ?: context.content.getStringContent("title")
    summary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记忆详情页（write/edit/read/delete 与 active 同构共用）。
 * 保存与活跃的差异只在恢复时的 isActive 标志；动作按钮与确认对话框复用 [MemoryDetailActions]。
 */
@Composable
private fun MemoryDetailPreview(
    context: ToolUIContext,
    kind: MemoryDetailKind,
    isActive: Boolean,
    onDismissRequest: () -> Unit,
) {
    val envelope = context.content
    if (envelope == null || envelope.getStringContent("error") != null) {
        DefaultToolPreview(context = context)
        return
    }
    val memoryId = (envelope as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
    val title = envelope.getStringContent("title")
    val description = envelope.getStringContent("description")
    val content = envelope.getStringContent("content")
    val previousContent = envelope.getStringContent("previous_content")
    val scopeId = envelope.getStringContent("scope_id")
    Column(modifier = Modifier.fillMaxWidth()) {
        ToolDetailContainer {
            // 共用头部: 标题加粗 + 描述斜体（空则省略），各 kind 在其下渲染各自内容块
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (kind) {
                MemoryDetailKind.WRITE -> {
                    content?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    memoryId?.let { ToolPill(stringResource(R.string.tool_ui_memory_id, it)) }
                }
                MemoryDetailKind.EDIT -> {
                    content?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (previousContent != null) {
                        Text(
                            text = previousContent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    memoryId?.let { ToolPill(stringResource(R.string.tool_ui_memory_id, it)) }
                }
                MemoryDetailKind.DELETE -> {
                    content?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                MemoryDetailKind.READ -> {
                    content?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    memoryId?.let { ToolPill(stringResource(R.string.tool_ui_memory_id, it)) }
                }
            }
        }
        MemoryDetailActions(
            kind = kind,
            isActive = isActive,
            memoryId = memoryId,
            title = title,
            description = description,
            content = content,
            previousContent = previousContent,
            scopeId = scopeId,
            onDismissRequest = onDismissRequest,
        )
    }
}

/**
 * 记忆详情页动作栏: 写/创建可删除本条（确认对话框）; 编辑可回退编辑（确认对话框）; 删除可恢复记忆。
 * 全部通过 MemoryRepository 直接落库, 完成后 dismiss。
 */
@Composable
private fun MemoryDetailActions(
    kind: MemoryDetailKind,
    isActive: Boolean,
    memoryId: Int?,
    title: String?,
    description: String?,
    content: String?,
    previousContent: String?,
    scopeId: String?,
    onDismissRequest: () -> Unit,
) {
    val memoryRepo: MemoryRepository = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }

    val canDelete = kind != MemoryDetailKind.DELETE && kind != MemoryDetailKind.READ && memoryId != null
    val canRevert = kind == MemoryDetailKind.EDIT && memoryId != null && previousContent != null
    val canRestore = kind == MemoryDetailKind.DELETE && scopeId != null && content != null
    if (!canDelete && !canRevert && !canRestore) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRestore) {
            IconButton(
                onClick = {
                    scope.launch {
                        try {
                            memoryRepo.addMemory(
                                assistantId = scopeId!!,
                                title = title.orEmpty(),
                                description = description.orEmpty(),
                                content = content!!,
                                overwrite = true,
                                isActive = isActive,
                            )
                            onDismissRequest()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toaster.show(
                                message = e.message ?: e.javaClass.simpleName,
                                type = ToastType.Error,
                            )
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = HugeIcons.Redo,
                    contentDescription = stringResource(R.string.tool_ui_restore_memory),
                )
            }
        }
        if (canRevert) {
            IconButton(onClick = { showRevertConfirm = true }) {
                Icon(
                    imageVector = HugeIcons.Redo,
                    contentDescription = stringResource(R.string.tool_ui_revert_edit),
                )
            }
        }
        if (canDelete) {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.tool_ui_delete_this_memory),
                )
            }
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.tool_ui_delete_memory_title)) },
            text = { Text(stringResource(R.string.tool_ui_delete_memory_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            try {
                                memoryRepo.deleteMemory(memoryId!!)
                                onDismissRequest()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toaster.show(
                                    message = e.message ?: e.javaClass.simpleName,
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.tool_ui_delete_memory_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (showRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = { Text(stringResource(R.string.tool_ui_revert_edit)) },
            text = {
                Text(
                    text = previousContent?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.tool_ui_revert_edit),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevertConfirm = false
                        scope.launch {
                            try {
                                memoryRepo.updateMemory(
                                    id = memoryId!!,
                                    title = title.orEmpty(),
                                    description = description.orEmpty(),
                                    content = previousContent!!,
                                )
                                onDismissRequest()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                toaster.show(
                                    message = e.message ?: e.javaClass.simpleName,
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.tool_ui_revert_edit))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
