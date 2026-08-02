package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 详情 BottomSheet 外层容器（与 DefaultToolPreview 一致的滚动区） */
@Composable
internal fun ToolDetailContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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
