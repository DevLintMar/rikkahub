package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.McpToolGroup
import me.rerere.rikkahub.data.ai.tools.local.SubAgentToolContext
import me.rerere.rikkahub.data.ai.tools.local.buildSubAgentTool
import me.rerere.rikkahub.data.ai.tools.local.buildWorkflowTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/**
 * Creates the complete tool set for one generation run, including approval resumption.
 *
 * The fork's tool set is assembled here (moved out of ChatService). Besides the flat list handed
 * to the main agent, this also builds the [SubAgentToolContext] that the `sub_agent` tool filters
 * when deciding what a child agent may inherit.
 */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        // ---- MCP 工具按 server 分组（同时用于子代理上下文与主工具列表） ----
        val rawMcpTools = mcpManager.getAllAvailableTools()
        val invalidMcpNames = rawMcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidMcpNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidMcpNames)
        }
        val mcpToolGroups: List<McpToolGroup> = rawMcpTools
            .groupBy { it.second }
            .map { (serverName, triples) ->
                McpToolGroup(
                    serverName = serverName,
                    tools = triples.map { (serverId, _, tool) ->
                        Tool(
                            name = "mcp__${serverName}__${tool.name}",
                            description = tool.description ?: "",
                            parameters = { tool.inputSchema },
                            needsApproval = { tool.needsApproval },
                            execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                        )
                    },
                )
            }

        // Skill 工具（独立持有，不放入 baseTools）
        val skillTool: Tool? = if (assistant.enabledSkills.isNotEmpty()) {
            createSkillTools(
                enabledSkills = assistant.enabledSkills,
                allSkills = skillManager.listSkills(),
            ).singleOrNull()
        } else null

        // ---- 子代理可继承的工具上下文（搜索 + 本地 + 对话 + 工作区 + read_image） ----
        val baseTools = buildList {
            if (useExternalWebSearch) {
                addAll(createSearchTools(settings))
            }

            // 本地工具
            addAll(localTools.getTools(assistant.localTools))

            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, folderRepository, assistant.id, conversationId))
            }

            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
            // workspaceId 必须传：/upload 附件不需要它，但工作区内的 Rootfs 路径（/workspace、/tmp 等）需要
            addAll(createReadImageTool(workspaceId = assistant.workspaceId?.toString()))
        }

        // 构建 SubAgentToolContext
        val toolContext = SubAgentToolContext(
            baseTools = baseTools,
            mcpToolGroups = mcpToolGroups,
            skillTool = skillTool,
            subAgentTool = null, // 暂不递归传入（避免自身引用循环）
            workflowTool = null,
        )

        // ---- 构建主 agent 的扁平工具列表 ----
        return buildList {
            // 记忆工具（fork：按门槛决定包含哪些；见 buildMemoryTools）
            if (assistant.enableMemory) {
                val memoryAssistantId = if (assistant.useGlobalMemory) {
                    MemoryRepository.GLOBAL_MEMORY_ID
                } else {
                    assistant.id.toString()
                }
                addAll(
                    buildMemoryTools(
                        json = json,
                        memoryAssistantId = memoryAssistantId,
                        readMemoryByTitle = { title ->
                            memoryRepository.getMemoryByTitle(memoryAssistantId, title)
                        },
                        readActiveMemoryByTitle = { title ->
                            memoryRepository.getActiveMemoryByTitle(memoryAssistantId, title)
                        },
                        writeMemory = { title, description, content, overwrite ->
                            memoryRepository.addMemory(
                                assistantId = memoryAssistantId,
                                title = title,
                                description = description,
                                content = content,
                                overwrite = overwrite,
                                isActive = false,
                            )
                        },
                        editMemory = { title, newTitle, description, content, oldText, newText, replaceAll ->
                            memoryRepository.editMemoryByTitle(
                                assistantId = memoryAssistantId,
                                title = title,
                                newTitle = newTitle,
                                description = description,
                                content = content,
                                oldText = oldText,
                                newText = newText,
                                replaceAll = replaceAll,
                                isActive = false,
                            )
                        },
                        deleteMemoryByTitle = { title ->
                            memoryRepository.deleteMemoryByTitle(memoryAssistantId, title, isActive = false)
                        },
                        createActiveMemory = { title, description, content, overwrite ->
                            memoryRepository.addMemory(
                                assistantId = memoryAssistantId,
                                title = title,
                                description = description,
                                content = content,
                                overwrite = overwrite,
                                isActive = true,
                            )
                        },
                        editActiveMemory = { title, newTitle, description, content, oldText, newText, replaceAll ->
                            memoryRepository.editMemoryByTitle(
                                assistantId = memoryAssistantId,
                                title = title,
                                newTitle = newTitle,
                                description = description,
                                content = content,
                                oldText = oldText,
                                newText = newText,
                                replaceAll = replaceAll,
                                isActive = true,
                            )
                        },
                        deleteActiveMemoryByTitle = { title ->
                            memoryRepository.deleteMemoryByTitle(memoryAssistantId, title, isActive = true)
                        },
                        includeActiveEdit = assistant.enableEditActiveMemory,
                        includeSavedEdit = assistant.enableEditSavedMemories,
                    )
                )
            }

            // 搜索工具
            if (useExternalWebSearch) {
                addAll(createSearchTools(settings))
            }

            // 本地工具（sub_agent + workflow 用新上下文包装）
            addAll(localTools.getTools(assistant.localTools).map { tool ->
                when (tool.name) {
                    "sub_agent" -> {
                        buildSubAgentTool(
                            runtime = localTools.subAgentRuntime,
                            availableTools = toolContext,
                            agentManager = localTools.agentManager,
                            skillManager = skillManager,
                            settingsStore = settingsStore,
                            getConversationId = { conversationId },
                            needsApproval = { !assistant.enableSubAgentAutoApproval },
                        )
                    }

                    "run_workflow" -> {
                        buildWorkflowTool(localTools.workflowEngine) { conversationId }
                    }

                    else -> tool
                }
            })

            // 对话工具（含文件夹）
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, folderRepository, assistant.id, conversationId))
            }

            // 工作区工具
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))

            // 图片懒加载读取工具（恒注册：/upload 全局可解析；工作区路径需 workspaceId）
            addAll(createReadImageTool(workspaceId = assistant.workspaceId?.toString()))

            // Skill 工具
            skillTool?.let { add(it) }

            // MCP 工具
            mcpToolGroups.flatMap { it.tools }.forEach { add(it) }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
