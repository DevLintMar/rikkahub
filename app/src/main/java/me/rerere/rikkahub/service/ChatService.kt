package me.rerere.rikkahub.service

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.SubAgentFailReason
import me.rerere.rikkahub.data.ai.tools.local.TaskStatus
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.ImageLazyLoadTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.UploadReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.lostUploadUrlsAfterDelete
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.rikkahub.utils.ProcessInfo
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.toMessageTimeString
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        ImageLazyLoadTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val keepAlive: GenerationKeepAlive,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    init {
        // 监听子代理/工作流后台执行完成事件（仅有这一个 init 块订阅事件总线）
        appScope.launch {
            appEventBus.events.collect { event ->
                if (event is AppEvent.SubAgentTaskFinished) {
                    handleTaskFinished(event)
                }
            }
        }
    }

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { id, cause ->
                    val session = sessions[id]
                    if (cause != null) session?.messageQueue?.pause()
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
                    }
                    appScope.launch { advanceConversation(id) }
                },
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) { block() }

        return appScope.launch(start = CoroutineStart.LAZY) {
            // 刻意不传 backgroundTask：这条通道只跑**聊天生成**，此刻持有前台服务的就是这轮回复生成，
            // 文案该是「正在生成回复…」。子代理自己的网络流不走这里，它直接用
            // keepAlive.hold(..., backgroundTask = true)（Task 7）。给本函数加一个永不被传的
            // backgroundTask 形参，等于邀请后来者把一轮回复生成误标成「后台任务」。
            val token = keepAlive.hold(conversationId)
            try {
                block()
            } finally {
                keepAlive.release(token)
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid, folderId: Uuid? = null) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        // **已载入的会话绝不重载**——§6.1 的规则对这条路径同样成立。内存态可能领先于库（生成期间只在
        // 结束时落盘），重载会把正在生成的内容冲掉；而且一次库读整对象替换内存还会抹掉**投递刚写的标记**
        // ⇒ `startTaskDelivery` 判 `stillPending = false` ⇒ 不触发那一轮回复，随后某次 save 再把无标记态
        // 落库（结果正文永久丢失）。用户打开一个正在后台生成的会话就会撞上。
        if (session.loaded) {
            settingsStore.updateAssistant(session.state.value.assistantId)
            return
        }
        val conversation = conversationRepo.getConversationById(conversationId)
        // 库里没有这条会话时要现建一条；构建它所需的设置读是**挂起**调用，所以整段必须在临界区**外**：
        // `synchronized` 里出现挂起点 Kotlin 直接报编译错误
        // ("The 'first' suspension point is inside a critical section")——这也正是不能把它放回锁里的原因。
        val resolvedConversation = conversation ?: run {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val baseConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            )
            (if (folderId != null) baseConversation.copy(folderId = folderId) else baseConversation)
                .updateCurrentMessages(assistant.presetMessages)
        }
        val effectiveAssistantId = synchronized(session) {
            // 库读是挂起调用，两条路径都可能在它那里让出；**复查 + 置位 + 应用必须在同一个锁里**（与 ensureLoaded 同形）。
            // 这个锁里只允许有**不挂起**的调用：`updateConversation` 与读 `state.value` 都是。
            if (!session.loaded) {
                updateConversation(conversationId, resolvedConversation)
                session.loaded = true
            }
            // 只有「用户打开了会话」才切全局助手；用**生效态**的 assistantId（可能是别人的载入结果）——
            // 在锁内取值（不挂起），真正的切换是挂起调用，放到锁外。
            session.state.value.assistantId
        }
        // `updateAssistant` 是挂起调用（DataStore 写），同样必须在锁外；
        // 值在锁内取好，语义与「在锁内调用」一致。
        settingsStore.updateAssistant(effectiveAssistantId)
        // 这条路径是「软件退出 / 子代理中断」失败回执的**主要**来源——新进程里 registry 是空的，
        // 用户打开那个会话时工具结果仍停在 started，正是这里把它补成终态。
        // 静默：只补标记不触发生成（决策 2），AI 下次在这个会话里发言时由派生通知看到。
        reconcileInterruptedSubAgentTasks(conversationId)
        // 再补一次「结果已入库但没人回复」的拾遗：进程若死在终态入库与那一轮之间（崩溃/被杀），
        // 这些标记会一直挂在那里。用户打开会话时把它们重新排队，AI 会把结果补讲出来。
        requeueUnrepliedTaskMarkers(conversationId)
    }

    /**
     * 背景生成（标题 / 建议）写回状态的**唯一**安全方式：在 Main 上、以「已载入会话的实时内存态」为基
     * 合并一次再落库。
     *
     * 不能沿用「从库读整对象 → 改字段 → 整对象写回」：库读可能早于投递的内存写，整对象写回会把投递
     * 刚写的标记连内存带库一起抹掉（`startTaskDelivery` 随后判 `stillPending = false`，不触发回复，
     * 结果正文永久丢失）。会话**未载入**时退回库读——**绝不能**用 `state.value` 兜底：那可能是
     * `Conversation.ofId` 建的空壳，写它等于把会话清空。
     */
    private suspend fun mergeConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ) {
        withContext(Dispatchers.Main) {
            val session = getOrCreateSession(conversationId)
            val base = if (session.loaded) {
                session.state.value
            } else {
                conversationRepo.getConversationById(conversationId) ?: return@withContext
            }
            val updated = update(base)
            // **先在内存里落一次，再落库**：`saveConversation` 的第一句是挂起的 `existsConversationById`，
            // 内存写入在它之后才发生。中间那段挂起里若有投递恢复执行（它的最后一个 await 刚结束）
            // 并写入标记，本函数随后就会用「不含标记的 base + 新字段」整对象覆盖内存与库
            // ⇒ `startTaskDelivery` 判 `stillPending=false` ⇒ 不触发回复、结果正文从库里消失。
            // 同一条规则在 `deliverTaskResult` 与 `reconcileInterruptedSubAgentTasks` 里已各写一次——
            // **每个写会话对象的路径都必须照做**（这是本计划第三次踩它：前两次写对了，这次漏了）。
            updateConversation(conversationId, updated)
            saveConversation(conversationId, updated)
        }
    }

    /**
     * 把「终态已入库、但还没有被回复过」的结果重新排进触发队列。
     *
     * 为什么需要：触发队列（`taskDeliveries`）只在内存里。进程若死在「终态已入库、那一轮还没开」
     * 之间——被系统杀掉、或者前台服务契约失败导致崩溃（真机现场就是这个）——标记留在会话里，
     * 却再也没人开那一轮，用户看到的就是几条「已完成」下面空空如也。
     * 载入会话时补一次，让它自愈；判据与注入用的是同一个（标记之后没有带非空文本的 assistant 消息），
     * 所以已经讲过的结果不会被重复开轮。
     */
    private fun requeueUnrepliedTaskMarkers(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val pending = session.state.value.currentMessages.pendingTaskMarkers()
        if (pending.isEmpty()) return
        pending.forEach { session.taskDeliveries.enqueue(it.taskId) }
        Logging.log(
            TAG,
            "requeue: conv=$conversationId 有 ${pending.size} 条结果还没被回复（${pending.joinToString(",") { it.taskId }}）" +
                "，已重新排队 ${ProcessInfo.describe()}",
        )
        advanceConversation(conversationId)
    }

    /**
     * 背景路径（子代理投递、中断对账）在改会话内容之前必须过这一关。
     *
     * 返回 false 表示会话在库里不存在——**不新建、不复活**（`saveConversation` 对不存在的 id 会
     * `insertConversation`，直接投递等于把用户删掉的会话变回来）。
     */
    private suspend fun ensureLoaded(conversationId: Uuid): Boolean {
        val session = getOrCreateSession(conversationId)
        if (session.loaded) return true
        val conversation = conversationRepo.getConversationById(conversationId) ?: return false
        synchronized(session) {
            // 同一会话可能同时有两条背景路径在载入（两个子代理任务几乎同时完成，或投递与「用户打开
            // 会话」触发的中断对账撞上）。`getConversationById` 是挂起调用，两条协程都会在它这里让出，
            // 于是两条都会带着「自己读到的那份」走到这一步。若不复查，后到者的 `updateConversation`
            // 会整段替换内存态（底层 `ConversationRepository.updateConversation` 是 deleteByConversation
            // + saveMessageNodes），把先到者刚写入的标记与改写后的工具结果抹掉；随后它自己的
            // `saveConversation` 再把这份抹掉后的状态落库——先到者那次投递就真丢了，而且那条工具结果
            // 在库里仍是 `started`，会被对账判成「中断」，给出一个错的失败回执。
            //
            // 复查与置位必须在同一个锁里：否则两条都会通过复查。
            if (!session.loaded) {
                updateConversation(conversationId, conversation)
                session.loaded = true
            }
        }
        // 对账放在锁外：它自己会再读一次 state 并（必要时）落库，与「应用载入结果」不是同一件事；
        // 它也是幂等的（改写后 status 就是终态，第二次不会再命中）。
        reconcileInterruptedSubAgentTasks(conversationId)
        return true
    }

    /**
     * 载入会话后补中断回执：工具结果仍是 `started` **且本进程完全不认识这个 taskId** → 判定为中断。
     *
     * 静默：**不触发**生成（决策 2），AI 下次在这个会话里发言时自然通过派生通知看到。
     * 幂等：改写后 `status` 变终态，下次不再命中。
     */
    private suspend fun reconcileInterruptedSubAgentTasks(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        // 有生成在飞时直接跳过：本函数会追加标记节点并改写工具结果，而生成期间对节点树的改动会被
        // 按下标合并的 assistant 消息吃掉（机制见 `deliverTaskResult` 闸门处的注释）。
        // `initializeConversation` 这条路径会撞上该情形——用户打开一个正在后台生成的会话。
        // 跳过是安全的：本函数幂等，且每次载入都会再跑一次；重启后 registry 为空时必然补上回执。
        if (session.generationJob.value?.isActive == true) {
            Log.i(TAG, "reconcileInterruptedSubAgentTasks: $conversationId 跳过（有生成在飞）")
            return
        }
        val conversation = session.state.value
        // 判据是「registry 里没有这个 taskId」而不是「registry 里它不是 IN_PROGRESS」。
        // 后者会把**本进程里刚刚完成、投递还没落地**的任务误判成中断：那一刻工具结果仍是 `started`
        // （① 终态入库在 ensureLoaded 返回之后才做），而 registry 里已是 COMPLETED ⇒ `isLive` 为假
        // ⇒ 被判中断 ⇒ 先写一条**假的**「应用退出」回执并落库；随后真正的投递走到 `applyTaskDelivery`
        // 时标记已存在、按幂等契约返回 null ⇒ **真投递被静默丢弃**：结果正文丢失、且不触发 AI 回复。
        // 这正是验收标准第一条要保证的场景（用户切屏离开 → 会话被 5 秒空闲回收 → 子代理完成 →
        // 投递的 ensureLoaded 先跑对账）。「本进程不认识它」才等于「它随上一个进程一起死了」。
        // 传「本进程登记过的 taskId 集合」，不传谓词：谓词的名字与语义曾经正好相反，而那次
        // 反转让「本进程刚跑完的任务」全被判成「应用退出」（详见候选函数的 KDoc）。
        // 集合只取一次，判定与下面那行诊断日志用的是**同一份**数据——两者对不上就说明代码有病。
        val knownTaskIds = localTools.subAgentTaskRegistry.all().map { it.taskId }.toSet()
        val candidates = conversation.currentMessages.interruptedSubAgentTaskCandidates(knownTaskIds)
        if (candidates.isEmpty()) return
        // 诊断（本判定的唯一产出就是「应用退出」回执，而判据本身无法自证，所以两侧证据都钉下来）：
        // 本进程身份（pid/procStart）、本进程 registry 的身份与它登记过的全部 taskId、以及每张被
        // 判中断的卡片自带的两条时间（卡片创建时刻、卡片里记的发起进程启动时刻）。
        // 「卡片是本进程写的却被判中断」只要发生过一次，这里就会留下定案材料。
        Logging.log(
            TAG,
            buildString {
                append("reconcile: conv=").append(conversationId)
                append(" judged=")
                append(
                    candidates.joinToString(",") { candidate ->
                        "${candidate.taskId}(msgAt=${candidate.cardCreatedAt.toJavaLocalDateTime().toMessageTimeString()}" +
                            ",launched=${candidate.cardLaunchedAt},cardProcStart=${candidate.cardProcessStartedAt})"
                    },
                )
                append(" registry=@").append(System.identityHashCode(localTools.subAgentTaskRegistry))
                append(" known=").append(knownTaskIds.joinToString(","))
                append(' ')
                append(ProcessInfo.describe())
            },
        )
        val interrupted = candidates.map { it.taskId }

        var current = conversation
        interrupted.forEach { taskId ->
            val updated = current.applyTaskDelivery(
                delivery = TaskDelivery(
                    taskId = taskId,
                    status = "failed",
                    reason = SubAgentFailReason.APP_EXIT.wire,
                    description = null,
                    result = null,
                    error = context.getString(R.string.sub_agent_error_app_exit),
                ),
                // 用 lambda：description 由 applyTaskDelivery 从工具结果里解析（这里拿不到）
                markerText = { description -> taskMarkerText(description, "failed", SubAgentFailReason.APP_EXIT) },
            )
            if (updated != null) current = updated
        }
        if (current !== conversation) {
            Log.i(TAG, "reconcileInterruptedSubAgentTasks: $conversationId (${interrupted.size} task(s))")
            // 与 `deliverTaskResult` 同一个理由，这里也**先同步写内存**再落库：`saveConversation` 内部
            // 的 `updateConversation` 在它自己的 `existsConversationById` **挂起之后**才执行。那段挂起里
            // 若起了新一轮生成（生成结束回调排空队列、或另一条投递的 `saveConversation` 尾部），
            // 该生成会快照到「未含本次对账标记」的状态；等我们恢复再把内存设成含标记的那份，
            // 它在飞的 assistant 消息就会按 index 落进标记节点 —— 与 Critical 一模一样的机制链，
            // 只是这次的后果是「中断通知永久派发不出去」而不是「结果正文丢失」。
            // 守卫（本函数开头那个 `isActive` 检查）到这里的改写之间没有任何挂起点，
            // 所以先写内存即可把它关掉。
            updateConversation(conversationId, current)
            saveConversation(conversationId, current)
        }
    }

    // ---- 子代理任务投递（完成 / 失败 / 取消 / 中断 四条路径共用） ----

    private fun handleTaskFinished(event: AppEvent.SubAgentTaskFinished) {
        appScope.launch {
            // 整段投递期间持一个引用。
            //
            // 为什么必须持：`taskDeliveries` 刻意不进 `isInUse`（spec §5.4），所以队列本身**不**阻止
            // 会话被空闲回收。而空闲定时器是**提前**装好的（`release()` 让 refCount 归零时 arm 一颗
            // `delay(5s)`），它只在自己 fire 的那一刻检查 `refCount <= 0 && !isGenerating`。
            // 投递是挂起函数（`saveConversation` 落库会让出线程），若此刻恰好有一颗先前装好的定时器
            // 到期，`removeSession` 会因 `isInUse == false` 而**回收会话**——队列随会话一起消失
            // （`sessions.remove` + `session.cleanup()`），随后 `saveConversation` 尾部的
            // `advanceConversation` 会因 `sessions[id]` 为 null 直接 return，**这一轮生成再也不会被触发**。
            // ① 终态已入库所以状态不丢（§5.2），丢的是 ②「触发一轮 AI 回复」——正是验收标准第一条。
            //
            // 这不违反 §5.4：调用返回后引用即释放，队列依旧不让会话常驻。
            addConversationReference(event.conversationId)
            try {
                deliverTaskResult(event)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "deliverTaskResult failed", e)
            } finally {
                removeConversationReference(event.conversationId)
            }
        }
    }

    /**
     * ① 终态入库（随时可做、可丢）→ ② 触发回复（需要时机）。
     *
     * 做完 ① 之后进程立刻死掉也不丢：结果正文在标记 metadata 里、终态在工具结果里，都已入库；
     * 通知会在下次生成时重新派生。
     */
    private suspend fun deliverTaskResult(event: AppEvent.SubAgentTaskFinished) {
        // 闸门放在 `ensureLoaded` **之前**：`ensureLoaded` 内部会调中断对账，而它同样会改动节点树。
        val session = getOrCreateSession(event.conversationId)
        Logging.log(
            TAG,
            "deliver entry: task=${event.taskId} status=${event.status} reason=${event.reason?.wire} " +
                "loaded=${session.loaded} generating=${session.generationJob.value?.isActive} " +
                ProcessInfo.describe(),
        )

        // ① 与「载入时的中断对账」都会改动会话的节点树（追加标记节点、改写工具结果），
        //    而**生成在飞时绝不能动**。机制（本任务最难发现的坑）：
        //    `Conversation.updateCurrentMessages` 是**按下标合并**的 —— `messages[index]` 落到
        //    `messageNodes[index]`，节点里没有这条消息就**追加进该节点并把 selectIndex 移过去**；
        //    而 `GenerationLoop.generateInternal` 用的是一份**冻结快照**（`:432 var messages = messages`，
        //    之后每个 chunk 都基于它累积，从不重读会话）。于是生成在飞时追加节点会造成错位：
        //    该生成第一个 chunk 的 assistant 消息会落进**标记节点** → 标记对 `currentMessages` 隐形
        //    （`pendingTaskMarkers()` 只扫 `currentMessages`）⇒ `startTaskDelivery` 判 `stillPending = false`
        //    ⇒ **不触发那一轮、结果正文再也派发不出去**（正文只在标记 metadata 里）；同时工具结果的
        //    终态改写会被那一轮的旧版本按 id 覆盖回去（终态丢失）。
        //    触发窗口是「请求已构建、首个 token 未到」的一次 provider 往返——很宽，不是窄缝。
        //    设计 §7 原先断言「生成中的 assistant 位置在标记之前」，那只在**首个 chunk 落地之后**成立。
        //    旧机制没踩到，是因为 `handleSubAgentRecall` 只在空闲时才追加可见节点；Task 8 去掉了那个前提。
        //
        //    用 while + 复查当前值，而不是一次 `first {}`：`first {}` 是按**发射时**的值判定的，
        //    而生成结束回调排空队列时，可能在我们被调度回来之前就又起了一轮。
        awaitIdle(session)

        if (!ensureLoaded(event.conversationId)) {
            Log.w(TAG, "deliverTaskResult: conversation ${event.conversationId} 已不存在，丢弃投递")
            return
        }
        val status = if (event.status == TaskStatus.COMPLETED) "completed" else "failed"

        // 再等一次，而且是**紧贴改写**的一次：上面 `ensureLoaded` 里有挂起点（会话未载入时要读库；
        // 中断对账若命中还要落库），而这段时间里别的路径可能已经起了新一轮生成——例如同一会话的
        // 第二条投递落库后其 `saveConversation` 尾部排空队列，或用户队列消息被排空
        // （`removeQueuedMessage` / `finishEditQueuedMessage` 都会调 `advanceConversation`）。
        // 只等一次会把「闸门」与「改写」之间留下一段挂起，机制链就重新成立。
        //
        // **承重不变量：`awaitIdle` 返回到 `updateConversation` 之间不得有任何挂起点。**
        // 因为 `isActive == false` 并不只意味着「没有生成」——`setJob` 会先把 LAZY 的 job 装进
        // `_generationJob` 再 `start()`，那一瞬间 `isActive` 也是 false，而它的请求快照是稍后
        // 在 `handleMessageComplete` 里才构建的。这段改写之所以安全，靠的正是「同一个不挂起
        // 片段」：本协程在 Main 上，只要不挂起，任何新装的 job 的协程体都排在我们之后，
        // 快照必然包含我们刚写的标记。**别在这两行之间插入任何 suspend 调用（包括日志之外的
        // I/O、`yield`、`delay`）**——那会让整条机制链重新成立，且没有任何测试会报警。
        awaitIdle(session)

        // 紧贴改写的这行诊断：`generating` 必须为 false —— 它若为 true，说明闸门漏了，而
        // 生成期间改节点树会让在飞的回复按下标落进标记节点（见上方那段长注释）。
        // `Logging.log` 不挂起，放在这里不破坏「awaitIdle 到 updateConversation 之间不挂起」这条不变量。
        Logging.log(
            TAG,
            "deliver proceed: task=${event.taskId} status=$status loaded=${session.loaded} " +
                "generating=${session.generationJob.value?.isActive} queue=${session.taskDeliveries.size()} " +
                ProcessInfo.describe(),
        )

        val updated = session.state.value.applyTaskDelivery(
            delivery = TaskDelivery(
                taskId = event.taskId,
                status = status,
                reason = event.reason?.wire,
                description = event.description,
                result = event.result,
                error = event.error,
            ),
            markerText = { description -> taskMarkerText(description, status, event.reason) },
        ) ?: run {
            // 无变更 = 这条结果已被消化过（该 taskId 的标记已经在会话里，幂等契约）。记一行日志：
            // 现场只有这一行能看出「投递被跳过」。
            // 注意：无锚点**不再**算「无变更」——那种情形必须照常留回执，见 `applyTaskDelivery` 的注释。
            Log.w(TAG, "deliverTaskResult: ${event.taskId} 无需变更，跳过（已投递）")
            Logging.log(TAG, "deliver skip: task=${event.taskId}（标记已存在，幂等） ${ProcessInfo.describe()}")
            return
        }

        // 先在**内存**里落一次，再落库：`saveConversation` 内的 `updateConversation` 在它自己的一次
        // 挂起读库**之后**才执行，两条并发投递会双双读到「还不含对方标记」的内存态，后写者会把先写者
        // 从内存与库里一起抹掉。先同步写内存可保证第二条投递读到的是「已含第一条标记」的状态。
        updateConversation(event.conversationId, updated)

        // 只有来自「实时完成」的投递才触发新的一轮；中断对账走的不是这条入口。
        session.taskDeliveries.enqueue(event.taskId)
        saveConversation(event.conversationId, updated)
        Logging.log(TAG, "deliver done: task=${event.taskId} 已入库并排队触发 ${ProcessInfo.describe()}")
    }

    /**
     * 等到这个会话没有在飞的生成。
     *
     * **每次改动节点树之前都要调**，不只是进 `deliverTaskResult` 时调一次：调用点之间可能有挂起点
     * （见两处调用点的注释），只等一次会让「闸门」与「改写」之间留下一次挂起。机制见 `deliverTaskResult`
     * 上方那段说明。
     *
     * 用 while + 复查当前值，而不是一次 `first {}`：`first {}` 是按**发射时**的值判定的，
     * 而生成结束回调排空队列时，可能在我们被调度回来之前就又起了一轮。
     */
    private suspend fun awaitIdle(session: ConversationSession) {
        while (session.generationJob.value?.isActive == true) {
            session.generationJob.first { it?.isActive != true }
        }
    }

    private fun taskMarkerText(description: String, status: String, reason: SubAgentFailReason?): String = when {
        status == "completed" -> context.getString(R.string.sub_agent_task_marker_finished, description)
        reason == SubAgentFailReason.USER_CANCELLED -> context.getString(R.string.sub_agent_task_marker_cancelled, description)
        reason == SubAgentFailReason.APP_EXIT -> context.getString(R.string.sub_agent_task_marker_interrupted, description)
        else -> context.getString(R.string.sub_agent_task_marker_failed, description)
    }

    /** 取消一个还在跑的子代理任务（卡片上的按钮）。对不存在或已终态的任务是空操作。 */
    fun cancelSubAgentTask(taskId: String) {
        localTools.subAgentRuntime.cancel(taskId)
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        advanceConversation(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        advanceConversation(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        advanceConversation(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            advanceConversation(conversationId)
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            advanceConversation(conversationId)
        }
        return reply
    }

    /**
     * 会话推进的唯一入口：同一时刻只允许一件事在跑，顺序是「用户排队消息 → 子代理投递」。
     *
     * 所有「状态变了，也许该继续」的地方都调它（生成结束、投递入库、队列增减、保存）。
     */
    private fun advanceConversation(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext()
            if (next != null) {
                session.submittingMessage = next
                return sendQueuedMessage(session, next)
            }
            return startTaskDelivery(session)
        }
    }

    /**
     * 为队首任务开一轮生成。
     *
     * 用 `setJob(job, cancelPrevious = false)`：**绝不取消用户正在跑的生成**（旧实现走
     * `setJob(job)`，默认会取消前一个，存在把用户刚发起的生成掐掉的窗口）。
     */
    private fun startTaskDelivery(session: ConversationSession): Job? {
        // **一条结果一条回复**：从队列取队首、只为它开一轮，且这一轮只把**它**的通知交给模型。
        //
        // 这里原先还有一道「标记之后是否已有 assistant 文本」的跳过判据，三道结果几乎同时回来时
        // 它会退化：第一条结果的回复落在**所有**标记之后，于是后两条被判成「已被回复」直接跳过
        // ⇒ 三条结果只得到一条回复（现场日志：`开一轮 … pending=3 queueLeft=2`，此后不再开轮）。
        // 队列本身就是「谁还没被回复」的账本（取队首即销账），不需要再用启发式猜。
        // 代价：用户中途说话了、而这条结果已被那轮回复顺带讲掉时，仍会多开一轮——按「每条结果
        // 都要有自己的回复」这是可接受的（多一轮而不是少一条）。
        val taskId = session.taskDeliveries.peek() ?: return null
        session.taskDeliveries.takeNext()
        val job = launchGenerationJob(
            conversationId = session.id,
            keepAliveInBackground = true,
        ) {
            handleMessageComplete(session.id, notifyTaskIds = setOf(taskId))
        }
        session.setJob(job, cancelPrevious = false)
        Logging.log(
            TAG,
            "startTaskDelivery: task=$taskId 开一轮（cancelPrevious=false，绝不取消在飞生成） " +
                "pending=${session.state.value.currentMessages.pendingTaskMarkers().size} " +
                "queueLeft=${session.taskDeliveries.size()} ${ProcessInfo.describe()}",
        )
        job.invokeOnCompletion { appScope.launch { advanceConversation(session.id) } }
        return job
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                val conversation = session.state.value

                // 定位消息所在节点：优先用 id 匹配（消息 id 稳定）。UI 传入的 message 实例
                // 可能与 session 最新实例 equals 不匹配（usage/annotations 等字段更新过），
                // equals 匹配会返回 null → indexOf(null) = -1 → subList(0, 0) 清空整个会话，
                // checkFilesDelete 连带删除全部图片文件——图片变占位符的根因。
                val node = conversation.getMessageNodeByMessageId(message.id)
                    ?: conversation.getMessageNodeByMessage(message)
                if (node == null) {
                    addError(
                        IllegalStateException(
                            context.getString(R.string.error_message_not_found_regenerate)
                        ),
                        conversationId,
                        title = context.getString(R.string.error_title_regenerate_message)
                    )
                    // 上游把这里改成了 launchGenerationJob（前台服务保活），标签随之改名
                    return@launchGenerationJob
                }

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(getOrCreateSession(conversationId)) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        /** 非空 = 本轮只为这些 taskId 开（见 `startTaskDelivery`）：只注入它们的通知。 */
        notifyTaskIds: Set<String>? = null,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException("No chat model selected")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)
        // 只为触发轮记一次「快照长度 vs 节点数」的落差（见下方写回处的诊断）。
        var gapLogged = false

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
                // 会话中存在用户图片时提示：图片懒加载依赖 read_image 工具，无工具能力的模型无法查看
                val hasAttachedImages = initialConversation.messageNodes
                    .flatMap { it.messages }
                    .any { message -> message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") } }
                if (hasAttachedImages) {
                    addError(
                        IllegalStateException(context.getString(R.string.error_tool_unable_read_images)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    conversationId = conversationId,
                    workspaceCwd = conversation.workspaceCwd,
                )
            } catch (error: InvalidMcpServerNamesException) {
                // 这条失败路径会把整个会话的消息队列暂停（见下一行），提示里必须说清楚，
                // 否则用户只看到「队列不跑了」，不知道去哪恢复。
                sessions[conversationId]?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        ) + "\n\n" +
                            context.getString(R.string.error_mcp_invalid_server_name_queue_paused)
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            generationLoop.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let { raw ->
                    val base = if (messageRange != null) {
                        raw.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        raw
                    }
                    // **顺序要紧：注入必须从「还带标记的列表」派生**——通知的正文就是从标记的
                    // metadata 里读出来的。滤掉标记只作用于最终发给 provider 的那一份。
                    // 反过来做（先把标记滤掉、再注入）会让通知一条都派生不出来：模型干看着工具卡片
                    // 上的 completed，只能猜「它们跑完了但结果没送到我手上」。
                    val injected = injectTaskNotifications(
                        messages = base,
                        pendingTaskCount = localTools.subAgentTaskRegistry.liveCount(),
                        onlyTaskIds = notifyTaskIds,
                    )
                    Logging.log(
                        TAG,
                        "inject: round=${if (notifyTaskIds != null) "trigger" else "user"} " +
                            "task=${notifyTaskIds?.joinToString(",") ?: "-"} " +
                            "→ ${injected.size - base.size} 条通知",
                    )
                    injected
                        // 标记是给用户看的回执（正文在 metadata 里、只走上面注入的通知），不能进 prompt：
                        // 否则模型会看到「Agent X 已完成」却看不到内容，既多余又误导。
                        .filterNot { it.subAgentTaskMarkerOrNull() != null }
                        // 触发轮再补一条**空的助手消息**：这一轮的回复落进它，而不是追加进上一条——
                        // 否则多轮回复会堆在同一条消息里（现场：生成的东西都被加到原来的节点里去了）。
                        // `GenerationLoop` 的 responseBaseMessages 会「复用末尾那条助手消息」，所以
                        // 先放一条空的，落点就一定是新的。
                        .let { list ->
                            if (notifyTaskIds == null) {
                                list
                            } else {
                                list + UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model.id)
                            }
                        }
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                activeMemories = if (assistant.useGlobalMemory) {
                    memoryRepository.getActiveMemories(MemoryRepository.GLOBAL_MEMORY_ID)
                } else {
                    memoryRepository.getActiveMemories(assistant.id.toString())
                },
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(UploadReminderTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = tools,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // 过滤掉注入的子代理通知（用户不可见，仅提供给 AI 上下文）。
                        // 判据是 USER + isSynthetic + 含标签三者同时满足，**不是只看标签**：真实消息
                        // （用户写的、或模型复述的）里若出现该字面量，只看标签会把它们一起剔掉，而误过滤
                        // 不可恢复（回复静默不写回 + 下标合并错位），漏过滤只是多一条可疑气泡。
                        // 取舍的完整论证见 `UIMessage.isInjectedTaskNotification` 的 KDoc。
                        val filteredMessages = chunk.messages.filterNot { it.isInjectedTaskNotification() }
                        val currentConversation = getConversationFlow(conversationId).value
                        if (notifyTaskIds != null && !gapLogged) {
                            gapLogged = true
                            // 诊断：触发轮的快照长度与当前节点数对不上，就说明这一轮期间会话被追加过
                            // 节点（下标错位的根因）。修好后这里应当恒等；一旦不等，把这行给我。
                            Logging.log(
                                TAG,
                                "writeback(trigger): snapshot=${filteredMessages.size} " +
                                    "nodes=${currentConversation.messageNodes.size} ${ProcessInfo.describe()}",
                            )
                        }
                        val updatedConversation = if (notifyTaskIds != null) {
                            // 触发轮：新消息落末尾。否则它可能被塞进「快照之后才追加进来的那份回执」
                            // 所在的节点里，UI 上就是「合成一条 + 1/2 分支」。
                            currentConversation.updateCurrentMessagesAppendingNew(filteredMessages)
                        } else {
                            currentConversation.updateCurrentMessages(filteredMessages)
                        }
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            if (it is CancellationException) throw it
            sessions[conversationId]?.messageQueue?.pause()

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        val cleanedConversation = conversation.copy(messageNodes = messagesNodes)
        updateConversation(conversationId, cleanedConversation)

        // 图片懒加载标记清理：标记是发送态产物（ImageLazyLoadTransformer 只注入到
        // 发给模型的副本），一旦因重新生成等路径残留在持久化消息里，AI 会看到死路径
        // 导致图片变占位。检测到即剥离标记文本，让消息回到干净的用户文本。
        val stripped = cleanedConversation.stripLazyLoadImageMarkers()
        if (stripped != cleanedConversation) {
            updateConversation(conversationId, stripped)
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation 可能不是最新了：交给合入口，以已载入会话的实时内存态为基（避免整对象写回
            // 把投递刚写的标记抹掉）。
            mergeConversationState(conversationId) { it.copy(title = result.message.toText().trim()) }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            // 清空旧建议：走合入口（未载入时退回库读，绝不拿空壳 state 兜底）。
            mergeConversationState(conversationId) { it.copy(chatSuggestions = emptyList()) }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            // 写入新建议：以实时内存态为基（库读可能早于投递的内存写）。
            mergeConversationState(conversationId) { it.copy(chatSuggestions = suggestions.take(10)) }
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        // 防御：新会话节点为空但旧会话非空，说明发生了异常截断（如节点定位失败导致
        // subList(0,0)），此时照常删除会连用户图片一起误删——跳过删除并告警。
        if (newConversation.messageNodes.isEmpty() && oldConversation.messageNodes.isNotEmpty()) {
            Log.w(
                TAG,
                "checkFilesDelete: skipped unexpected empty newConversation(${newConversation.id}); old had ${oldConversation.messageNodes.size} nodes"
            )
            return
        }
        val session = sessions[newConversation.id]
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        // upload 目录附件是用户持久附件（图片/文档），可能被多条消息引用；
        // 任何自动清理误删的代价极高（重新生成/懒加载等路径可能误判引用），
        // 因此 upload 文件一律不随会话更新自动删除，靠"设置-文件管理"手动清理。
        // 路径形式多样（/data/data vs /data/user/0 符号链接），按父目录名判定最稳。
        fun isUploadFile(uri: Uri): Boolean =
            uri.path?.let { path ->
                path.substringBeforeLast('/').substringAfterLast('/') == FileFolders.UPLOAD
            } == true

        val missingFromNew = oldFiles.filter { file -> newFiles.none { it == file } }
        val skippedUpload = missingFromNew.filter(::isUploadFile)
        if (skippedUpload.isNotEmpty()) {
            // 诊断：某次会话更新时这些 upload 附件在新会话中"缺失引用"（候选误删），
            // belt 已拦截。若用户图片出现在此清单，说明消息 part 在更新中被弄丢了。
            val msg = "checkFilesDelete: belt skipped ${skippedUpload.size} upload file(s) missing from newConversation: ${skippedUpload.joinToString(", ")}"
            Log.w(TAG, msg)
            Logging.log(TAG, msg)
        }
        val deletedFiles = missingFromNew - skippedUpload.toSet()
        if (deletedFiles.isNotEmpty()) {
            // 诊断：记录被删文件与调用来源（定位"重新生成后图片被删"根因）
            val stack = Thread.currentThread().stackTrace
                .take(8)
                .joinToString("\n") { "    at $it" }
            Log.w(
                TAG,
                "checkFilesDelete: deleting ${deletedFiles.size} file(s): ${deletedFiles.joinToString(", ")}\n$stack"
            )
            Logging.log(
                TAG,
                "checkFilesDelete: deleting ${deletedFiles.size} file(s): ${deletedFiles.joinToString(", ")}\n$stack"
            )
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }

        // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
        // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
        advanceConversation(conversationId)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)

        // 引用计数回收：删除消息后失去引用且不再被任何会话消息引用的 upload 附件
        // （checkFilesDelete 因 belt 跳过 upload，此处显式补充）。
        // "失去引用"按 upload 文件名比较，不按 URL 字符串——同一物理文件可能有多种拼写，
        // 见 lostUploadUrlsAfterDelete 的说明。
        val lostFiles = lostUploadUrlsAfterDelete(
            oldUrls = currentConversation.files.map { it.toString() },
            newUrls = updatedConversation.files.map { it.toString() },
        ).map { it.toUri() }
        Logging.log(TAG, "deleteMessage: lost ${lostFiles.size} file(s): ${lostFiles.joinToString(", ")}")
        if (lostFiles.isNotEmpty()) {
            conversationRepo.cleanupUploadFilesIfUnreferenced(lostFiles, conversationId.toString())
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
