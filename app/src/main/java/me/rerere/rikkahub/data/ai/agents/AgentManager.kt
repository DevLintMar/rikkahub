package me.rerere.rikkahub.data.ai.agents

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillManager
import java.io.File

private const val TAG = "AgentManager"

class AgentManager(
    private val context: Context,
    private val skillManager: SkillManager,
) {
    companion object {
        val GENERAL_PURPOSE = AgentDefinition(
            name = "general-purpose",
            description = "General-purpose agent for researching complex questions, " +
                "searching for code, and executing multi-step tasks. " +
                "When you are searching for a keyword or file and are not confident " +
                "that you will find the right match in the first few tries use this " +
                "agent to perform the search for you.",
            tools = listOf("*"),
            disallowedTools = listOf(
                "workspace_write",
                "workspace_edit",
                "workspace_shell",
            ),
            mcpServers = null,
            skills = null,
            systemPrompt = "",
        )
    }

    private var cache: List<AgentDefinition>? = null

    fun getAgent(name: String): AgentDefinition? {
        return listAgents().find { it.name == name }
    }

    fun listAgents(): List<AgentDefinition> {
        if (cache == null) {
            cache = loadAllAgents()
        }
        return cache!!
    }

    fun invalidateCache() {
        cache = null
    }

    fun getAgentsDir(): File {
        val dir = File(context.filesDir, "agents")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadAllAgents(): List<AgentDefinition> {
        val agents = mutableMapOf<String, AgentDefinition>()

        // 0. 内置 general-purpose
        agents[GENERAL_PURPOSE.name] = GENERAL_PURPOSE

        // 1. 扫描 {filesDir}/agents/*.md
        val agentsDir = getAgentsDir()
        if (agentsDir.exists()) {
            agentsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.forEach { file ->
                    parseAgentFile(file)?.let { agent ->
                        agents[agent.name] = agent
                    }
                }
        }

        // 2. 扫描 {filesDir}/skills/*/agents/*.md
        val skillsDir = File(context.filesDir, FileFolders.SKILLS)
        if (skillsDir.exists()) {
            // 构建 skillDir -> skillName 映射
            val skillNameMap = mutableMapOf<String, String>()
            skillsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val skillFile = dir.resolve("SKILL.md")
                    if (skillFile.exists()) {
                        val content = skillFile.readText()
                        val frontmatter = parseFrontmatter(content)
                        frontmatter["name"]?.takeIf { it.isNotBlank() }?.let { name ->
                            skillNameMap[dir.absolutePath] = name
                        }
                    }
                }

            skillsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val agentsDir = dir.resolve("agents")
                    if (agentsDir.exists()) {
                        val skillName = skillNameMap[dir.absolutePath] ?: dir.name
                        agentsDir.listFiles()
                            ?.filter { it.isFile && it.name.endsWith(".md") }
                            ?.forEach { file ->
                                parseAgentFile(file)?.let { agent ->
                                    agents["$skillName:${agent.name}"] = agent.copy(
                                        name = "$skillName:${agent.name}"
                                    )
                                }
                            }
                    }
                }
        }

        return agents.values.toList()
    }

    private fun parseAgentFile(file: File): AgentDefinition? {
        return try {
            val content = file.readText()
            val frontmatter = parseFrontmatter(content)
            val body = extractBody(content)

            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null

            AgentDefinition(
                name = name,
                description = description,
                tools = parseStringList(frontmatter, "tools"),
                disallowedTools = parseStringList(frontmatter, "disallowedTools") ?: emptyList(),
                mcpServers = parseStringList(frontmatter, "mcpServers"),
                skills = parseStringList(frontmatter, "skills"),
                model = frontmatter["model"]?.takeIf { it.isNotBlank() },
                effort = frontmatter["effort"]?.takeIf { it.isNotBlank() },
                systemPrompt = body,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse agent file: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * 解析 YAML Frontmatter 为 key-value 映射。
     * 支持格式：
     * - key: value
     * - key: [a, b, c]
     * - key:\n  - item\n  - item
     * - key: |\n  text (多行)
     */
    private fun parseFrontmatter(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result

        val endIndex = findFrontmatterEnd(content) ?: return result
        val yamlBlock = content.substring(3, endIndex)

        val lines = yamlBlock.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.isBlank()) {
                i++
                continue
            }

            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) {
                i++
                continue
            }

            val key = line.substring(0, colonIdx).trim()
            val valueAfterColon = line.substring(colonIdx + 1).trim()

            when {
                // 内联数组: key: [a, b]
                valueAfterColon.startsWith("[") && valueAfterColon.endsWith("]") -> {
                    val inner = valueAfterColon.removeSurrounding("[", "]")
                    result[key] = inner
                }

                // 多行块标量: key: |
                valueAfterColon == "|" -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size) {
                        val continuation = lines[i]
                        if (continuation.isBlank() || !continuation.startsWith(" ")) {
                            i-- // push back for outer loop increment
                            break
                        }
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(continuation.trimStart())
                        i++
                    }
                    result[key] = sb.toString()
                }

                // 列表: key:\n  - item
                valueAfterColon.isEmpty() || valueAfterColon.startsWith("-") -> {
                    val items = mutableListOf<String>()
                    if (valueAfterColon.startsWith("-")) {
                        items.add(valueAfterColon.removePrefix("-").trim())
                    }
                    i++
                    while (i < lines.size) {
                        val continuation = lines[i].trimStart()
                        if (continuation.startsWith("- ")) {
                            items.add(continuation.removePrefix("- ").trim())
                            i++
                        } else if (continuation.isBlank()) {
                            i++
                            continue
                        } else {
                            i-- // push back
                            break
                        }
                    }
                    result[key] = items.joinToString(",") { it }
                }

                // 简单标量: key: value
                valueAfterColon.isNotBlank() -> {
                    result[key] = valueAfterColon.trimStart()
                }
            }
            i++
        }

        return result
    }

    private fun findFrontmatterEnd(content: String): Int? {
        val searchFrom = 3 // skip first "---"
        val idx = content.indexOf("\n---", searchFrom)
        if (idx < 0) return null
        return idx
    }

    private fun extractBody(content: String): String {
        val endIndex = findFrontmatterEnd(content) ?: return content
        val bodyStart = endIndex + "\n---".length
        return content.substring(bodyStart).trimStart('\n', '\r')
    }

    /** Parse a string list field from frontmatter. Supports both "[a, b]" and comma-separated values. */
    private fun parseStringList(
        frontmatter: Map<String, String>,
        key: String,
    ): List<String>? {
        val raw = frontmatter[key] ?: return null
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}
