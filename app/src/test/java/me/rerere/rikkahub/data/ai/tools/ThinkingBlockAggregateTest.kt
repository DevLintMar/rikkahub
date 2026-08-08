package me.rerere.rikkahub.data.ai.tools

import kotlin.time.Clock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.thinkingAggregate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingBlockAggregateTest {

    // 固定基准时间戳：createdAt/finishedAt 用同一 base，差值精确整秒（两次 now() 会有微秒抖动导致 inWholeMilliseconds 截断）
    private val base = Clock.System.now()

    private fun reasoning(durationSeconds: Int) = UIMessagePart.Reasoning(
        reasoning = "think",
        createdAt = base - kotlin.time.Duration.parse("${durationSeconds}s"),
        finishedAt = base,
    )

    private fun tool(output: String? = null) = UIMessagePart.Tool(
        toolCallId = "t", toolName = "x", input = "{}",
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList(),
    )

    @Test
    fun `sums reasoning durations and counts executed tools`() {
        val steps = listOf(
            ThinkingStep.ReasoningStep(reasoning(3)),
            ThinkingStep.ToolStep(tool("""{"type":"x"}""")),
            ThinkingStep.ReasoningStep(reasoning(2)),
            ThinkingStep.ToolStep(tool()),  // 未执行（output 空）不计
        )
        val (thoughtMs, toolCount) = steps.thinkingAggregate()
        assertEquals(5000, thoughtMs)
        assertEquals(1, toolCount)
    }

    @Test
    fun `empty block yields zeros`() {
        assertEquals(0L to 0, emptyList<ThinkingStep>().thinkingAggregate())
    }
}
