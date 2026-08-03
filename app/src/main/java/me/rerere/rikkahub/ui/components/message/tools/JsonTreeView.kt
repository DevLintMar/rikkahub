/** 详情页的平铺 JSON 树。日志页可展开 JSON 树见 ui/components/ui/JsonTree.kt（交互模式不同，两组件并存不合并）。 */
package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 平铺 JSON 树渲染上限：深度/条目超限即省略号截断，防止 MCP 大响应全量组合拖垮 UI。 */
private const val JSON_TREE_MAX_DEPTH = 12
private const val JSON_TREE_MAX_ITEMS = 2000

/** Agora JsonNodeView 对齐：JSON 树形视图（键 chips + 内联值 + 嵌套缩进 + 可选中）。 */
@Composable
internal fun JsonTreeView(json: JsonElement) {
    // 共享 IntArray(1) 持有剩余条目计数，沿递归实时回流（写数组不触发重组）
    val remaining = remember { intArrayOf(JSON_TREE_MAX_ITEMS) }
    remaining[0] = JSON_TREE_MAX_ITEMS // 每次组合重置预算，避免跨重组累计耗尽
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (json) {
                is JsonObject -> JsonTreeObjectView(json, 0, remaining)
                is JsonArray -> JsonTreeArrayView(json, 0, remaining)
                is JsonPrimitive -> JsonTreePrimitiveView(json, Modifier.fillMaxWidth())
                is JsonNull -> Text(
                    text = "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 长(>40)或含换行的字符串值单独一行展示，避免被压成窄列。 */
private fun isBlockString(value: JsonElement): Boolean =
    value is JsonPrimitive && value.isString &&
        (value.content.length > 40 || value.content.contains('\n'))

@Composable
private fun JsonTreeKeyChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun JsonTreeObjectView(obj: JsonObject, depth: Int, remaining: IntArray) {
    if (depth > JSON_TREE_MAX_DEPTH || remaining[0] <= 0) {
        Text(
            text = "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        obj.entries.forEach { (key, value) ->
            if (remaining[0] <= 0) return@forEach
            remaining[0]--
            val blockString = isBlockString(value)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    JsonTreeKeyChip(key, MaterialTheme.colorScheme.primary)
                    if (!blockString) {
                        Spacer(Modifier.width(8.dp))
                        when (value) {
                            is JsonPrimitive -> JsonTreePrimitiveView(value, Modifier.weight(1f))
                            is JsonNull -> Text(
                                text = "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is JsonObject -> Text(
                                text = "{…}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is JsonArray -> Text(
                                text = "[…]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (blockString && value is JsonPrimitive) {
                    JsonTreePrimitiveView(value, Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                when (value) {
                    is JsonObject -> Box(
                        modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp),
                    ) { JsonTreeObjectView(value, depth + 1, remaining) }
                    is JsonArray -> Box(
                        modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp),
                    ) { JsonTreeArrayView(value, depth + 1, remaining) }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun JsonTreeArrayView(arr: JsonArray, depth: Int, remaining: IntArray) {
    if (depth > JSON_TREE_MAX_DEPTH || remaining[0] <= 0) {
        Text(
            text = "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        arr.forEachIndexed { i, item ->
            if (remaining[0] <= 0) return@forEachIndexed
            remaining[0]--
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                JsonTreeKeyChip((i + 1).toString(), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                when (item) {
                    is JsonPrimitive -> JsonTreePrimitiveView(item, Modifier.weight(1f))
                    is JsonNull -> Text(
                        text = "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is JsonObject -> Box(Modifier.weight(1f)) { JsonTreeObjectView(item, depth, remaining) }
                    is JsonArray -> Box(Modifier.weight(1f)) { JsonTreeArrayView(item, depth, remaining) }
                }
            }
        }
    }
}

@Composable
private fun JsonTreePrimitiveView(primitive: JsonPrimitive, modifier: Modifier = Modifier) {
    val color = when {
        primitive.isString -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = primitive.content,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        ),
        color = color,
        modifier = modifier,
    )
}
