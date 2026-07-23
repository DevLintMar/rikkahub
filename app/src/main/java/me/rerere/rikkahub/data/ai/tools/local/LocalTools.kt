package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager
import kotlin.uuid.Uuid

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val appScope: AppScope,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    // Sub-agent & Workflow
    val subAgentRuntime by lazy {
        SubAgentRuntime(providerManager, settingsStore, appScope, eventBus)
    }
    val workflowEngine by lazy { WorkflowEngine(subAgentRuntime) }

    val subAgentTool by lazy { buildSubAgentTool(subAgentRuntime) }
    val taskListTool by lazy { buildTaskListTool(subAgentRuntime) }
    val taskGetTool by lazy { buildTaskGetTool(subAgentRuntime) }
    val workflowTool by lazy { buildWorkflowTool(workflowEngine) }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.SubAgent)) {
            tools.add(subAgentTool)
            tools.add(taskListTool)
            tools.add(taskGetTool)
        }
        if (options.contains(LocalToolOption.Workflow)) {
            tools.add(workflowTool)
        }
        return tools
    }
}
