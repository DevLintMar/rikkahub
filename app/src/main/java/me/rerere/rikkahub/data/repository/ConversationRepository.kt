package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.net.Uri
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.embedding.FusedHit
import me.rerere.rikkahub.data.embedding.SemanticIndexManager
import me.rerere.rikkahub.data.embedding.rrfFuseScored
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 对话查询的文件夹范围。
 * - [All]：全部文件夹（含未归类）；
 * - [Unfiled]：默认「聊天」文件夹（未归类，folder_id 为空），id 记为 `default`；
 * - [Folder]：指定文件夹。
 */
sealed interface ConversationFolderScope {
    data object All : ConversationFolderScope
    data object Unfiled : ConversationFolderScope
    data class Folder(val id: Uuid) : ConversationFolderScope
}

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val semanticIndexManager: SemanticIndexManager,
) {
    companion object {
        private const val TAG = "ConversationRepository"
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /** 把文件夹范围翻译成 DAO 的 folder_id 参数：null=全部、""=未归类、具体 uuid=该文件夹。 */
    private fun ConversationFolderScope.toDaoFolderId(): String? = when (this) {
        ConversationFolderScope.All -> null
        ConversationFolderScope.Unfiled -> ""
        is ConversationFolderScope.Folder -> id.toString()
    }

    suspend fun getRecentConversations(
        assistantId: Uuid,
        scope: ConversationFolderScope = ConversationFolderScope.All,
        limit: Int = 10,
        offset: Int = 0,
    ): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            folderId = scope.toDaoFolderId(),
            limit = limit,
            offset = offset
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    suspend fun countConversationsOfAssistant(
        assistantId: Uuid,
        scope: ConversationFolderScope = ConversationFolderScope.All,
    ): Int =
        conversationDAO.countConversationsOfAssistant(assistantId.toString(), scope.toDaoFolderId())

    /** 指定文件夹内全部会话 id（供文件夹维度过滤/计数）。 */
    suspend fun getConversationIdsInFolder(folderId: Uuid): List<Uuid> =
        conversationDAO.getConversationIdsInFolder(folderId.toString())
            .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
        semanticIndexManager.markPending(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
        semanticIndexManager.markPending(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        semanticIndexManager.deleteConversation(conversation.id)
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        // 引用计数回收：仅当附件不再被任何"其他会话"消息引用时才物理删除，
        // 避免多会话共享同一张图时删一个会话误删另一会话的图片
        cleanupUploadFilesIfUnreferenced(fullConversation.files, conversation.id.toString())
    }

    /**
     * 引用计数回收 upload 附件。删除对话/消息后调用：对失去引用的附件做全库
     * 引用检查（message_node.messages LIKE 文件名），无任何"其他会话"仍引用才物理删除。
     * [excludeConversationId] 排除正在删除/编辑的会话：其自身节点可能因并发取消
     * 任务的兜底保存残留引用，不应计入。注意：调用方需在 DB 完成删除/更新后再调用
     * （被删内容已从 message_node 表移除）。
     */
    suspend fun cleanupUploadFilesIfUnreferenced(
        files: List<Uri>,
        excludeConversationId: String,
    ) {
        if (files.isEmpty()) return
        Logging.log(TAG, "cleanup: ${files.size} candidate upload file(s): ${files.joinToString(", ")}")
        // 逐个文件名查全库引用（排除当前会话），无任何其他会话仍引用才物理删除
        val toDelete = mutableListOf<Uri>()
        files.forEach { uri ->
            val fileName = uri.toString().uploadFileNameOrNull() ?: return@forEach
            val refs = messageNodeDAO.countMessageNodesContaining(fileName, excludeConversationId)
            Logging.log(TAG, "  $fileName refs=$refs ${if (refs == 0) "-> DELETE" else "-> KEEP"}")
            if (refs == 0) {
                toDelete.add(uri)
            }
        }
        if (toDelete.isNotEmpty()) {
            Logging.log(TAG, "  deleteChatFiles: ${toDelete.joinToString(", ")}")
            filesManager.deleteChatFiles(toDelete)
        } else {
            Logging.log(TAG, "  nothing to delete (all still referenced)")
        }
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    data class ConversationSearchHit(
        val conversationId: String,
        val title: String,
        val index: Int,        // 在 currentMessages(USER/ASSISTANT) 中的位置，与 read_conversation offset 对齐
        val role: String,
        val snippet: String,   // FTS snippet（[brackets] 着重标记）
        val date: String,      // 匹配消息的日期（yyyy-MM-dd）
    )

    data class ConversationSearchPage(
        val hits: List<ConversationSearchHit>,
        val hasMore: Boolean,
    )

    data class RebuildResult(
        val indexed: Int,
        val failed: Int,
    )

    /**
     * 混合搜索并返回每条匹配的具体消息（融合 FTS + 语义，跨会话按融合分降序）。
     * 每条结果含所在对话、该消息在 currentMessages(USER/ASSISTANT) 中的索引
     * （与 read_conversation 的 offset 对齐）与 FTS [brackets] 着重标记 snippet。
     * @param scope 非 [ConversationFolderScope.All] 时仅搜索该范围内的会话。
     */
    suspend fun searchConversationMessages(
        query: String,
        scope: ConversationFolderScope = ConversationFolderScope.All,
        limit: Int = 15,
        offset: Int = 0,
    ): ConversationSearchPage {
        val folderIds = when (scope) {
            ConversationFolderScope.All -> null
            ConversationFolderScope.Unfiled -> conversationDAO.getConversationIdsInFolder("").toSet()
            is ConversationFolderScope.Folder -> conversationDAO.getConversationIdsInFolder(scope.id.toString()).toSet()
        }
        if (folderIds == null) {
            return searchConversationMessagesAll(query, limit, offset)
        }
        // 仅搜索指定文件夹：融合后按 conversationId 过滤；候选不足时循环放大取数，保证分页 has_more 正确
        var fetchLimit = offset + limit + 1
        var filtered: List<FusedHit> = emptyList()
        repeat(4) {
            val fts = searchMessages(query, MessageSearchSort.RELEVANCE).take(fetchLimit)
            val semantic = if (semanticIndexManager.isConfigured()) {
                semanticIndexManager.search(query, fetchLimit).map { hit ->
                    MessageSearchResult(
                        nodeId = hit.nodeId,
                        messageId = hit.messageId,
                        conversationId = hit.conversationId,
                        title = "",
                        updateAt = Instant.EPOCH,
                        snippet = hit.chunkText.take(120),
                    )
                }
            } else emptyList()
            filtered = rrfFuseScored(fts, semantic, k = 60).filter { it.conversationId in folderIds }
            if (filtered.size >= offset + limit + 1) return@repeat
            fetchLimit *= 2
        }
        val all = filtered.mapNotNull { buildConversationSearchHit(it) }
            .sortedByDescending { it.date } // 按日期新 → 旧
        return ConversationSearchPage(
            hits = all.drop(offset).take(limit),
            hasMore = offset + limit < all.size,
        )
    }

    private suspend fun searchConversationMessagesAll(
        query: String,
        limit: Int,
        offset: Int,
    ): ConversationSearchPage {
        // 多取 1 条用于判断 offset+limit 之后是否还有（has_more）
        val fetchLimit = offset + limit + 1
        val fts = searchMessages(query, MessageSearchSort.RELEVANCE).take(fetchLimit)
        val semantic = if (semanticIndexManager.isConfigured()) {
            semanticIndexManager.search(query, fetchLimit).map { hit ->
                MessageSearchResult(
                    nodeId = hit.nodeId,
                    messageId = hit.messageId,
                    conversationId = hit.conversationId,
                    title = "",
                    updateAt = Instant.EPOCH,
                    snippet = hit.chunkText.take(120),
                )
            }
        } else emptyList()

        val all = rrfFuseScored(fts, semantic, k = 60)
            .mapNotNull { buildConversationSearchHit(it) }
            .sortedByDescending { it.date } // 按日期新 → 旧

        return ConversationSearchPage(
            hits = all.drop(offset).take(limit),
            hasMore = offset + limit < all.size,
        )
    }

    /** 把一条融合命中转成具体消息命中（load 会话、在 currentMessages 中定位 index）。 */
    private suspend fun buildConversationSearchHit(hit: FusedHit): ConversationSearchHit? {
        val conversation = getConversationById(Uuid.parse(hit.conversationId)) ?: return null
        val branch = runCatching { conversation.currentMessages }
            .getOrDefault(emptyList())
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val index = branch.indexOfFirst { it.id.toString() == hit.messageId }
        if (index < 0) return null
        val message = branch[index]
        return ConversationSearchHit(
            conversationId = hit.conversationId,
            title = conversation.title,
            index = index,
            role = message.role.name.lowercase(),
            snippet = hit.snippet,
            date = message.createdAt.date.toString(),
        )
    }

    suspend fun rebuildAllIndexes(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): RebuildResult {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }

        // 语义索引重建：清空后全量 markPending，再分批嵌入
        semanticIndexManager.deleteAll()
        var pendingRows = 0
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            pendingRows += semanticIndexManager.markPending(conversation)
            onProgress(index + 1, total)
        }
        // 分批嵌入：反复调用直到队列清空（indexPending 内部按 batchSize 分块请求，单趟最多 64 行）
        var indexed = 0
        var failed = 0
        if (pendingRows > 0) {
            var processed = 0
            while (true) {
                val counts = semanticIndexManager.indexPending()
                indexed += counts.indexed
                failed += counts.failed
                processed += counts.indexed
                onProgress(processed, pendingRows)
                // 队列已清空，或整批失败（indexed == 0）——后者留给 worker 退避重试，避免死循环
                if (counts.indexed == 0) break
            }
        }
        return RebuildResult(indexed = indexed, failed = failed)
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/** 从 upload file:// URL 提取 uuid 文件名（供引用计数 LIKE 匹配）；非 upload 路径返回 null。纯 JVM 可测。 */
internal fun String.uploadFileNameOrNull(): String? {
    if (!contains("/upload/")) return null
    return substringAfterLast('/').takeIf { it.isNotBlank() }
}

/**
 * 从失去引用的附件中筛出"全库无任何消息引用"的部分（upload URL 字符串版本）。
 * [isReferenced]：fileName → 是否仍被任何会话消息引用（由 DAO LIKE 查询提供）。
 * 纯函数，不依赖 Android，供单测直接验证决策逻辑。
 */
internal fun filterUnreferencedUploadUrls(
    lostUrls: List<String>,
    isReferenced: (String) -> Boolean,
): List<String> = lostUrls.filter { url ->
    val fileName = url.uploadFileNameOrNull() ?: return@filter false
    !isReferenced(fileName)
}
