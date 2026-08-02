package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
) : ViewModel() {
    var isRebuilding by mutableStateOf(false)
        private set

    var progress by mutableStateOf(0 to 0)
        private set

    var rebuildFinished by mutableStateOf<String?>(null)
        private set

    fun rebuild() {
        if (isRebuilding) return
        viewModelScope.launch {
            isRebuilding = true
            progress = 0 to 0
            rebuildFinished = null
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    progress = current to total
                }
                rebuildFinished = "语义索引重建完成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rebuildFinished = "重建失败：${e.message ?: "未知错误"}"
            } finally {
                isRebuilding = false
            }
        }
    }

    fun consumeRebuildFinished() {
        rebuildFinished = null
    }
}
