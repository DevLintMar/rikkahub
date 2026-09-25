package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     * 移除所有图片懒加载标记文本 part，让消息回到干净的用户文本。
     * 标记是发送态产物（ImageLazyLoadTransformer 只注入到发给模型的副本），
     * 持久化消息里不该出现；重新生成旧懒加载消息时若残留，AI 会看到死路径 → 图片占位。
     * 返回移除后的 Conversation（无标记时原样返回）。
     */
    fun stripLazyLoadImageMarkers(): Conversation {
        val newNodes = messageNodes.map { node ->
            if (node.messages.isEmpty()) return@map node
            val newMessages = node.messages.map { message ->
                if (message.role != MessageRole.USER) return@map message
                val newParts = message.parts.filterNot { part ->
                    part is UIMessagePart.Text &&
                        part.text.startsWith(IMAGE_LAZY_LOAD_MARKER_PREFIX) &&
                        part.text.contains("read_image")
                }
                if (newParts.size == message.parts.size) message
                else message.copy(parts = newParts)
            }
            if (newMessages == node.messages) node
            else node.copy(messages = newMessages)
        }
        return if (newNodes == messageNodes) this
        else copy(messageNodes = newNodes)
    }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    /**
     * 写回一轮生成的结果，**新消息一律成为末尾的新节点**。
     *
     * 与 [updateCurrentMessages] 只差「新消息怎么安置」：后者把它放进 `messageNodes[index]`，
     * 而 `index` 是**这一轮请求构建时那份快照**里的下标。快照之后若会话又追加了节点——典型就是
     * 另一个子代理的完成回执——下标就会错位，于是这一轮的回复被塞进**那份回执所在的节点**里，
     * UI 上表现为「两个消息合成一条、右上角出现 1/2 分支切换」（现场截图就是这样）。
     *
     * 触发轮用这个变体：它要的语义是「我的回复出现在末尾」，不是「占据快照里的第几个下标」。
     * 已存在的消息（流式更新）仍按 **id 就地替换**，所以逐 chunk 写回不会产生新节点。
     */
    fun updateCurrentMessagesAppendingNew(messages: List<UIMessage>): Conversation {
        val newNodes = messageNodes.toMutableList()
        messages.forEach { message ->
            val nodeIndex = newNodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
            if (nodeIndex >= 0) {
                val node = newNodes[nodeIndex]
                val indexInNode = node.messages.indexOfFirst { it.id == message.id }
                val newMessages = node.messages.toMutableList().also { it[indexInNode] = message }
                // 不移动 `selectIndex`：与 [updateCurrentMessages] 的「就地替换」分支保持一致，
                // 免得改动分支选择的既有语义。
                newNodes[nodeIndex] = node.copy(messages = newMessages)
            } else {
                newNodes.add(message.toMessageNode())
            }
        }
        return copy(messageNodes = newNodes)
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

/**
 * 图片懒加载标记的辨识前缀（与 ImageLazyLoadTransformer 注入文本一致，需同步维护）。
 */
internal const val IMAGE_LAZY_LOAD_MARKER_PREFIX = "[The user attached "

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
