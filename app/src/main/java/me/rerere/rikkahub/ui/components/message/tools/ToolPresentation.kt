package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.parseEnvelope

/** 工具种类（展示分流用；MCP/未知 → UNKNOWN） */
enum class ToolKind {
    WEB_SEARCH, WEB_FETCH,
    CONVERSATION_LIST, CONVERSATION_SEARCH, CONVERSATION_READ,
    SHELL_EXECUTE, FILE_READ, FILE_WRITE, FILE_EDIT, FILE_GLOB, FILE_GREP,
    CLIPBOARD, TEXT_TO_SPEECH, SCREEN_TIME, CALENDAR_QUERY, CALENDAR_CREATE, TIME_INFO,
    EVAL_JAVASCRIPT, RUN_WORKFLOW, SUB_AGENT,
    UNKNOWN,
}

/** 工具展示结构化对象 */
data class ToolPresentation(
    val toolName: String,
    val kind: ToolKind,
    val state: ToolState,
    val subject: String?,
    val count: Int?,
    val errorMessage: String?,
    val exitCode: Int?,
)

object ToolPresentationResolver {

    /** 基于字面工具名映射种类（规避信封 type 的语义名/字面名不一致）。 */
    fun kindFor(toolName: String): ToolKind = when (toolName) {
        "search_web" -> ToolKind.WEB_SEARCH
        "scrape_web" -> ToolKind.WEB_FETCH
        "recent_chats" -> ToolKind.CONVERSATION_LIST
        "conversation_search" -> ToolKind.CONVERSATION_SEARCH
        "read_conversation" -> ToolKind.CONVERSATION_READ
        "workspace_shell" -> ToolKind.SHELL_EXECUTE
        "workspace_read_file" -> ToolKind.FILE_READ
        "workspace_write_file" -> ToolKind.FILE_WRITE
        "workspace_edit_file" -> ToolKind.FILE_EDIT
        "workspace_glob" -> ToolKind.FILE_GLOB
        "workspace_grep" -> ToolKind.FILE_GREP
        "clipboard_tool" -> ToolKind.CLIPBOARD
        "text_to_speech" -> ToolKind.TEXT_TO_SPEECH
        "get_screen_time" -> ToolKind.SCREEN_TIME
        "calendar_query" -> ToolKind.CALENDAR_QUERY
        "calendar_create" -> ToolKind.CALENDAR_CREATE
        "get_time_info" -> ToolKind.TIME_INFO
        "eval_javascript" -> ToolKind.EVAL_JAVASCRIPT
        "run_workflow" -> ToolKind.RUN_WORKFLOW
        "sub_agent" -> ToolKind.SUB_AGENT
        else -> ToolKind.UNKNOWN
    }

    fun resolve(tool: UIMessagePart.Tool): ToolPresentation {
        val kind = kindFor(tool.toolName)
        val envelope = parseEnvelope(tool.output)
        val errorMessage = envelope?.get("error")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
        val exitCode = (envelope?.get("exitCode") as? JsonPrimitive)?.intOrNull
        return ToolPresentation(
            toolName = tool.toolName,
            kind = kind,
            state = refineState(tool.toolState, kind, envelope, exitCode),
            subject = subjectFor(kind, tool, envelope),
            count = countFor(kind, envelope),
            errorMessage = errorMessage,
            exitCode = exitCode,
        )
    }

    /** toolState 为主，信封语义细化 EMPTY。 */
    private fun refineState(
        base: ToolState,
        kind: ToolKind,
        envelope: JsonObject?,
        exitCode: Int?,
    ): ToolState {
        if (base == ToolState.RUNNING || base == ToolState.CALLING) return base
        if (exitCode != null && exitCode != 0) return ToolState.FAILED
        val count = countFor(kind, envelope)
        if (kind != ToolKind.UNKNOWN && count != null && count == 0) return ToolState.EMPTY
        return base
    }

    private fun subjectFor(kind: ToolKind, tool: UIMessagePart.Tool, envelope: JsonObject?): String? {
        val args = tool.inputAsJson().jsonObjectOrNull()
        return when (kind) {
            ToolKind.WEB_SEARCH, ToolKind.CONVERSATION_SEARCH -> args?.string("query")
                ?: envelope?.string("query")
            ToolKind.WEB_FETCH -> args?.string("url") ?: envelope?.string("url")
            ToolKind.CONVERSATION_LIST -> envelope?.string("conversation_id")
            ToolKind.CONVERSATION_READ -> envelope?.string("title")
                ?: envelope?.string("conversation_id")
            ToolKind.SHELL_EXECUTE -> args?.string("command")
                ?.replace('\n', ' ')?.trim()
            ToolKind.FILE_READ, ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> envelope?.string("path")
            ToolKind.FILE_GLOB, ToolKind.FILE_GREP -> args?.string("pattern")
                ?: envelope?.string("pattern")
            ToolKind.CLIPBOARD, ToolKind.TEXT_TO_SPEECH, ToolKind.SCREEN_TIME,
            ToolKind.CALENDAR_QUERY, ToolKind.CALENDAR_CREATE, ToolKind.TIME_INFO,
            ToolKind.EVAL_JAVASCRIPT, ToolKind.RUN_WORKFLOW -> envelope?.string("path")
            ToolKind.SUB_AGENT -> envelope?.string("description")
                ?: args?.string("description")
            ToolKind.UNKNOWN -> null
        }?.take(120)
    }

