package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolOutput
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read" to false,
    "workspace_write" to false,
    "workspace_edit" to false,
    "workspace_shell" to true,
    "workspace_glob" to false,
    "workspace_grep" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createGlobTool(workspaceId, ::needsApproval, workspaceRepository),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
        For large files, read a byte range with offset (default 0) and limit (default 0 = read to end); the result includes totalChars and hasMore so you can page through.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "字节偏移，用于分段读大文件 (default 0)")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "最大读取字节数 (default 0 = 读到文件尾)")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val offset = it.jsonObject["offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val limit = it.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val totalChars = workspaceRepository.rootfsFileSize(workspaceId, path)
            if (offset >= totalChars && totalChars > 0) {
                error("offset $offset is beyond end of file ($totalChars bytes)")
            }
            // limit<=0 表示读到文件尾: exportRootfsFileRange 会把整个文件缓冲进内存
            // (100KB 截断安全网在之后才生效), 需在缓冲前先做大小守卫, 防止大文件 OOM
            if (limit <= 0 && totalChars > MAX_READ_FILE_BYTES) {
                error("File is too large to read: $path (${totalChars / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it.")
            }
            val buffer = ByteArrayOutputStream()
            workspaceRepository.exportRootfsFileRange(workspaceId, path, offset, limit, buffer)
            val text = buffer.toString(Charsets.UTF_8.name())
            val hasMore = limit > 0 && offset + limit < totalChars
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("type", JsonPrimitive("workspace_read"))
                        put("path", JsonPrimitive(path))
                        put("text", JsonPrimitive(text))
                        put("offset", JsonPrimitive(offset))
                        put("limit", JsonPrimitive(limit))
                        put("totalChars", JsonPrimitive(totalChars))
                        put("hasMore", JsonPrimitive(hasMore))
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", "workspace_write")
                    put("path", entry.path)
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString()
            )
        )
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("type", "workspace_edit")
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = { params ->
        val result = workspaceRepository.executeCommand(
            workspaceId,
            shellCommand(params.jsonObject),
            shellCwd(params.jsonObject, defaultCwd),
            shellTimeoutMillis(params.jsonObject),
        )
        listOf(UIMessagePart.Text(shellEnvelope(result)))
    },
    executeFlow = { params ->
        channelFlow {
            // channelFlow 的 ProducerScope 本身就是 SendChannel; shell 收集线程(非协程)
            // 在命令执行期间用 trySend 实时投递 OutputDelta, 由收集方边执行边消费,
            // 不再等 executeCommandStreaming 返回后批量转发
            val producer = this
            val deferred = async(Dispatchers.IO) {
                workspaceRepository.executeCommandStreaming(
                    id = workspaceId,
                    command = shellCommand(params.jsonObject),
                    cwd = shellCwd(params.jsonObject, defaultCwd),
                    timeoutMillis = shellTimeoutMillis(params.jsonObject),
                ) { line -> producer.trySend(ToolOutput.OutputDelta(line)) }
            }
            val result = deferred.await()
            // 命令返回时全部 OutputDelta 已按 FIFO 序入 channel, Completed 必然在其后。
            // 用 suspend send() 保证 Completed 必然送达: channel 默认容量只有 64,
            // 高输出量时 trySend 会因缓冲满而静默丢包, 导致成功命令被判为 FAILED;
            // send() 在缓冲满时挂起等待收集方腾出空间, 收集方持续消费所以不会死锁。
            producer.send(ToolOutput.Completed(listOf(UIMessagePart.Text(shellEnvelope(result)))))
            close()
        }
    },
)

private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        List files matching a glob pattern in the assistant's bound workspace Rootfs /workspace area.
        The pattern matches paths relative to the base directory; '*.go' matches top-level only, use '**/*.go' to recurse.
        'path' is an optional base directory relative to /workspace. Returns path, name, isDirectory, sizeBytes and updatedAt for each file.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern matching paths; '*.go' matches top-level only, use '**/*.go' to recurse")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional base directory relative to /workspace. Omit for the workspace root.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val pattern = it.jsonObject.string("pattern") ?: error("pattern is required")
        val path = it.jsonObject.string("path").orEmpty()
        val files = workspaceRepository.glob(workspaceId, pattern, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("workspace_glob"))
                    put("pattern", JsonPrimitive(pattern))
                    putJsonArray("files") {
                        files.forEach { f ->
                            add(buildJsonObject {
                                put("path", JsonPrimitive(f.path))
                                put("name", JsonPrimitive(f.name))
                                put("isDirectory", JsonPrimitive(f.isDirectory))
                                put("sizeBytes", JsonPrimitive(f.sizeBytes))
                                put("updatedAt", JsonPrimitive(f.updatedAt))
                            })
                        }
                    }
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search for text or regex in files under the assistant's bound workspace Rootfs /workspace area.
        Set 'regex'=true to treat the pattern as a regular expression (default is literal match).
        'path' is an optional base directory relative to /workspace; 'glob' is an optional file-name filter.
        Returns matching lines with file path, line number and content.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to search for, or a regular expression when regex=true")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional base directory relative to /workspace. Omit to search the workspace root.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to treat the pattern as a regular expression (default false = literal match)")
                })
                put("glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file-name glob filter (e.g. '*.kt')")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val pattern = it.jsonObject.string("pattern") ?: error("pattern is required")
        val path = it.jsonObject.string("path").orEmpty()
        val regex = it.jsonObject["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val glob = it.jsonObject.string("glob")
        val matches = workspaceRepository.grep(
            id = workspaceId, query = pattern, path = path, regex = regex,
            ignoreCase = true, includeGlob = glob,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("workspace_grep"))
                    put("pattern", JsonPrimitive(pattern))
                    putJsonArray("matches") {
                        matches.forEach { m ->
                            add(buildJsonObject {
                                put("path", JsonPrimitive(m.path))
                                put("line", JsonPrimitive(m.line))
                                put("text", JsonPrimitive(m.text))
                            })
                        }
                    }
                }.toString()
            )
        )
    },
)

private fun shellCommand(params: JsonObject): String =
    params.string("command") ?: error("command is required")

private fun shellCwd(params: JsonObject, defaultCwd: String?): String =
    (params.string("cwd") ?: defaultCwd.orEmpty())
        .removePrefix("/workspace/").removePrefix("/workspace")

private fun shellTimeoutMillis(params: JsonObject): Long =
    params.string("timeout")?.toLongOrNull()
        ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
        ?.times(1_000L)
        ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS

private fun shellEnvelope(result: WorkspaceCommandResult): String = buildJsonObject {
    put("type", "workspace_shell")
    put("exitCode", result.exitCode)
    put("stdout", result.stdout)
    put("stderr", result.stderr)
    put("timedOut", result.timedOut)
    put("totalChars", result.stdout.length + result.stderr.length)
    if (result.truncated) put("truncated", true)
}.toString()

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("type", "workspace_read")
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录, 以及临时目录 /tmp
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}
