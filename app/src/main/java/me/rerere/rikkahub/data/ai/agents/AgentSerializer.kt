package me.rerere.rikkahub.data.ai.agents

import java.io.File

/**
 * AgentDefinition ↔ .md 文件双向转换。
 */
object AgentSerializer {

    fun serialize(agent: AgentDefinition): String = buildString {
        appendLine("---")
        appendLine("name: ${agent.name}")
        appendLine("description: ${agent.description}")
        agent.tools?.let { appendLine("tools: ${formatList(it)}") }
        if (agent.disallowedTools.isNotEmpty() || agent.tools != null) {
            appendLine("disallowedTools: ${formatList(agent.disallowedTools)}")
        }
        agent.mcpServers?.let { appendLine("mcpServers: ${formatList(it)}") }
        agent.skills?.let { appendLine("skills: ${formatList(it)}") }
        agent.model?.let { appendLine("model: $it") }
        agent.effort?.let { appendLine("effort: $it") }
        appendLine("---")
        append(agent.systemPrompt)
    }

    fun serializeToFile(agent: AgentDefinition, file: File) {
        file.writeText(serialize(agent))
    }

    fun deserialize(content: String): AgentDefinition? {
        val frontmatter = parseFrontmatter(content)
        val body = extractBody(content)

        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null

        return AgentDefinition(
            name = name,
            description = description,
            tools = parseListField(frontmatter, "tools"),
            disallowedTools = parseListField(frontmatter, "disallowedTools") ?: emptyList(),
            mcpServers = parseListField(frontmatter, "mcpServers"),
            skills = parseListField(frontmatter, "skills"),
            model = frontmatter["model"]?.takeIf { it.isNotBlank() },
            effort = frontmatter["effort"]?.takeIf { it.isNotBlank() },
            systemPrompt = body,
        )
    }

    fun deserializeFromFile(file: File): AgentDefinition? {
        return try {
            deserialize(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun formatList(items: List<String>): String {
        if (items.isEmpty()) return "[]"
        return "[${items.joinToString(", ")}]"
    }

    private fun parseListField(fm: Map<String, String>, key: String): List<String>? {
        val raw = fm[key] ?: return null
        if (raw.isBlank() || raw == "null") return null
        // [a, b, c] or a, b
        val stripped = raw.removeSurrounding("[", "]")
        val items = stripped.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return items.ifEmpty { emptyList() }
    }

    private fun parseFrontmatter(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endIndex = content.indexOf("\n---", startIndex = 3) ?: return result
        val block = content.substring(3, endIndex)
        val lines = block.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }
            val key = line.substring(0, colonIdx).trim()
            val valueAfter = line.substring(colonIdx + 1).trim()
            when {
                valueAfter == "|" -> {
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size) {
                        val cl = lines[i]
                        if (!cl.startsWith(" ")) { i--; break }
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(cl.trimStart())
                        i++
                    }
                    result[key] = sb.toString()
                }
                else -> {
                    result[key] = valueAfter
                }
            }
            i++
        }
        return result
    }

    private fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endIndex = content.indexOf("\n---", startIndex = 3) ?: return content
        return content.substring(endIndex + 4).trimStart('\n', '\r')
    }
}
