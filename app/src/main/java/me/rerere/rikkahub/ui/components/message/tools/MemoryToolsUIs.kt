package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R

/** 读记忆 */
object ReadMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "read_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

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

/** 更新活跃记忆 */
object UpdateActiveMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "update_active_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.PencilEdit01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_update_active_memory)

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
}

/** 编辑记忆 */
object EditMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "edit_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

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
}

/** 删除记忆 */
object DeleteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "delete_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Eraser

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_delete_memory)

    override fun hasSummary(context: ToolUIContext): Boolean = false

    @Composable
    override fun Summary(context: ToolUIContext) {
    }
}
