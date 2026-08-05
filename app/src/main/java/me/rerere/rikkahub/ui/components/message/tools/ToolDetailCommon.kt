package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CodeSquare
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.utils.JsonInstantPretty

/** 详情内容容器（content-only：滚动由 ToolDetailSheet 统一提供） */
@Composable
internal fun ToolDetailContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** 元信息胶囊（Agora MetaPill） */
@Composable
internal fun ToolPill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** 终端风格输出块（Agora TerminalOutput）：等宽 + 可选中 */
@Composable
internal fun ToolTerminalOutput(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** 字节数 → 人类可读（512 B / 1.2 KB / 3.4 MB / 1.0 GB） */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    else -> {
        val kb = bytes / 1024.0
        if (kb < 1024) String.format("%.1f KB", kb)
        else {
            val mb = kb / 1024
            if (mb < 1024) String.format("%.1f MB", mb)
            else String.format("%.1f GB", mb / 1024)
        }
    }
}

/** 工具入参是否为空（{} / [] / null）：空参数时隐藏"参数"分区 */
internal fun JsonElement.isJsonEmpty(): Boolean = when (this) {
    is JsonObject -> isEmpty()
    is JsonArray -> isEmpty()
    is JsonNull -> true
    is JsonPrimitive -> false
}

/** 工具详情整页 JSON 视图（content-only）：参数 + 结果 JsonTreeView */
@Composable
internal fun ToolJsonBody(context: ToolUIContext) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!context.arguments.isJsonEmpty()) {
            ToolJsonSection(
                label = stringResource(R.string.tool_ui_arguments),
                json = context.arguments,
            ) {
                JsonTreeView(context.arguments)
            }
        }
        if (context.content != null) {
            ToolJsonSection(
                label = stringResource(R.string.chat_message_tool_call_result),
                json = context.content,
            ) {
                JsonTreeView(context.content)
            }
        }
    }
}

/**
 * 工具详情分区：label 右侧一个小开关（CodeSquare），切换 渲染视图(semanticContent) / 原始 json/text 文本框。
 * [json] 非 null 时开关才把内容切到原始 JSON 文本框；为 null（如结果非 JSON）时默认不提供开关，直接渲染语义内容。
 * 注意：[semanticContent] 必须是末位参数（尾随 lambda 绑定它）；具名参数 [showToggle] 位于其前。
 */
@Composable
internal fun ToolJsonSection(
    label: String,
    json: JsonElement?,
    showToggle: Boolean = json != null,
    semanticContent: @Composable () -> Unit,
) {
    var showRaw by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (showToggle) {
                IconButton(onClick = { showRaw = !showRaw }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = HugeIcons.CodeSquare,
                        contentDescription = stringResource(
                            if (showRaw) R.string.tool_ui_view_style else R.string.tool_ui_view_json,
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = if (showRaw) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (showRaw && json != null) {
            ToolJsonRawText(json)
        } else {
            semanticContent()
        }
    }
}

/** 原始 JSON 代码块（对齐最早 rikkahub：语法高亮 + 复制 + 下载，HighlightCodeBlock） */
@Composable
internal fun ToolJsonRawText(json: JsonElement) {
    HighlightCodeBlock(
        code = JsonInstantPretty.encodeToString(json),
        language = "json",
        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
    )
}
