package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.agents.AgentDefinition
import me.rerere.rikkahub.data.ai.agents.AgentManager

class SubAgentsVM(
    private val agentManager: AgentManager,
) : ViewModel() {
    private val _agents = MutableStateFlow<List<AgentDefinition>>(emptyList())
    val agents = _agents.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            agentManager.invalidateCache()
            _agents.value = agentManager.listAgents()
        }
    }

    fun deleteAgent(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val agentsDir = agentManager.getAgentsDir()
            val file = java.io.File(agentsDir, "$name.md")
            if (file.exists()) {
                file.delete()
            }
            agentManager.invalidateCache()
            _agents.value = agentManager.listAgents()
        }
    }

    fun refresh() {
        loadAgents()
    }
}
