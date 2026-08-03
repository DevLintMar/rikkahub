package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastForEachIndexed
import kotlin.time.Clock
import me.rerere.ai.ui.UIMessagePart

/**
 * 聚合思考块内的用户交互状态（用于抑制执行结束后的自动收起）：
 * - [detailOpen]：是否有工具详情 BottomSheet 打开
 * - [expandedThoughtCount]：处于用户展开态(Expanded)的思考步骤数，>0 表示有用户展开的思考内容
 */
@Stable
class ChainBlockInteractionState {
    var detailOpen by mutableStateOf(false)
    var expandedThoughtCount by mutableStateOf(0)
}

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/** 聚合思考块：返回 (思考总毫秒数, 已执行的工具数)。 */
fun List<ThinkingStep>.thinkingAggregate(): Pair<Long, Int> {
    var thoughtMs = 0L
    var toolCount = 0
    forEach { step ->
        when (step) {
            is ThinkingStep.ReasoningStep -> {
                val r = step.reasoning
                val end = r.finishedAt ?: Clock.System.now()
                thoughtMs += (end - r.createdAt).inWholeMilliseconds
            }
            is ThinkingStep.ToolStep -> if (step.tool.isExecuted) toolCount++
        }
    }
    return thoughtMs to toolCount
}