    private fun countFor(kind: ToolKind, envelope: JsonObject?): Int? = when (kind) {
        ToolKind.WEB_SEARCH -> envelope?.arraySize("items")
        ToolKind.WEB_FETCH -> null
        ToolKind.CONVERSATION_LIST -> envelope?.arraySize("conversations")
        ToolKind.CONVERSATION_SEARCH -> envelope?.arraySize("results")
        ToolKind.CONVERSATION_READ -> envelope?.get("total_messages")?.let {
            (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }
        ToolKind.SHELL_EXECUTE -> null
        ToolKind.FILE_READ, ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> envelope?.get("sizeBytes")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.toInt() }
        ToolKind.SCREEN_TIME -> envelope?.arraySize("apps")
        ToolKind.CALENDAR_QUERY -> envelope?.arraySize("events")
        ToolKind.FILE_GLOB -> envelope?.arraySize("files")   // workspace_glob 信封发 "files"（非 "matches"）
        ToolKind.FILE_GREP -> envelope?.arraySize("matches")
        ToolKind.CLIPBOARD, ToolKind.TEXT_TO_SPEECH, ToolKind.CALENDAR_CREATE, ToolKind.TIME_INFO,
        ToolKind.EVAL_JAVASCRIPT, ToolKind.RUN_WORKFLOW, ToolKind.SUB_AGENT, ToolKind.UNKNOWN -> null
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.arraySize(key: String): Int? =
        (this?.get(key) as? kotlinx.serialization.json.JsonArray)?.size

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject
}

/** 一行概览：状态 + subject + count（对齐 Agora toolSummary）。 */
@Composable
fun toolSummary(p: ToolPresentation): String {
    val subject = p.subject?.let { s ->
        if (s.length > 60) s.take(60) + "…" else s
    }
    return when (p.state) {
        ToolState.CALLING -> stringResource(R.string.tool_summary_calling, p.toolName)
        ToolState.RUNNING -> stringResource(R.string.tool_summary_running, subject ?: p.toolName)
        ToolState.FAILED -> when {
            p.exitCode != null && p.exitCode != 0 ->
                stringResource(R.string.tool_summary_exit_failed, p.exitCode)
            p.errorMessage != null -> stringResource(R.string.tool_summary_error, p.errorMessage)
            else -> stringResource(R.string.tool_summary_failed)
        }
        ToolState.STOPPED -> stringResource(R.string.tool_summary_stopped)
        ToolState.EMPTY -> emptySummary(p, subject)
        ToolState.SUCCEEDED -> succeededSummary(p, subject)
    }
}

@Composable
private fun emptySummary(p: ToolPresentation, subject: String?): String = when (p.kind) {
    ToolKind.WEB_SEARCH -> stringResource(R.string.tool_summary_no_search_results, subject.orEmpty())
    ToolKind.CONVERSATION_SEARCH -> stringResource(R.string.tool_summary_no_conv_results, subject.orEmpty())
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_summary_no_conversations)
    ToolKind.FILE_READ -> stringResource(R.string.tool_summary_empty_file, subject ?: stringResource(R.string.tool_ui_file))
    ToolKind.SCREEN_TIME -> stringResource(R.string.tool_summary_no_screen_time)
    ToolKind.CALENDAR_QUERY -> stringResource(R.string.tool_summary_no_events)
    else -> stringResource(R.string.tool_summary_empty, subject ?: p.toolName)
}

@Composable
private fun succeededSummary(p: ToolPresentation, subject: String?): String = when (p.kind) {
    ToolKind.WEB_SEARCH -> p.count?.let {
        stringResource(R.string.tool_summary_search_done, it, subject.orEmpty())
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.CONVERSATION_SEARCH -> p.count?.let {
        stringResource(R.string.tool_summary_conv_done, it, subject.orEmpty())
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.CONVERSATION_LIST -> p.count?.let {
        stringResource(R.string.tool_summary_conv_count, it)
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.SHELL_EXECUTE -> p.exitCode?.let {
        stringResource(R.string.tool_summary_exit_ok, it)
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> stringResource(
        R.string.tool_summary_file_done, subject ?: p.toolName
    )
    else -> stringResource(R.string.tool_summary_done, subject ?: p.toolName)
}
