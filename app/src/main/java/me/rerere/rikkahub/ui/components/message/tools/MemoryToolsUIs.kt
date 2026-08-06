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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookCheck
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject

/** 详情页动作类型: 写/编辑/删除（保存与活跃两种记忆同构，差异在 isActive 标志与串） */
private enum class MemoryDetailKind { WRITE, EDIT, DELETE }

/** 读记忆 */
object ReadMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "read_memory"

    override fun icon(context: ToolUIContext): ImageVector = Lucide.BookCheck

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_memory)

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
}

/** 写入记忆 */
object WriteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "write_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_write_memory)

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
        stringResource(R.string.chat_message_tool_edit_memory)

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
        stringResource(R.string.chat_message_tool_delete_memory)

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
        stringResource(R.string.chat_message_tool_create_active_memory)

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
        stringResource(R.string.chat_message_tool_edit_active_memory)

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
        stringResource(R.string.chat_message_tool_delete_active_memory)

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

/** 删除类记忆的折叠摘要: 展示记忆标题 */
@Composable
private fun MemoryDeletedSummary(context: ToolUIContext) {
    val title = context.content.getStringContent("title")
        ?: context.arguments.getStringContent("title")
    title?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记忆详情页（write/edit/delete 与 active 同构共用）。
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
                    title?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    content?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
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
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }

    val canDelete = kind != MemoryDetailKind.DELETE && memoryId != null
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
            TextButton(
                onClick = {
                    scope.launch {
                        memoryRepo.addMemory(
                            assistantId = scopeId!!,
                            title = title.orEmpty(),
                            description = description.orEmpty(),
                            content = content!!,
                            overwrite = true,
                            isActive = isActive,
                        )
                        onDismissRequest()
                    }
                },
            ) {
                Text(stringResource(R.string.tool_ui_restore_memory))
            }
        }
        if (canRevert) {
            TextButton(onClick = { showRevertConfirm = true }) {
                Text(stringResource(R.string.tool_ui_revert_edit))
            }
        }
        if (canDelete) {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.tool_ui_delete_memory),
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
                            memoryRepo.deleteMemory(memoryId!!)
                            onDismissRequest()
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
                            memoryRepo.updateMemory(
                                id = memoryId!!,
                                title = title.orEmpty(),
                                description = description.orEmpty(),
                                content = previousContent!!,
                            )
                            onDismissRequest()
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
