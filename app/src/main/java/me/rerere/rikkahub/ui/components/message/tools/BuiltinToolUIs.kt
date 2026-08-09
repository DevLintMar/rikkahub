package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChatBot
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.FolderClock
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.MessageDelay01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 记忆工具: 按 action 区分标题/图标, 摘要显示记忆内容, 详情附带删除按钮
 */
object MemoryToolUI : ToolUIRenderer {
    private const val ACTION_CREATE = "create"
    private const val ACTION_EDIT = "edit"
    private const val ACTION_DELETE = "delete"

    override val toolName: String = "memory_tool"

    private fun action(context: ToolUIContext): String? =
        context.arguments.getStringContent("action")

    override fun icon(context: ToolUIContext): ImageVector = when (action(context)) {
        ACTION_DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        // 旧数据回退: 信封 title → 入参 title → 内容前 40 字 → 空串
        val legacyTitle = context.content.getStringContent("title")
            ?: context.arguments.getStringContent("title")
            ?: context.content.getStringContent("content")?.trim()?.take(40).orEmpty()
        return when (action(context)) {
            ACTION_CREATE -> stringResource(R.string.chat_message_tool_create_memory)
            ACTION_EDIT -> stringResource(R.string.chat_message_tool_edit_memory, legacyTitle)
            ACTION_DELETE -> stringResource(R.string.chat_message_tool_delete_memory, legacyTitle)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        action(context) in listOf(ACTION_CREATE, ACTION_EDIT) &&
            context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { memoryContent ->
            Text(
                text = memoryContent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
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
        val envelope = context.content
        if (envelope == null || envelope.getStringContent("error") != null) {
            DefaultToolPreview(context = context)
            return
        }
        val memoryRepo: MemoryRepository = koinInject()
        val scope = rememberCoroutineScope()
        val memoryId = (envelope as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        var showDeleteConfirm by remember { mutableStateOf(false) }
        val content = envelope.getStringContent("content")
        val canDelete = action(context) in listOf(ACTION_CREATE, ACTION_EDIT) && memoryId != null
        Column(modifier = Modifier.fillMaxWidth()) {
            ToolDetailContainer {
                when (action(context)) {
                    ACTION_CREATE, ACTION_EDIT -> {
                        if (!content.isNullOrBlank()) {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (memoryId != null) {
                            ToolPill(stringResource(R.string.tool_ui_memory_id, memoryId))
                        }
                    }
                    ACTION_DELETE -> Text(
                        text = stringResource(R.string.tool_ui_memory_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> DefaultToolPreview(context = context)
                }
            }
            if (canDelete) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { showDeleteConfirm = true }
                    ) {
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
                        ) { Text(stringResource(R.string.tool_ui_delete_memory_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }
}

/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_search_web,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun items(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("items")?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || items(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = items(context)
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = items.mapNotNull { it.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
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
        SearchWebPreview(content = content)
    }
}

/**
 * 网页抓取: 摘要显示抓取页数与失败数, 详情为各 URL 的独立卡片（元信息/图片/失败项）
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_scrape_web)

    private fun entries(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("urls")?.jsonArray ?: emptyList()

    private fun failedCount(entries: List<JsonElement>): Int =
        entries.count { it.jsonObjectOrNull?.getStringContent("error") != null }

    override fun hasSummary(context: ToolUIContext): Boolean =
        entries(context).isNotEmpty() || context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val entries = entries(context)
        // 旧信封兼容：无 urls 数组时回退显示 URL
        if (entries.isEmpty()) {
            val legacyUrl = context.arguments.getStringContent("url")
            if (legacyUrl != null) {
                Text(
                    text = legacyUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            return
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = entries.mapNotNull { it.jsonObjectOrNull?.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_scrape_urls, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            val failed = failedCount(entries)
            if (failed > 0) {
                Text(
                    text = stringResource(R.string.chat_message_tool_scrape_failed, failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
        ScrapeWebPreview(content = content)
    }
}

/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_get_time)

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val date = content.getStringContent("date")
        val time = content.getStringContent("time")
        val weekday = content.getStringContent("weekday")
        val timezone = content.getStringContent("timezone")
        val utcOffset = content.getStringContent("utc_offset")
        ToolDetailContainer {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = date ?: stringResource(R.string.tool_ui_time_default),
                    style = MaterialTheme.typography.titleMedium,
                )
                weekday?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
            if (time != null) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (timezone != null || utcOffset != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timezone?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                    utcOffset?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                }
            }
        }
    }
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
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
        val text = content.getStringContent("text")
        val action = context.arguments.getStringContent("action")
        ToolDetailContainer {
            when (action) {
                ACTION_READ -> if (text.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.tool_ui_clipboard_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ToolTerminalOutput(text)
                }
                ACTION_WRITE -> {
                    Text(
                        text = stringResource(R.string.tool_ui_clipboard_written),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    text?.takeIf { it.isNotBlank() }?.let { ToolTerminalOutput(it) }
                }
                else -> DefaultToolPreview(context = context)
            }
        }
    }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = "text_to_speech"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    // 标题固定"朗读"（朗读内容内联在 Summary 下方, 无需在标题二次显示）
    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_speaking)

    // 文本来源与 Preview 保持一致（content 优先、arguments 兜底；arguments 在调用期即已知）
    private fun speakText(context: ToolUIContext): String =
        context.content.getStringContent("text")
            ?: context.arguments.getStringContent("text")
            ?: ""

    // 回归最早展示模式: 折叠步骤内联显示朗读文本 + 右侧重放按钮（596f08d 误移入 Preview）
    override fun hasSummary(context: ToolUIContext): Boolean = speakText(context).isNotBlank()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = speakText(context)
        if (text.isBlank()) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.tool_ui_replay),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = speakText(context)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalIconButton(
                    onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                ) {
                    Icon(
                        imageVector = HugeIcons.Refresh01,
                        contentDescription = stringResource(R.string.tool_ui_replay),
                    )
                }
            }
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
            .isNotBlank()

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val output = context.tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (output.isBlank()) {
            DefaultToolPreview(context = context)
            return
        }
        ToolDetailContainer {
            ToolTerminalOutput(output)
        }
    }
}

/**
 * 图片读取（read_image）：标题固定"识别图片"（流式调用中与调用完成一致），
 * 折叠下方小字显示图片缩略图与识别结果预览；详情信封渲染图片列表 + 每张图的识别结果。
 */
object ReadImageToolUI : ToolUIRenderer {
    override val toolName: String = "read_image"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Image03

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_image)

    /** 单张图片的结果条目（来自 content["results"]） */
    private data class Entry(
        val url: String,
        val mode: String,
        val text: String?,
    )

    private fun entries(context: ToolUIContext): List<Entry> =
        context.content?.jsonObjectOrNull?.get("results")?.jsonArray
            ?.mapNotNull { it.jsonObjectOrNull }
            ?.map { obj ->
                Entry(
                    url = obj.getStringContent("url") ?: "",
                    mode = obj.getStringContent("mode") ?: "",
                    text = obj.getStringContent("text"),
                )
            }
            ?: emptyList()

    private fun images(context: ToolUIContext): List<String> =
        context.tool.output.filterIsInstance<UIMessagePart.Image>().map { it.url }

    /** 剥掉 OCR 文本外层 <image_file_ocr> 包裹，只留识别内容 */
    private fun cleanOcrText(text: String): String {
        val inner = Regex("<image_file_ocr>([\\s\\S]*?)</image_file_ocr>")
            .find(text)?.groupValues?.getOrNull(1)?.trim()
        return inner?.takeIf { it.isNotBlank() } ?: text.trim()
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        entries(context).isNotEmpty() || images(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        // 图片缩略图由 ChatMessageTools 统一渲染（折叠步骤 64dp 缩略图），此处只给文本概览
        val entries = entries(context)
        val failed = entries.count { it.mode == "error" }
        if (entries.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_message_tool_read_image_count, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                if (failed > 0) {
                    Text(
                        text = stringResource(R.string.chat_message_tool_read_image_failed, failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            entries.firstOrNull { it.mode == "ocr" }?.text?.let { ocr ->
                Text(
                    text = cleanOcrText(ocr),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
        val images = images(context)
        val entries = entries(context)
        ToolDetailContainer {
            if (images.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(images) { url ->
                        ZoomableAsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(120.dp)
                                .width(160.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            content.getStringContent("note")?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty()) {
                ToolJsonRawText(content)
            } else {
                entries.forEach { entry ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when (entry.mode) {
                                "error" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ToolPill(
                                    when (entry.mode) {
                                        "base64" -> "base64"
                                        "ocr" -> "OCR"
                                        else -> stringResource(R.string.chat_message_tool_read_image_failed_label)
                                    }
                                )
                                Text(
                                    text = entry.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            when (entry.mode) {
                                "error" -> entry.text?.let { err ->
                                    Text(
                                        text = err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                "ocr" -> entry.text?.let { ocr ->
                                    Text(
                                        text = cleanOcrText(ocr),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                else -> Unit // base64 图片已在上方渲染
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 聊天文件夹列表: 标题固定, 折叠下方小字列出文件夹（当前文件夹高亮+标记）,
 * 详情为每个文件夹的名称/当前标记/对话数/ID 的信封展示
 */
object ListConversationFoldersToolUI : ToolUIRenderer {
    override val toolName: String = "list_conversation_folders"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FolderClock

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_list_folders)

    private fun folders(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonObject)?.get("folders") as? JsonArray ?: emptyList()

    /** 该条目是否为当前文件夹（is_current） */
    private fun JsonElement.isCurrentFolder(): Boolean =
        jsonObjectOrNull?.get("is_current")?.jsonPrimitiveOrNull?.booleanOrNull ?: false

    /** 该条目是否为默认「聊天」文件夹（id 为 default 的未归类条目） */
    private fun JsonElement.isDefaultFolder(): Boolean =
        jsonObjectOrNull?.get("id")?.jsonPrimitiveOrNull?.contentOrNull == "default"

    /** 该条目的 id 文本：默认文件夹显示 "default"，其余为文件夹 UUID */
    private fun JsonElement.folderIdText(): String =
        jsonObjectOrNull?.get("id")?.jsonPrimitiveOrNull?.contentOrNull ?: "default"

    @Composable
    private fun JsonElement.folderDisplayName(): String =
        if (isDefaultFolder()) {
            stringResource(R.string.chat_page_folder_default)
        } else {
            getStringContent("name") ?: stringResource(R.string.tool_ui_untitled)
        }

    override fun hasSummary(context: ToolUIContext): Boolean = folders(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val folders = folders(context)
        if (folders.isEmpty()) return
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            folders.forEach { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = folder.folderDisplayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (folder.isCurrentFolder()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (folder.isCurrentFolder()) {
                        Text(
                            text = stringResource(R.string.tool_ui_folder_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
        val folders = folders(context)
        ToolDetailContainer {
            if (folders.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_recent_chats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                folders.forEach { folder ->
                    val isCurrent = folder.isCurrentFolder()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = folder.folderDisplayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                ToolPill(stringResource(R.string.tool_ui_folder_current))
                            }
                            (folder.jsonObjectOrNull?.get("conversation_count"))
                                ?.jsonPrimitiveOrNull?.intOrNull
                                ?.let { count ->
                                    ToolPill(stringResource(R.string.tool_ui_folder_conversations, count))
                                }
                        }
                        Text(
                            text = folder.folderIdText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 最近聊天: 标题固定, 摘要列出最近对话的标题
 */
object RecentChatsToolUI : ToolUIRenderer {
    override val toolName: String = "recent_chats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_recent_chats)

    private fun chats(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonObject)?.get("conversations") as? JsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = chats(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val titles = chats(context).mapNotNull { it.getStringContent("title") }
        if (titles.isEmpty()) return
        Text(
            text = titles.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
        val chats = chats(context)
        ToolDetailContainer {
            if (chats.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_recent_chats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                chats.forEach { c ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = c.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        c.getStringContent("last_chat")?.let { lastChat ->
                            Text(
                                text = lastChat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 对话历史搜索: 标题带查询词, 摘要显示命中数
 */
object ConversationSearchToolUI : ToolUIRenderer {
    override val toolName: String = "conversation_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_conversation_search,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun results(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonObject)?.get("results") as? JsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = results(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results.isEmpty()) return
        Text(
            text = stringResource(R.string.chat_message_tool_search_results_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
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
        val results = results(context)
        ToolDetailContainer {
            if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_conv_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                results.forEach { r ->
                    val title = r.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled)
                    val index = (r.jsonObjectOrNull?.get("index") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    val role = r.getStringContent("role")
                    val date = r.getStringContent("date")
                    val snippet = r.getStringContent("snippet").orEmpty()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            index?.let { ToolPill("#$it") }
                            role?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                            date?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                        }
                        if (snippet.isNotBlank()) {
                            Text(
                                text = snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 屏幕使用时间: 摘要显示总时长与用时最多的应用, 详情为按时长排序的应用列表 (带占比条);
 * 无权限时回退到默认 JSON 详情
 */
object GetScreenTimeToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_APPS = 3

    override val toolName: String = "get_screen_time"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_screen_time)

    private fun apps(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("apps")?.let { it as? JsonArray } ?: emptyList()

    private fun isNoPermission(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") == "NO_PERMISSION"

    override fun hasSummary(context: ToolUIContext): Boolean =
        isNoPermission(context) || apps(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (isNoPermission(context)) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        val apps = apps(context)
        if (apps.isEmpty()) return
        val totalMinutes = context.content?.jsonObjectOrNull?.get("total_minutes")
            ?.jsonPrimitiveOrNull?.longOrNull ?: 0
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_time_total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMinutes(totalMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            apps.take(SUMMARY_MAX_APPS).forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = app.getStringContent("app_name")
                            ?: app.getStringContent("package") ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    // 与 Preview 的 DefaultToolPreview fallback 保持一致
    override fun hasSemanticDetail(context: ToolUIContext): Boolean =
        context.content != null && context.content.jsonObjectOrNull?.get("error") == null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val apps = apps(context)
        // 错误响应（无权限/非法时间/非法范围）无 apps, 回退默认 JSON 详情
        if (content.jsonObjectOrNull?.get("error") != null) {
            DefaultToolPreview(context = context)
            return
        }
        ToolDetailContainer {
            // 回归最早展示: 总屏幕时间单独一行(label + Xh Ym), 下方区间与按时长排序的 app 列表(带占比条)
            val totalMinutes = content.jsonObjectOrNull?.get("total_minutes")
                ?.jsonPrimitiveOrNull?.longOrNull ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tool_ui_screen_time_total),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(totalMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val begin = content.getStringContent("start")
                val finish = content.getStringContent("end")
                if (begin != null && finish != null) {
                    Text(
                        text = "${formatRangeTime(begin)} → ${formatRangeTime(finish)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxAppMs = apps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
                apps.forEach { app ->
                    val name = app.getStringContent("app_name")
                        ?: app.getStringContent("package") ?: return@forEach
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatMinutes(app.appMinutes()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (app.appMs().toFloat() / maxAppMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

object CalendarQueryToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_query"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Calendar03

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_calendar_query)

    private fun events(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("events")?.let { it as? JsonArray } ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = events(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val events = events(context)
        if (events.isEmpty()) return
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_search_results_count, events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            events.take(3).forEach { event ->
                val title = event.getStringContent("title") ?: return@forEach
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        val events = events(context)
        ToolDetailContainer {
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_calendar_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.forEach { ev ->
                    val title = ev.getStringContent("title")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tool_ui_untitled)
                    val start = ev.getStringContent("start")
                    val allDay = (ev.jsonObjectOrNull?.get("all_day") as? JsonPrimitive)?.contentOrNull == "true"
                    val calendar = ev.getStringContent("calendar")
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (allDay) ToolPill(stringResource(R.string.tool_ui_all_day))
                        }
                        start?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        calendar?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

object CalendarCreateToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_create"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CalendarAdd01

    @Composable
    override fun title(context: ToolUIContext): String {
        val eventTitle = context.arguments.getStringContent("title") ?: ""
        return stringResource(R.string.chat_message_tool_calendar_create, eventTitle)
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
        val title = content.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled)
        val start = content.getStringContent("start")
        val end = content.getStringContent("end")
        val eventId = content.getStringContent("event_id")
        ToolDetailContainer {
            Text(
                text = stringResource(R.string.tool_ui_event_created, title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            start?.let { Text(stringResource(R.string.tool_ui_event_start, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            end?.let { Text(stringResource(R.string.tool_ui_event_end, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            eventId?.let { ToolPill(stringResource(R.string.tool_ui_event_id, it)) }
        }
    }
}

/** 读取单个应用条目的前台时长 (毫秒) */
private fun JsonElement.appMs(): Long =
    jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

/** 读取单个应用条目的前台时长 (分钟)；优先 total_minutes，回退 total_ms/60000 */
private fun JsonElement.appMinutes(): Long =
    jsonObjectOrNull?.get("total_minutes")?.jsonPrimitiveOrNull?.longOrNull ?: (appMs() / 60000)

/** 将分钟数格式化为 "Xh Ym" / "Xh" / "Ym" */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

private val SCREEN_TIME_RANGE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 将工具返回的 ISO 时间字符串格式化为 "MM-dd HH:mm", 解析失败时原样返回.
 *
 * 工具用 ZonedDateTime.toString() 输出, 区域 ID 时会带 "[Asia/Shanghai]" 后缀,
 * 故优先用 ZonedDateTime.parse, 再回退到 offset / 本地日期时间.
 */
private fun formatRangeTime(iso: String): String = runCatching {
    ZonedDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    OffsetDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    LocalDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.getOrDefault(iso)

@Composable
private fun SearchWebPreview(content: JsonElement) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val images = content.jsonObject["images"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content.getStringContent("answer")?.let { answer ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                MarkdownBlock(
                    content = answer,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (images.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(images) { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(120.dp)
                            .width(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { context.openUrl(imageUrl) },
                    )
                }
            }
        }

        if (items.isNotEmpty()) {
            items.forEach { item ->
                val url = item.getStringContent("url") ?: return@forEach
                val title = item.getStringContent("title") ?: return@forEach
                val text = item.getStringContent("text") ?: return@forEach

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            HighlightText(
                code = JsonInstantPretty.encodeToString(content),
                language = "json",
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * 网页抓取详情: 每个 URL 独立卡片——favicon + 标题 + URL + HTTP 状态码 + 发布时间/作者 pill，
 * 失败项红色展示错误原因，成功项展示图片缩略图与 Markdown 正文。
 */
@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urlEntries = content.jsonObjectOrNull?.get("urls")?.jsonArray

    // 兼容旧信封（无 urls 数组的老数据 {url, text, truncated, totalChars}）回退原渲染
    if (urlEntries == null || urlEntries.isEmpty()) {
        LegacyScrapeWebPreview(content)
        return
    }

    val truncated = content.jsonObjectOrNull
        ?.get("truncated")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
    val totalChars = content.jsonObjectOrNull
        ?.get("totalChars")?.jsonPrimitiveOrNull?.longOrNull ?: 0L
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        urlEntries.forEach { entry ->
            val obj = entry.jsonObjectOrNull ?: return@forEach
            val url = obj.getStringContent("url") ?: return@forEach
            val error = obj.getStringContent("error")
            val title = obj.jsonObjectOrNull?.get("metadata")?.jsonObjectOrNull?.getStringContent("title")
                ?: obj.getStringContent("title")
            val statusCode = obj.jsonObjectOrNull?.get("statusCode")?.jsonPrimitiveOrNull?.intOrNull
            val publishedDate = obj.getStringContent("publishedDate")
            val author = obj.getStringContent("author")
            val images = obj.jsonObjectOrNull?.get("images")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val text = obj.getStringContent("content")

            Card(
                onClick = { context.openUrl(url) },
                colors = CardDefaults.cardColors(
                    containerColor = if (error != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Favicon(url = url, modifier = Modifier.size(20.dp))
                        Text(
                            text = title?.takeIf { it.isNotBlank() } ?: url,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        statusCode?.let { ToolPill(stringResource(R.string.tool_ui_scrape_status, it)) }
                    }
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        return@Column
                    }
                    if (publishedDate != null || author != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            publishedDate?.takeIf { it.isNotBlank() }?.let {
                                ToolPill(stringResource(R.string.tool_ui_scrape_published, it))
                            }
                            author?.takeIf { it.isNotBlank() }?.let {
                                ToolPill(stringResource(R.string.tool_ui_scrape_author, it))
                            }
                        }
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images) { imageUrl ->
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    // 与 SearchWebPreview 图片数组展示保持一致（120x160，圆角 12dp）
                                    modifier = Modifier
                                        .height(120.dp)
                                        .width(160.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { context.openUrl(imageUrl) },
                                )
                            }
                        }
                    }
                    if (!text.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            MarkdownBlock(
                                content = text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
        if (truncated) {
            Text(
                text = stringResource(R.string.tool_ui_scrape_truncated, totalChars),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
            )
        }
    }
}

/** 旧版 scrape 信封（{url, text, truncated, totalChars}）渲染，供历史消息回退 */
@Composable
private fun LegacyScrapeWebPreview(content: JsonElement) {
    val url = content.getStringContent("url")
    val text = content.getStringContent("text")
    val truncated = content.jsonObjectOrNull
        ?.get("truncated")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
    val totalChars = content.jsonObjectOrNull
        ?.get("totalChars")?.jsonPrimitiveOrNull?.longOrNull ?: 0L

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(
                R.string.chat_message_tool_scrape_prefix,
                url ?: ""
            )
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!text.isNullOrBlank()) {
                Card {
                    MarkdownBlock(
                        content = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
            if (truncated) {
                Text(
                    text = stringResource(R.string.tool_ui_scrape_truncated, totalChars),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 阅读对话: 标题=阅读对话, 详情=标题+元信息+消息列表 */
object ReadConversationToolUI : ToolUIRenderer {
    override val toolName: String = "read_conversation"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MessageDelay01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_conversation)

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
        val title = content.getStringContent("title")
            ?: context.arguments.getStringContent("conversation_id")
            ?: stringResource(R.string.tool_ui_untitled)
        val total = content.getStringContent("total_messages")?.toIntOrNull()
        val messages = (content.jsonObjectOrNull?.get("messages") as? JsonArray) ?: emptyList()
        ToolDetailContainer {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                total?.let { ToolPill(stringResource(R.string.tool_ui_read_conv_messages, it)) }
            }
            val hasMore = content.getStringContent("has_more") == "true"
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_read_conv_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                messages.forEach { m ->
                    val role = m.getStringContent("role")
                    val text = m.getStringContent("text").orEmpty()
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when (role) {
                                "user" -> stringResource(R.string.tool_ui_read_conv_role_user)
                                "assistant" -> stringResource(R.string.tool_ui_read_conv_role_assistant)
                                else -> role ?: ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ToolTerminalOutput(text)
                    }
                }
                if (hasMore) {
                    Text(
                        text = stringResource(R.string.tool_ui_read_conv_more),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 子代理: 标题=子代理, 详情=描述+状态+结果/错误 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "sub_agent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ChatBot

    @Composable
    override fun title(context: ToolUIContext): String {
        // loading（content=null）默认"运行子代理"；确认为后台执行（mode=background）才加"（后台）"
        val isBackground = context.content.getStringContent("mode") == "background"
        return stringResource(
            if (isBackground) R.string.chat_message_tool_sub_agent_background
            else R.string.chat_message_tool_sub_agent
        )
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
        val description = content.getStringContent("description")
            ?: context.arguments.getStringContent("description")
        val status = content.getStringContent("status")
        val result = content.getStringContent("result")
        val error = content.getStringContent("error")
        val taskId = content.getStringContent("task_id")
        ToolDetailContainer {
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            context.arguments.getStringContent("prompt")?.takeIf { it.isNotBlank() }?.let { prompt ->
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "started" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_started))
                    "completed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_completed))
                    "failed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_failed))
                }
                taskId?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!result.isNullOrBlank()) {
                ToolTerminalOutput(result)
            }
        }
    }
}
