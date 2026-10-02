package me.rerere.rikkahub.data.ai.bridge

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "WorkspaceLlmBridge"
const val DEFAULT_WORKSPACE_BRIDGE_PORT = 28888

data class ActiveBridgeSession(
    val providerImpl: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val model: Model,
    val assistant: Assistant? = null,
)

class WorkspaceLlmBridgeServer(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isRunning = AtomicBoolean(false)
    private val currentSession = AtomicReference<ActiveBridgeSession?>(null)

    fun setActiveSession(
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        model: Model,
        assistant: Assistant? = null,
    ) {
        currentSession.set(
            ActiveBridgeSession(
                providerImpl = providerImpl,
                providerSetting = providerSetting,
                model = model,
                assistant = assistant,
            )
        )
        // 自动将当前活跃模型与推理参数注入到沙箱环境变量中
        me.rerere.workspace.ProotShellRunner.extraEnvProvider = {
            buildMap {
                put("LLM_MODEL", model.modelId)
                val effort = assistant?.reasoningLevel?.effort ?: "auto"
                put("LLM_REASONING_EFFORT", effort)
            }
        }
        Log.i(TAG, "Active bridge session explicitly updated for model: ${model.modelId}")
        ensureStarted()
    }

    /**
     * 获取当前活跃会话。若尚未由对话显式激活，自动根据用户当前设置兜底解析默认助手与模型。
     */
    fun resolveActiveSession(): ActiveBridgeSession? {
        val explicit = currentSession.get()
        if (explicit != null) return explicit

        return try {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.find { it.id == settings.assistantId }
                ?: settings.assistants.firstOrNull()

            val targetModelId = assistant?.chatModelId ?: settings.chatModelId
            val allModels = settings.providers.flatMap { it.models }
            val model = allModels.find { it.id == targetModelId }
                ?: allModels.firstOrNull()
                ?: return null

            val providerSetting = model.findProvider(settings.providers) ?: return null
            @Suppress("UNCHECKED_CAST")
            val providerImpl = providerManager.getProviderByType(providerSetting) as Provider<ProviderSetting>

            val session = ActiveBridgeSession(
                providerImpl = providerImpl,
                providerSetting = providerSetting,
                model = model,
                assistant = assistant,
            )

            // 同步沙箱环境配置
            me.rerere.workspace.ProotShellRunner.extraEnvProvider = {
                buildMap {
                    put("LLM_MODEL", session.model.modelId)
                    val effort = session.assistant?.reasoningLevel?.effort ?: "auto"
                    put("LLM_REASONING_EFFORT", effort)
                }
            }

            session
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve fallback active session from settings", e)
            null
        }
    }

    @Synchronized
    fun ensureStarted(port: Int = DEFAULT_WORKSPACE_BRIDGE_PORT) {
        if (isRunning.get() && server != null) return

        try {
            server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                routing {
                    get("/health") {
                        val session = resolveActiveSession()
                        val status = if (session != null) {
                            "OK (active_model=${session.model.modelId}, effort=${session.assistant?.reasoningLevel?.effort ?: "auto"})"
                        } else {
                            "OK (idle)"
                        }
                        call.respondText(status, ContentType.Text.Plain)
                    }

                    get("/v1/models") {
                        val session = resolveActiveSession()
                        val modelName = session?.model?.modelId ?: "rikkahub-active-model"
                        val json = """
                            {
                              "object": "list",
                              "data": [
                                {
                                  "id": "$modelName",
                                  "object": "model",
                                  "created": 1700000000,
                                  "owned_by": "rikkahub"
                                }
                              ]
                            }
                        """.trimIndent()
                        call.respondText(json, ContentType.Application.Json)
                    }

                    post("/v1/chat/completions") {
                        val session = resolveActiveSession()
                        if (session == null) {
                            val errJson = """
                                {
                                  "error": {
                                    "message": "No active conversation or configured model found in RikkaHub. Please configure a provider model first.",
                                    "type": "server_error"
                                  }
                                }
                            """.trimIndent()
                            call.respondText(errJson, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                            return@post
                        }

                        val requestBody = call.receiveText()
                        try {
                            val openAiReq = OpenAiProtocolAdapter.parseRequest(requestBody)
                            val uiMessages = OpenAiProtocolAdapter.toUIMessages(openAiReq.messages)
                            val tools = OpenAiProtocolAdapter.toTools(openAiReq.tools)

                            val assistant = session.assistant
                            val params = TextGenerationParams(
                                model = session.model,
                                temperature = openAiReq.temperature ?: assistant?.temperature,
                                topP = openAiReq.topP ?: assistant?.topP,
                                maxTokens = openAiReq.maxCompletionTokens
                                    ?: openAiReq.maxTokens
                                    ?: assistant?.maxTokens,
                                tools = tools,
                                reasoningLevel = OpenAiProtocolAdapter.parseReasoningEffort(openAiReq.reasoningEffort)
                                    ?: assistant?.reasoningLevel
                                    ?: ReasoningLevel.AUTO,
                                customHeaders = buildList {
                                    assistant?.customHeaders?.let { addAll(it) }
                                    addAll(session.model.customHeaders)
                                },
                                customBody = buildList {
                                    assistant?.customBodies?.let { addAll(it) }
                                    addAll(session.model.customBodies)
                                },
                            )

                            val result = withContext(Dispatchers.IO) {
                                session.providerImpl.generateText(
                                    providerSetting = session.providerSetting,
                                    messages = uiMessages,
                                    params = params,
                                )
                            }

                            val openAiResp = OpenAiProtocolAdapter.toOpenAiResponse(
                                result = result,
                                requestedModel = session.model.modelId,
                            )

                            val respJson = OpenAiProtocolAdapter.serializeResponse(openAiResp)
                            call.respondText(respJson, ContentType.Application.Json, HttpStatusCode.OK)
                        } catch (e: Exception) {
                            Log.e(TAG, "Bridge request execution failed", e)
                            val errJson = """
                                {
                                  "error": {
                                    "message": "Bridge execution failed: ${e.message?.replace("\"", "\\\"")}",
                                    "type": "bridge_error"
                                  }
                                }
                            """.trimIndent()
                            call.respondText(errJson, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }.start(wait = false)

            isRunning.set(true)
            Log.i(TAG, "Workspace LLM Bridge Server started on http://127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Workspace LLM Bridge Server on port $port", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            server = null
            isRunning.set(false)
            Log.i(TAG, "Workspace LLM Bridge Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Workspace LLM Bridge Server", e)
        }
    }
}
