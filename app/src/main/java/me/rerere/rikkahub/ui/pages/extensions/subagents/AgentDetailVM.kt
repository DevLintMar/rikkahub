package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.agents.AgentDefinition
import me.rerere.rikkahub.data.ai.agents.AgentManager
import me.rerere.rikkahub.data.ai.agents.AgentSerializer
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

class AgentDetailVM(
    private val agentManager: AgentManager,
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
) : ViewModel() {
    // Available options
    val availableMcpServers = MutableStateFlow<List<String>>(emptyList())
    val availableSkills = MutableStateFlow<List<String>>(emptyList())

    // Current editing agent
    private val _agent = MutableStateFlow<AgentDefinition?>(null)
    val agent = _agent.asStateFlow()

    fun load(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Load available MCP servers and skills
            val settings = settingsStore.settingsFlow.first()
            availableMcpServers.value = settings.mcpServers
                .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                .map { it.commonOptions.name }
            availableSkills.value = skillManager.listSkills().map { it.name }

            // Load agent if editing existing
            if (name.isNotBlank()) {
                _agent.value = agentManager.getAgent(name)
            }
        }
    }

    fun save(name: String, description: String, tools: List<String>?,
             disallowedTools: List<String>, mcpServers: List<String>?,
             skills: List<String>?, effort: String?, systemPrompt: String,
             onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val agent = AgentDefinition(
                    name = name,
                    description = description,
                    tools = tools,
                    disallowedTools = disallowedTools,
                    mcpServers = mcpServers,
                    skills = skills,
                    model = null,
                    effort = effort,
                    systemPrompt = systemPrompt,
                )
                val dir = agentManager.getAgentsDir()
                val file = File(dir, "$name.md")
                AgentSerializer.serializeToFile(agent, file)
                agentManager.invalidateCache()
                withContext(Dispatchers.Main) {
                    onResult(true, name)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Unknown error")
                }
            }
        }
    }
}
