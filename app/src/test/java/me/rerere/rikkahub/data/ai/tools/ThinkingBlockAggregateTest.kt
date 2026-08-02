package me.rerere.rikkahub.data.ai.tools

import kotlin.time.Clock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.thinkingAggregate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingBlockAggregateTest {

    private fun reasoning(durationSeconds: Int) = UIMessagePart.Reasoning(
        reasoning = "think",
        createdAt = Clock.System.now() - kotlin.time.Duration.parse("${durationSeconds}s"),
        finishedAt = Clock.System.now(),
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
