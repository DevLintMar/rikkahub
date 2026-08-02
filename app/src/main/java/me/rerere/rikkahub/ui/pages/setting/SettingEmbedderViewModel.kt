package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.embedding.EmbeddingClient
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * 管理"重建语义索引"任务的生命周期。
 *
 * 重建过程包含破坏性步骤（先清空 FTS 表再重建），必须运行在 [viewModelScope]
 * 中而非页面级 [androidx.compose.runtime.rememberCoroutineScope] —— 页面离开组合时
 * rememberCoroutineScope 会被取消，导致索引清空后中断、留下残缺索引；而
 * viewModelScope 在导航条目停留在返回栈期间一直存活。
 */
class SettingEmbedderViewModel(
    private val conversationRepo: ConversationRepository,
    private val embeddingClient: EmbeddingClient,
) : ViewModel() {
    var isRebuilding by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0 to 0)
        private set

    var rebuildFinished by mutableStateOf<String?>(null)
        private set

    enum class TestState { IDLE, TESTING, DONE }

    var testState by mutableStateOf(TestState.IDLE)
        private set

    var testResult by mutableStateOf<String?>(null)
        private set

    fun rebuild() {
        if (isRebuilding) return
        viewModelScope.launch {
            isRebuilding = true
            progress = 0 to 0
            rebuildFinished = null
            try {
                val result = conversationRepo.rebuildAllIndexes { current, total ->
                    progress = current to total
                }
                rebuildFinished = if (result.failed > 0) {
                    "重建完成：${result.indexed} 条已嵌入，${result.failed} 条失败（请检查 embedder 配置）"
                } else {
                    "语义索引重建完成：${result.indexed} 条已嵌入"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rebuildFinished = "重建失败：${e.message ?: "未知错误"}"
            } finally {
                isRebuilding = false
            }
        }
    }

    /** 用当前表单值（未必已保存）发起一次探测嵌入，验证 baseUrl/model/apiKey 是否可用。 */
    fun testConnection(baseUrl: String, model: String, apiKey: String) {
        if (testState == TestState.TESTING) return
        viewModelScope.launch {
            testState = TestState.TESTING
            testResult = null
            val start = System.currentTimeMillis()
            val embeddings = try {
                embeddingClient.computeEmbeddings(
                    texts = listOf("测试"),
                    model = model.ifBlank { "（未填）" },
                    baseUrl = baseUrl.ifBlank { "（未填）" },
                    apiKey = apiKey,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val elapsed = System.currentTimeMillis() - start
            val emb = embeddings?.firstOrNull()
            testState = TestState.DONE
            testResult = if (emb != null && emb.isNotEmpty()) {
                "连接成功：维度 ${emb.size}，耗时 ${elapsed}ms，向量前 3 维 [${emb.take(3).joinToString { "%.4f".format(it) }}]"
            } else {
                "连接失败：未返回有效向量（耗时 ${elapsed}ms，请检查 baseUrl / model / API Key）"
            }
        }
    }

    fun consumeRebuildFinished() {
        rebuildFinished = null
    }

    fun consumeTestResult() {
        testResult = null
    }
}
