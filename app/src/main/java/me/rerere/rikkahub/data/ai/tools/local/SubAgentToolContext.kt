package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.Tool

/**
 * 按类别分组的工具上下文，用于 sub_agent 工具筛选。
 *
 * - baseTools: 受 `tools` / `disallowedTools` 控制
 * - mcpToolGroups: 受 `mcpServers` 控制
 * - skillTool: 受 `skills` 控制
 */
data class SubAgentToolContext(
    val baseTools: List<Tool>,
    val mcpToolGroups: List<McpToolGroup>,
    val skillTool: Tool?,
    val subAgentTool: Tool?,
    val workflowTool: Tool?,
)

data class McpToolGroup(
    val serverName: String,
    val tools: List<Tool>,
)
