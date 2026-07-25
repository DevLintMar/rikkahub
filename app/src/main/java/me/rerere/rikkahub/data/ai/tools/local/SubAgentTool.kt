package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agents.AgentDefinition
import me.rerere.rikkahub.data.ai.agents.AgentManager
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import kotlin.uuid.Uuid

internal fun buildSubAgentTool(
    runtime: SubAgentRuntime,
    availableTools: SubAgentToolContext = SubAgentToolContext(
        baseTools = emptyList(),
        mcpToolGroups = emptyList(),
        skillTool = null,
        subAgentTool = null,
        workflowTool = null,
    ),
    agentManager: AgentManager? = null,
    skillManager: SkillManager? = null,
    settingsStore: SettingsStore? = null,
    getConversationId: () -> Uuid = { error("sub_agent: conversationId not available") },
): Tool = Tool(
    name = "sub_agent",
    description = """
        Launch a new agent to handle complex, multi-step tasks.
        The agent runs independently with its own AI model instance.
        It has access to web search, time info, skills, and MCP tools.

        ## When to use
        Reach for this when the task matches an available agent type, when you
        have independent work to run in parallel, or when answering would mean
        reading across several files — delegate it and you keep the conclusion,
        not the file dumps. For a single-fact lookup where you already know the
        file, symbol, or value, search directly. Once you've delegated a search,
        don't also run it yourself — wait for the result.

        - The agent's final report is not shown to the user — relay what matters.
        - Subagents run in the background by default; you'll be notified when one
          completes. Pass run_in_background: false for a synchronous run when you
          need the result before continuing. Never fabricate or predict a pending
          agent's results — the notification is never something you write yourself;
          if the user asks before it arrives, say it's still running.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("description", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "A short (3-5 word) description of the task",
                    )
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "The task for the agent to perform")
                })
                put("model", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional model UUID override for this agent. Uses the current model if not specified.",
                    )
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Agents run in the background by default; you will be notified when one completes. Set to false to run this agent synchronously when you need its result before continuing.",
                    )
                })
                put("subagent_type", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "The type of specialized agent to use for this task",
                    )
                })
            },
            required = listOf("description", "prompt"),
        )
    },
    systemPrompt = { _, _ ->
        if (agentManager != null) {
            val agents = agentManager.listAgents()
            buildString {
                appendLine("Available agent types for the Agent tool:")
                agents.forEach { agent ->
                    append("- ${agent.name}: ${agent.description}")
                    val parts = mutableListOf<String>()
                    if (agent.tools != null) {
                        parts.add(
                            "Tools: ${
                                if (agent.tools.size == 1 && agent.tools[0] == "*") {
                                    "*"
                                } else {
                                    agent.tools.joinToString(", ")
                                }
                            }",
                        )
                    }
                    if (agent.skills != null && agent.skills.isNotEmpty()) {
                        parts.add("Skills: ${agent.skills.joinToString(", ")}")
                    }
                    if (agent.mcpServers != null && agent.mcpServers.isNotEmpty()) {
                        parts.add("McpServers: ${agent.mcpServers.joinToString(", ")}")
                    }
                    if (parts.isNotEmpty()) {
                        append(" (${parts.joinToString("; ")})")
                    }
                    appendLine()
                }
            }
        } else ""
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'prompt' parameter is required")
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'description' parameter is required")

        val runInBackground = obj["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: true
        val modelOverride = obj["model"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        val subagentType = obj["subagent_type"]?.jsonPrimitive?.contentOrNull

        val (effectivePrompt, effectiveTools, systemPrompt) = if (subagentType != null) {
            if (agentManager == null || skillManager == null) {
                error("sub_agent: AgentManager is not available. Cannot resolve agent type.")
            }
            val agentDef = agentManager.getAgent(subagentType)
                ?: error(
                    "sub_agent: agent type '$subagentType' not found. " +
                        "Available: ${agentManager.listAgents().joinToString { it.name }}",
                )
            val filtered = filterToolsByAgent(agentDef, availableTools, skillManager)
            val combinedPrompt = if (agentDef.systemPrompt.isNotBlank()) {
                "${agentDef.systemPrompt}\n\n$prompt"
            } else {
                prompt
            }
            Triple(combinedPrompt, filtered, null)
        } else {
            Triple(prompt, buildFullToolList(availableTools), null)
        }

        if (runInBackground) {
            val conversationId = getConversationId()
            val handle = runtime.executeAsync(
                prompt = effectivePrompt,
                description = description,
                conversationId = conversationId,
                modelOverride = modelOverride,
                tools = effectiveTools,
                systemPrompt = systemPrompt,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive("started"))
                put("task_id", JsonPrimitive(handle.taskId))
                put("description", JsonPrimitive(description))
                put("mode", JsonPrimitive("background"))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            val result = runtime.executeSync(
                prompt = effectivePrompt,
                modelOverride = modelOverride,
                tools = effectiveTools,
                systemPrompt = systemPrompt,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive(if (result.success) "completed" else "failed"))
                put("description", JsonPrimitive(description))
                put("mode", JsonPrimitive("synchronous"))
                if (result.success) {
                    put("result", JsonPrimitive(result.text))
                } else {
                    put("error", JsonPrimitive(result.error ?: "Unknown error"))
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    },
)

/**
 * 需要显式声明才能使用的工具。
 * 即使 `*` 通配符也不会包含这些工具，agent 必须在 `tools` 中写明具体名称才可用。
 */
private val RESTRICTED_TOOLS = setOf(
    "ask_user",
    "clipboard",
    "recent_chats",
    "conversation_search",
    "sub_agent",
    "run_workflow",
)

/**
 * 根据 AgentDefinition 的 frontmatter 规则筛选子代理可用的工具。
 *
 * 规则：
 * - baseTools 受 `tools`（白名单）和 `disallowedTools`（黑名单）控制
 * - MCP 工具受 `mcpServers` 控制
 * - Skill 工具受 `skills` 控制
 * - 某些工具（ask_user、clipboard、recent_chats、conversation_search、
 *   sub_agent、run_workflow）即使是 `*` 通配符也不会自动包含，
 *   必须在 `tools` 中显式写明才可用。
 */
private fun filterToolsByAgent(
    def: AgentDefinition,
    ctx: SubAgentToolContext,
    skillManager: SkillManager,
): List<Tool> {
    val result = mutableListOf<Tool>()

    // 1) 基础工具（受 tools / disallowedTools 控制）
    val baseToolNames = def.tools
    if (baseToolNames != null) {
        if (baseToolNames.size == 1 && baseToolNames[0] == "*") {
            // * 通配符 = 全部基础工具（排除受限工具）
            result.addAll(ctx.baseTools.filter { it.name !in RESTRICTED_TOOLS })
        } else {
            // 显式白名单：即使包含受限工具也允许
            ctx.baseTools.filter { it.name in baseToolNames }.let { result.addAll(it) }
        }
    } else {
        // 继承全部（排除受限工具）
        result.addAll(ctx.baseTools.filter { it.name !in RESTRICTED_TOOLS })
    }
    // 应用黑名单
    if (def.disallowedTools.isNotEmpty()) {
        result.removeAll { it.name in def.disallowedTools }
    }

    // 2) MCP 工具（受 mcpServers 控制）
    if (def.mcpServers != null) {
        // 白名单：只加入指定 server 下的所有工具
        ctx.mcpToolGroups
            .filter { it.serverName in def.mcpServers }
            .flatMap { it.tools }
            .let { result.addAll(it) }
    } else {
        // 继承：加入所有 MCP 工具
        ctx.mcpToolGroups.flatMap { it.tools }.let { result.addAll(it) }
    }

    // 3) Skill 工具（受 skills 控制）
    if (def.skills != null) {
        if (def.skills.isNotEmpty()) {
            val allSkills = skillManager.listSkills()
            val filteredSkills = allSkills.filter { it.name in def.skills }
            result.add(createFilteredSkillTool(filteredSkills.map { it.name }))
        }
        // skills = [] → 不加 skill 工具
    } else {
        // 继承主 agent 的 skill 工具
        ctx.skillTool?.let { result.add(it) }
    }

    return result
}

/**
 * 创建一个只列出指定 skill 的 use_skill 工具。
 * 适用于子代理配置了 skills 白名单的场景。
 */
private fun createFilteredSkillTool(skillNames: List<String>): Tool = Tool(
    name = "use_skill",
    description = """
        Load and apply a skill to get specialized instructions or capabilities.
        Call this tool when the user's request matches one of the available skills.
    """.trimIndent(),
    systemPrompt = { _, _ ->
        buildString {
            appendLine("**Skills**")
            appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
            appendLine("<available_skills>")
            skillNames.forEach { name ->
                appendLine("  <skill>")
                appendLine("    <name>$name</name>")
                appendLine("    <description>$name</description>")
                appendLine("  </skill>")
            }
            append("</available_skills>")
            appendLine()
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "The name of the skill to use")
                })
            },
            required = listOf("name"),
        )
    },
    execute = {
        // 子代理的 filtered skill tool 不需要真正执行 use_skill
        // 因为 skill 内容在 systemPrompt 中已经注入了
        listOf(UIMessagePart.Text("Skill loaded."))
    },
)

/**
 * 向后兼容：不传 subagent_type 时构建子代理工具列表。
 * 保持与原有行为一致：搜索 + 时间 + Skill + MCP，
 * 不包含本地工具（js/clipboard/tts/askUser/calendar/screenTime/subAgent/workflow），
 * 不包含对话工具、工作区工具。
 */
private fun buildFullToolList(ctx: SubAgentToolContext): List<Tool> {
    val result = mutableListOf<Tool>()
    // 仅搜索 + 时间（与原 subAgentTools 一致）
    ctx.baseTools.filter { tool ->
        tool.name == "search_web" || tool.name == "scrape_web" || tool.name == "time_info"
    }.let { result.addAll(it) }
    // MCP 工具
    result.addAll(ctx.mcpToolGroups.flatMap { it.tools })
    // Skill 工具
    ctx.skillTool?.let { result.add(it) }
    return result
}
