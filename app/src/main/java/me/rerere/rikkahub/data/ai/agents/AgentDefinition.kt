package me.rerere.rikkahub.data.ai.agents

data class AgentDefinition(
    val name: String,
    val description: String,
    val tools: List<String>?, // null = 继承主 agent 全部基础工具
    val disallowedTools: List<String> = emptyList(),
    val mcpServers: List<String>?, // null = 继承主 agent 全部 MCP 工具
    val skills: List<String>?, // null = 继承主 agent 全部 skill
    val model: String? = null, // 占位，始终继承主模型
    val effort: String? = null,
    val systemPrompt: String, // body 部分（--- 之后的内容）
)
