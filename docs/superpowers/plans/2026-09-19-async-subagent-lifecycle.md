# 异步子代理生命周期重做 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让后台子代理在任何会话、任何前后台状态下都能跑完并把结果只交给 AI（不展示给用户），且完成/失败/取消/进程中断四条路径都有统一、可持久、AI 能读到的回执。

**Architecture:** 把「终态入库」与「触发回复」拆成两步。投递入口唯一：`ChatService.deliverTaskResult` 先 `ensureLoaded` 再从库载入真实会话，一次 `saveConversation` 写入两样东西——改写后的工具结果（只有状态）与一条可见标记（文本给人看、`UIMessagePart.Text.metadata` 里放结果正文只给 AI）；随后视会话忙闲走带保活的生成通道或进 FIFO 排队。AI 侧的通知在请求构建时从标记**派生**（不落库），位置判据是「标记之后是否已有 assistant 文本」。子代理自己的网络流用 `GenerationKeepAlive` 复用现有前台服务，进程退出/中断由载入会话时的对账补失败回执。

**Tech Stack:** Kotlin 2.x、Jetpack Compose、Koin、Room（**本轮不动 schema**）、kotlinx.serialization、JUnit4、GitHub Actions（本机无 Android 编译器）

**Spec:** `docs/superpowers/specs/2026-09-19-async-subagent-lifecycle-design.md`

## Global Constraints

- **本机无 Android 编译器**：编译与单测结论**只从 GitHub Actions 取**。判定铁律：先 `git push`，再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`，然后**只用 `--json` 判结论**（`conclusion` + `headSha` 核对是本次提交；再用 `--json jobs` 确认 `build` 作业没被 skip 成假绿）。`gh run watch` 的退出码不可信。
- **绝对不改**：`app/build.gradle.kts` 之外的构建配置、DB schema / `AppDatabase.version` / 迁移文件、`MessageQueue.kt`、`QueuedMessage`、`ChatGenerationForegroundService` 的 acquire/release 契约（只允许加参数）。
- **不引入「隐藏/不可见消息」概念**：会话里不出现任何需要展示层过滤的节点。
- **结果可见性（决策 5）**：异步子代理的结果正文只进 `Text.metadata`，**不得**出现在工具结果 JSON 或任何会用 `stringResource` 之外的路径展示的地方；同步子代理（`run_in_background=false`）保持现状。
- **文案只加 `res/values/strings.xml` 与 `res/values-zh/strings.xml`**（现有 `tool_ui_sub_agent_*` 也只有这两个 locale）。
- **提交信息用中文**，一行主题说清「为什么」；每个提交末尾加：
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- **改动文件保持原有行尾**（仓库 `.kt` 是 LF/CRLF 混合，`core.autocrlf=true` 提交时归一成 LF）；不要整文件换行尾制造假 diff。
- 一个任务的测试跑不起来时**不要跳过**：本地无法跑 Gradle，就用 CI 那一轮当测试轮（见每个任务末尾的「CI 验证」）。

**加速说明（执行者可自行取舍）**：Task 1–6 都是「新增纯逻辑 + 最小接线」，互相不依赖行为，可以连续提交后**用一次 CI 验证**（提交仍分开，便于二分定位）。Task 7 起每任务各自一次 CI。

---

## 文件结构

**新增**

| 文件 | 职责 |
|---|---|
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistry.kt` | 进程内任务登记表（谁还活着）。终态不进这里 |
| `app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt` | 全部投递期纯逻辑：标记读写、位置判据、通知派生、工具结果改写、对账判据 |
| `app/src/main/java/me/rerere/rikkahub/service/TaskDeliveryQueue.kt` | 每会话「待触发投递」FIFO（顺序 + 去重） |
| `app/src/main/java/me/rerere/rikkahub/service/GenerationKeepAlive.kt` | 前台服务持有权的薄封装（生成与子代理共用） |
| `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistryTest.kt` | 上表第 1 项的测试 |
| `app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt` | 上表第 2 项的测试 |
| `app/src/test/java/me/rerere/rikkahub/service/TaskDeliveryQueueTest.kt` | 上表第 3 项的测试 |

**修改**

| 文件 | 改动 |
|---|---|
| `service/ChatService.kt` | 新增 `ensureLoaded` / `reconcileInterruptedSubAgentTasks` / `deliverTaskResult` / `cancelSubAgentTask`；`initializeConversation` 只在末尾补一行对账（**不**拆共用方法，见 Task 8 Step 1）；`dispatchNextQueuedMessage` → `advanceConversation`（多一个投递分支）；注入逻辑改为派生；**删除** `pendingNotifications` / `PendingRecall` / `handleSubAgentRecall` / `fireRecall` / `setSessionJob` / `checkPendingRecall` |
| `service/ConversationSession.kt` | 新增 `loaded` 标志与 `taskDeliveries` |
| `service/ChatGenerationForegroundService.kt` | `acquire` 多一个 `backgroundTask` 参数；通知文案按「是否只有后台任务」选择 |
| `data/event/AppEvent.kt` | `SubAgentCompleted` → `SubAgentTaskFinished`（带 `status` / `reason`） |
| `data/ai/tools/local/SubAgentRuntime.kt` | 改：用 registry、hold 保活、`cancel(taskId)`、只上报终态；删除 `tasks` / `TaskInfo` / `getTaskInfos` |
| `data/ai/tools/local/LocalTools.kt` | 改：构造 registry 与 keepAlive、暴露 `subAgentTaskRegistry` |
| `di/AppModule.kt` | 改：注册 `GenerationKeepAlive`、给 `LocalTools` 多传一个参数 |
| `ui/components/message/tools/BuiltinToolUIs.kt` | 改：`SubAgentToolUI` 显示失败分类 + 「取消任务」按钮 |
| `ui/pages/chat/ChatVM.kt` | 改：暴露 `cancelSubAgentTask` |
| `ui/pages/chat/ChatPage.kt` | 改：提供 `LocalSubAgentTaskActions` |
| `RouteActivity.kt` | 改：事件分支改名 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 改：新增 9 条文案 |

---

### Task 1: 任务登记表 `SubAgentTaskRegistry`

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistry.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistryTest.kt`

**Interfaces:**
- Consumes: 无（本任务不依赖前面的任务）
- Produces（后续任务直接按这些签名调用）：
  - `enum class TaskStatus { IN_PROGRESS, COMPLETED, FAILED }`（从 `SubAgentRuntime.kt` **搬到这里**，包名不变）
  - `enum class SubAgentFailReason(val wire: String)`，值 `USER_CANCELLED("user_cancelled")`、`APP_EXIT("app_exit")`
  - `data class SubAgentTaskInfo(taskId: String, conversationId: Uuid, description: String, prompt: String, startedAt: Long, status: TaskStatus, reason: SubAgentFailReason?, result: String?, error: String?)`
  - `class SubAgentTaskRegistry { fun register(taskId, conversationId, description, prompt): SubAgentTaskInfo; fun finish(taskId, status, reason, result, error): SubAgentTaskInfo?; fun isLive(taskId): Boolean; fun get(taskId): SubAgentTaskInfo?; fun liveCount(): Int; fun all(): List<SubAgentTaskInfo> }`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistryTest.kt`

```kotlin
package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentTaskRegistryTest {

    @Test
    fun `登记后任务处于存活状态`() {
        val registry = SubAgentTaskRegistry()
        val conversationId = Uuid.random()

        registry.register("sub_1", conversationId, "搜索 AI 新闻", "找到三条新闻")

        assertTrue(registry.isLive("sub_1"))
        assertEquals(1, registry.liveCount())
        assertEquals(conversationId, registry.get("sub_1")?.conversationId)
        assertEquals("搜索 AI 新闻", registry.get("sub_1")?.description)
        assertEquals(TaskStatus.IN_PROGRESS, registry.get("sub_1")?.status)
    }

    @Test
    fun `未登记的任务不是存活任务且查询为空`() {
        val registry = SubAgentTaskRegistry()

        assertFalse(registry.isLive("sub_missing"))
        assertNull(registry.get("sub_missing"))
        assertEquals(0, registry.liveCount())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `终态后不再是存活任务且带着结果与原因`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "搜索 AI 新闻", "找到三条新闻")

        registry.finish(
            taskId = "sub_1",
            status = TaskStatus.COMPLETED,
            reason = null,
            result = "三条新闻……",
            error = null,
        )

        assertFalse(registry.isLive("sub_1"))
        assertEquals(0, registry.liveCount())
        assertEquals(TaskStatus.COMPLETED, registry.get("sub_1")?.status)
        assertEquals("三条新闻……", registry.get("sub_1")?.result)
        assertNull(registry.get("sub_1")?.reason)
    }

    @Test
    fun `终态不会被第二次覆盖`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "搜索 AI 新闻", "找到三条新闻")
        registry.finish("sub_1", TaskStatus.COMPLETED, null, "成功的结果", null)

        // 取消与正常完成可能同时到达；先到者胜，避免「已完成」被改写成「已取消」
        val second = registry.finish(
            "sub_1",
            TaskStatus.FAILED,
            SubAgentFailReason.USER_CANCELLED,
            null,
            "cancelled",
        )

        assertEquals(TaskStatus.COMPLETED, second?.status)
        assertEquals(TaskStatus.COMPLETED, registry.get("sub_1")?.status)
        assertEquals("成功的结果", registry.get("sub_1")?.result)
    }

    @Test
    fun `取消未登记或已终态的任务是空操作`() {
        val registry = SubAgentTaskRegistry()
        assertNull(registry.finish("sub_missing", TaskStatus.FAILED, SubAgentFailReason.USER_CANCELLED, null, "x"))

        registry.register("sub_1", Uuid.random(), "任务", "提示")
        registry.finish("sub_1", TaskStatus.FAILED, SubAgentFailReason.APP_EXIT, null, "app_exit")
        registry.finish("sub_1", TaskStatus.FAILED, SubAgentFailReason.USER_CANCELLED, null, "cancelled")

        assertEquals(SubAgentFailReason.APP_EXIT, registry.get("sub_1")?.reason)
    }

    @Test
    fun `liveCount 只统计进行中的任务`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "A", "a")
        registry.register("sub_1b", Uuid.random(), "B", "b")
        registry.register("sub_1c", Uuid.random(), "C", "c")
        registry.finish("sub_1b", TaskStatus.COMPLETED, null, "done", null)

        assertEquals(2, registry.liveCount())
        assertEquals(3, registry.all().size)
    }
}
```

- [ ] **Step 2: 建实现文件**

`app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistry.kt`

```kotlin
package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class TaskStatus { IN_PROGRESS, COMPLETED, FAILED }

/**
 * 子代理失败的性质。`null`（不在枚举里）表示模型或网络错误。
 *
 * 写进会话的 `reason` 用它，UI 据此显示分类短文案，原始 error 正文只进标记 metadata 给 AI。
 */
enum class SubAgentFailReason(val wire: String) {
    USER_CANCELLED("user_cancelled"),
    APP_EXIT("app_exit"),
}

data class SubAgentTaskInfo(
    val taskId: String,
    val conversationId: Uuid,
    val description: String,
    val prompt: String,
    val startedAt: Long,
    val status: TaskStatus,
    val reason: SubAgentFailReason? = null,
    val result: String? = null,
    val error: String? = null,
)

/**
 * 异步子代理任务的进程内登记表：只回答「这一趟进程里还有什么活着」。
 *
 * 终态一律由 ChatService 写进会话（工具结果 + 标记），所以进程被杀之后这里为空也不需要恢复——
 * 中断对账靠会话里工具结果的 `status: started` 判据，不靠这张表的历史。
 */
class SubAgentTaskRegistry {
    private val tasks = ConcurrentHashMap<String, SubAgentTaskInfo>()

    fun register(
        taskId: String,
        conversationId: Uuid,
        description: String,
        prompt: String,
    ): SubAgentTaskInfo = SubAgentTaskInfo(
        taskId = taskId,
        conversationId = conversationId,
        description = description,
        prompt = prompt,
        startedAt = System.currentTimeMillis(),
        status = TaskStatus.IN_PROGRESS,
    ).also { tasks[taskId] = it }

    /**
     * 写终态。**已终态的任务不再被覆盖**：用户取消与正常完成可能同时到达，
     * 先到者胜，避免把「已完成」改写成「已取消」。
     *
     * 用 `ConcurrentHashMap.compute` 而不是「读 → 判断 → 写」：后者两次调用都读到
     * `IN_PROGRESS` 时会双双写入、最后写入者胜，正好是这个守卫要挡的情形。`compute`
     * 把整段判断与写入放进同一个原子操作里。
     *
     * 返回值语义：任务不存在 → `null`；已被别人写成终态 → 返回**已有的**那条（写入被拒绝）；
     * 本次写入成功 → 返回新终态。
     */
    fun finish(
        taskId: String,
        status: TaskStatus,
        reason: SubAgentFailReason? = null,
        result: String? = null,
        error: String? = null,
    ): SubAgentTaskInfo? = tasks.compute(taskId) { _, previous ->
        when {
            previous == null -> null
            previous.status != TaskStatus.IN_PROGRESS -> previous
            else -> previous.copy(status = status, reason = reason, result = result, error = error)
        }
    }

    fun isLive(taskId: String): Boolean = tasks[taskId]?.status == TaskStatus.IN_PROGRESS

    fun get(taskId: String): SubAgentTaskInfo? = tasks[taskId]

    fun liveCount(): Int = tasks.values.count { it.status == TaskStatus.IN_PROGRESS }

    fun all(): List<SubAgentTaskInfo> = tasks.values.sortedBy { it.startedAt }
}
```

- [ ] **Step 3: 删掉 `SubAgentRuntime.kt` 里重复的 `TaskStatus`**

新文件与 `SubAgentRuntime.kt` 在**同一个包**里，两个 `TaskStatus` 声明会直接编译冲突。所以只把旧的那一行删掉：

在 `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentRuntime.kt` 中删除：

```kotlin
enum class TaskStatus { IN_PROGRESS, COMPLETED, FAILED }
```

**其余一律不动**：`data class TaskInfo`（引用包内的 `TaskStatus`，仍然合法）、`private val tasks`、`getTaskInfos()`、`getTaskInfo()` 全部保留，`SubAgentRuntime.executeAsync` 也保持原样。本任务是纯增量——旧路径一字不改，所以 `ChatService` 这一任务**不需要任何改动**，能单独提交且行为零变化。

- [ ] **Step 4: CI 验证（本任务的测试轮）**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistry.kt \
        app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentRuntime.kt \
        app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentTaskRegistryTest.kt
git commit -m "feat(subagent): 新增任务登记表（纯增量，旧路径不动）"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```

等 run 结束后：

```bash
gh run view <run-id> --json conclusion,headSha    # conclusion 必须是 success，headSha 必须是本次提交
gh run view <run-id> --json jobs                  # build 作业不能有 skipped 步骤
```

Expected: `conclusion = "success"`，`headSha` 对得上，`build` 作业里 `:app:testDebugUnitTest` 执行且没有 skipped。**失败时看 `gh run view <run-id> --log` 里 `e: ` 开头的编译错误。**

---

### Task 2: 投递期纯逻辑（一）：标记读写、位置判据、通知派生

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt`

**Interfaces:**
- Consumes: 无
- Produces：
  - `internal const val SUB_AGENT_TOOL_NAME = "sub_agent"`
  - `internal const val SUB_AGENT_TASK_METADATA_KEY = "subAgentTask"`
  - `internal const val TASK_NOTIFICATION_TAG = "<task-notification>"`
  - `internal data class SubAgentTaskMarker(taskId: String, status: String, reason: String?, description: String, result: String?, error: String?)`
  - `internal fun UIMessage.subAgentTaskMarkerOrNull(): SubAgentTaskMarker?`
  - `internal fun List<UIMessage>.pendingTaskMarkers(): List<SubAgentTaskMarker>`
  - `internal fun taskNotificationXml(marker: SubAgentTaskMarker, pendingTaskCount: Int): String`
  - `internal fun injectTaskNotifications(messages: List<UIMessage>, pendingTaskCount: Int): List<UIMessage>`
  - `internal fun xmlEscape(text: String): String`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt`

```kotlin
package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDeliveryTest {

    private fun marker(
        taskId: String = "sub_1",
        status: String = "completed",
        reason: String? = null,
        description: String = "搜索 AI 新闻",
        result: String? = "三条新闻……",
        error: String? = null,
    ) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = "Agent \"$description\" finished",
                metadata = buildJsonObject {
                    put("subAgentTask", buildJsonObject {
                        put("taskId", taskId)
                        put("status", status)
                        if (reason != null) put("reason", reason) else put("reason", JsonNull)
                        put("description", description)
                        if (result != null) put("result", result) else put("result", JsonNull)
                        if (error != null) put("error", error) else put("error", JsonNull)
                    })
                },
            ),
        ),
    )

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun userText(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `标记的 metadata 往返后可读`() {
        val parsed = marker(status = "failed", reason = "app_exit", result = null, error = "boom").subAgentTaskMarkerOrNull()

        assertEquals("sub_1", parsed?.taskId)
        assertEquals("failed", parsed?.status)
        assertEquals("app_exit", parsed?.reason)
        assertEquals("搜索 AI 新闻", parsed?.description)
        assertNull(parsed?.result)
        assertEquals("boom", parsed?.error)
    }

    @Test
    fun `没有 metadata 的普通消息不是标记`() {
        assertNull(userText("你好").subAgentTaskMarkerOrNull())
        assertNull(assistantText("好的").subAgentTaskMarkerOrNull())
        assertNull(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("普通系统消息"))).subAgentTaskMarkerOrNull())
    }

    @Test
    fun `标记之后已有 assistant 文本则不再派生通知`() {
        val messages = listOf(userText("起个子代理"), marker(), assistantText("我看了报告"))

        assertTrue(messages.pendingTaskMarkers().isEmpty())
    }

    @Test
    fun `末尾的标记一定派生通知`() {
        val messages = listOf(userText("起个子代理"), assistantText("已启动后台任务"), marker())

        assertEquals(listOf("sub_1"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `中间的标记按其后的 assistant 文本逐个判定`() {
        val messages = listOf(
            marker(taskId = "sub_a"),
            assistantText("A 的结果我看了"),
            marker(taskId = "sub_b"),
        )

        assertEquals(listOf("sub_b"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `只有空白的 assistant 文本不算已汇报`() {
        val messages = listOf(marker(), assistantText("   "))

        assertEquals(listOf("sub_1"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `多个未汇报的标记按出现顺序全部返回`() {
        val messages = listOf(marker(taskId = "sub_a"), marker(taskId = "sub_b"))

        assertEquals(listOf("sub_a", "sub_b"), messages.pendingTaskMarkers().map { it.taskId })
    }

    @Test
    fun `通知正文取自标记 metadata 且带待完成任务数`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = "搜索 AI 新闻",
                result = "三条新闻",
                error = null,
            ),
            pendingTaskCount = 2,
        )

        assertTrue(xml.contains("<task-id>sub_1</task-id>"))
        assertTrue(xml.contains("<status>completed</status>"))
        assertTrue(xml.contains("<pending-tasks>2</pending-tasks>"))
        assertTrue(xml.contains("<result>三条新闻</result>"))
        assertTrue(xml.startsWith("<task-notification>"))
        assertTrue(xml.endsWith("</task-notification>"))
    }

    @Test
    fun `失败的通知带 reason 与 error 正文而不是 result`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_2",
                status = "failed",
                reason = "app_exit",
                description = "搜索 AI 新闻",
                result = null,
                error = "进程被回收",
            ),
            pendingTaskCount = 0,
        )

        assertTrue(xml.contains("<reason>app_exit</reason>"))
        assertTrue(xml.contains("<result>进程被回收</result>"))
        assertTrue(xml.contains("<summary>Agent \"搜索 AI 新闻\" failed</summary>"))
    }

    @Test
    fun `没有 reason 与正文时不出这两行`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker("sub_3", "completed", null, "任务", null, null),
            pendingTaskCount = 0,
        )

        assertFalse(xml.contains("<reason>"))
        assertFalse(xml.contains("<result>"))
    }

    @Test
    fun `结果里的尖括号被转义，不会破坏通知结构`() {
        val xml = taskNotificationXml(
            marker = SubAgentTaskMarker(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = "a<b>c",
                result = "</result><injected>",
                error = null,
            ),
            pendingTaskCount = 0,
        )

        assertTrue(xml.contains("&lt;injected&gt;"))
        // 结果里的 `</result>` 已被转义，所以整段 XML 里只有闭合标签那一处字面量
        assertEquals(1, Regex("</result>").findAll(xml).count())
        assertTrue(xml.contains("<summary>Agent \"a&lt;b&gt;c\" completed</summary>"))
    }

    @Test
    fun `xmlEscape 处理与号与尖括号`() {
        assertEquals("a&amp;b&lt;c&gt;d", xmlEscape("a&b<c>d"))
    }

    @Test
    fun `注入只追加待汇报的通知，不动原消息`() {
        val messages = listOf(userText("起个子代理"), marker())

        val injected = injectTaskNotifications(messages, pendingTaskCount = 1)

        assertEquals(messages.size + 1, injected.size)
        assertEquals(messages, injected.dropLast(1))
        assertTrue(injected.last().parts.first().let { (it as UIMessagePart.Text).text }.contains(TASK_NOTIFICATION_TAG))
    }

    @Test
    fun `无需汇报时注入是恒等变换`() {
        val messages = listOf(userText("你好"), assistantText("在的"))

        assertEquals(messages, injectTaskNotifications(messages, pendingTaskCount = 0))
    }
}
```

- [ ] **Step 2: 建实现文件**

`app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt`

```kotlin
package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val SUB_AGENT_TOOL_NAME = "sub_agent"
internal const val SUB_AGENT_TASK_METADATA_KEY = "subAgentTask"

/** 子代理任务通知的辨识标签。**只能按内容判定**：`UIMessage.isSynthetic` 是 @Transient，不持久化。 */
internal const val TASK_NOTIFICATION_TAG = "<task-notification>"

/**
 * 投递期写在**可见标记**上的机器可读部分。
 *
 * 载体选 `UIMessagePart.Text.metadata` 而不是「隐藏节点」：metadata 随 `nodes` blob 一起持久化、
 * 从不发给 provider、也不被任何渲染器读取（渲染器只读 `text`）。于是结果正文既能持久化、
 * 又只给 AI 看，且不需要任何展示层过滤。
 */
internal data class SubAgentTaskMarker(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String,
    val result: String?,       // 结果正文；失败时为 null
    val error: String?,        // 原始错误正文；成功时为 null
)

internal fun UIMessage.subAgentTaskMarkerOrNull(): SubAgentTaskMarker? {
    if (role != MessageRole.SYSTEM) return null
    val part = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull() ?: return null
    val meta = part.metadata?.get(SUB_AGENT_TASK_METADATA_KEY) as? JsonObject ?: return null
    val taskId = meta["taskId"]?.jsonPrimitive?.contentOrNull ?: return null
    val status = meta["status"]?.jsonPrimitive?.contentOrNull ?: return null
    return SubAgentTaskMarker(
        taskId = taskId,
        status = status,
        reason = meta["reason"]?.jsonPrimitive?.contentOrNull,
        description = meta["description"]?.jsonPrimitive?.contentOrNull ?: taskId,
        result = meta["result"]?.jsonPrimitive?.contentOrNull,
        error = meta["error"]?.jsonPrimitive?.contentOrNull,
    )
}

/**
 * 还没汇报给 AI 的标记，按出现顺序返回。
 *
 * 判据：标记**之后是否已经存在一条带非空文本的 assistant 消息**。标记总在投递时追加到末尾，
 * 所以任何排在它之后的 assistant 回复，其请求必然在标记已存在之后构建——也就必然已经派生过通知。
 * 于是这条规则不会漏发，同时天然做到「只通知一次」（缺陷④的结构性根因消失）。
 */
internal fun List<UIMessage>.pendingTaskMarkers(): List<SubAgentTaskMarker> {
    val found = mutableListOf<SubAgentTaskMarker>()
    var hasAssistantTextAfter = false
    for (index in indices.reversed()) {
        val message = this[index]
        val marker = message.subAgentTaskMarkerOrNull()
        if (marker != null) {
            if (!hasAssistantTextAfter) found += marker
        } else if (message.role == MessageRole.ASSISTANT &&
            message.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        ) {
            hasAssistantTextAfter = true
        }
    }
    return found.reversed()
}

internal fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

internal fun taskNotificationXml(marker: SubAgentTaskMarker, pendingTaskCount: Int): String = buildString {
    appendLine("<task-notification>")
    appendLine("  <task-id>${xmlEscape(marker.taskId)}</task-id>")
    appendLine("  <status>${xmlEscape(marker.status)}</status>")
    marker.reason?.let { appendLine("  <reason>${xmlEscape(it)}</reason>") }
    appendLine("  <pending-tasks>$pendingTaskCount</pending-tasks>")
    appendLine("  <summary>Agent \"${xmlEscape(marker.description)}\" ${xmlEscape(marker.status)}</summary>")
    val body = marker.result ?: marker.error
    if (!body.isNullOrBlank()) {
        appendLine("  <result>${xmlEscape(body)}</result>")
    }
    append("</task-notification>")
}

/**
 * 派生待汇报的任务通知，追加在请求消息列表末尾。
 *
 * 这些消息**只存在于这一次请求**：`ChatService.handleMessageComplete` 的 `.collect` 会按
 * [TASK_NOTIFICATION_TAG] 把它们剔除，不写回会话状态。
 */
internal fun injectTaskNotifications(
    messages: List<UIMessage>,
    pendingTaskCount: Int,
): List<UIMessage> {
    val markers = messages.pendingTaskMarkers()
    if (markers.isEmpty()) return messages
    val injected = markers.map { marker ->
        UIMessage.system(prompt = taskNotificationXml(marker, pendingTaskCount))
    }
    return messages + injected
}

```

本文件此时**只读不写** metadata，所以 import 只要这些：

```kotlin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
```

（`JsonNull` / `buildJsonObject` / `put` / `JsonPrimitive` 到 Task 3 写出 metadata 时才需要。）

- [ ] **Step 3: CI 验证**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt \
        app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt
git commit -m "feat(subagent): 投递期纯逻辑——标记读写、位置判据、通知派生"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`，`headSha` 对得上，`:app:testDebugUnitTest` 执行且无 skipped。

---

### Task 3: 投递期纯逻辑（二）：工具结果改写、标记落地、中断判据

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `SubAgentTaskMarker` / `SUB_AGENT_TOOL_NAME` / `SUB_AGENT_TASK_METADATA_KEY` / `TASK_NOTIFICATION_TAG`
- Produces：
  - `internal data class TaskDelivery(taskId: String, status: String, reason: String?, description: String?, result: String?, error: String?)`
  - `internal const val SUB_AGENT_RESULT_MAX_CHARS = 100_000`
  - `internal fun Conversation.applyTaskDelivery(delivery: TaskDelivery, markerText: (description: String) -> String): Conversation?`（返回 `null` = 无需变更；`markerText` 收到的是**解析后的** description——工具结果里没写时才回退成 taskId）
  - `internal fun List<UIMessage>.interruptedSubAgentTaskIds(isLive: (String) -> Boolean): List<String>`

- [ ] **Step 1: 写失败测试**（追加到 `SubAgentDeliveryTest.kt`）

```kotlin
    // ---- Task 3 ----

    private fun subAgentToolPart(taskId: String, status: String = "started", description: String = "搜索 AI 新闻") =
        UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "sub_agent",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("type", "sub_agent")
                        put("status", status)
                        put("task_id", taskId)
                        put("description", description)
                        put("mode", "background")
                    }.toString(),
                ),
            ),
        )

    private fun toolCallMessage(part: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("已启动后台任务"), part))

    private fun conversationOf(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(),
        title = "t",
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `投递改写工具结果为终态且不含结果正文`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        val updated = conversation.applyTaskDelivery(
            delivery = TaskDelivery(
                taskId = "sub_1",
                status = "completed",
                reason = null,
                description = null,
                result = "三条新闻……",
                error = null,
            ),
            markerText = { _ -> "Agent \"搜索 AI 新闻\" finished" },
        )!!

        // 只追加一条标记节点，历史节点数 +1
        assertEquals(3, updated.messageNodes.size)
        assertEquals(conversation.messageNodes[0], updated.messageNodes[0])
        assertEquals(conversation.messageNodes[1].id, updated.messageNodes[1].id)
        assertEquals(conversation.messageNodes[1].messages[0].parts[0], updated.messageNodes[1].messages[0].parts[0])

        val rewritten = updated.messageNodes[1].messages[0].parts.filterIsInstance<UIMessagePart.Tool>().single()
        val json = JsonInstant.parseToJsonElement(
            rewritten.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        ).jsonObject

        assertEquals("completed", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("sub_1", json["task_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("background", json["mode"]?.jsonPrimitive?.contentOrNull)   // 原字段要保留
        assertNull(json["result"])                                              // 决策 5：结果不进工具结果
        assertNull(json["error"])
    }

    @Test
    fun `投递结果正文只出现在标记 metadata 里`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        val updated = conversation.applyTaskDelivery(
            delivery = TaskDelivery("sub_1", "completed", null, null, "三条新闻……", null),
            // 用 lambda 拼文案顺带钉住「description 从工具结果里解析出来」这条路径
            markerText = { desc -> "Agent \"$desc\" finished" },
        )!!

        val markerPart = updated.messageNodes.last().messages.single().parts.single() as UIMessagePart.Text
        assertEquals("Agent \"搜索 AI 新闻\" finished", markerPart.text)
        assertTrue(markerPart.text.contains("三条新闻").not())
        assertEquals("三条新闻……", updated.messageNodes.last().messages.single().subAgentTaskMarkerOrNull()?.result)
    }

    @Test
    fun `找不到对应工具结果时返回 null`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))

        assertNull(
            conversation.applyTaskDelivery(
                TaskDelivery("sub_other", "completed", null, null, "x", null),
                markerText = { _ -> "m" },
            ),
        )
    }

    @Test
    fun `重复投递同一条是幂等的`() {
        val conversation = conversationOf(userText("起个子代理"), toolCallMessage(subAgentToolPart("sub_1")))
        val delivery = TaskDelivery("sub_1", "failed", "app_exit", null, null, "进程被回收")

        val interruptedText = { _: String -> "Agent \"搜索 AI 新闻\" 已中断（应用退出）" }
        val once = conversation.applyTaskDelivery(delivery, markerText = interruptedText)!!
        val twice = once.applyTaskDelivery(delivery, markerText = interruptedText)

        // 幂等的判据就是「返回 null」——不能用 `?: once` 兜底，否则断言退化成自己跟自己比
        assertNull("重复投递必须返回 null 表示无需变更", twice)
        assertEquals(3, once.messageNodes.size)
        assertEquals(1, once.messageNodes.count { it.messages.single().subAgentTaskMarkerOrNull() != null })
    }

    @Test
    fun `超长结果按 100KB 截断`() {
        val conversation = conversationOf(toolCallMessage(subAgentToolPart("sub_1")))
        val huge = "x".repeat(SUB_AGENT_RESULT_MAX_CHARS + 1_000)

        val updated = conversation.applyTaskDelivery(
            TaskDelivery("sub_1", "completed", null, null, huge, null),
            markerText = { _ -> "marker" },
        )!!

        val stored = updated.messageNodes.last().messages.single().subAgentTaskMarkerOrNull()?.result.orEmpty()
        // clipTaskResult = take(MAX) + "[truncated]"，所以长度是 MAX + 后缀长度
        assertEquals(SUB_AGENT_RESULT_MAX_CHARS + "[truncated]".length, stored.length)
        assertTrue(stored.endsWith("[truncated]"))
    }

    @Test
    fun `中断判据只认 registry 里不存在的 started 任务`() {
        val messages = listOf(
            toolCallMessage(subAgentToolPart("sub_live", status = "started")),
            toolCallMessage(subAgentToolPart("sub_dead", status = "started")),
            toolCallMessage(subAgentToolPart("sub_done", status = "completed")),
        )

        val interrupted = messages.interruptedSubAgentTaskIds { it == "sub_live" }

        assertEquals(listOf("sub_dead"), interrupted)
    }

    @Test
    fun `终态的旧任务不再被判定为中断`() {
        val messages = listOf(
            toolCallMessage(subAgentToolPart("sub_dead", status = "failed")),
        )

        assertTrue(messages.interruptedSubAgentTaskIds { false }.isEmpty())
    }
```

在同文件顶部补上这些 import：`kotlinx.serialization.json.jsonObject`、`kotlinx.serialization.json.contentOrNull`、`kotlinx.serialization.json.jsonPrimitive`、`me.rerere.rikkahub.data.model.Conversation`、`me.rerere.rikkahub.data.model.toMessageNode`、`me.rerere.rikkahub.utils.JsonInstant`、`org.junit.Assert.assertNull`、`kotlin.uuid.Uuid`。

- [ ] **Step 2: 实现**（追加到 `SubAgentDelivery.kt`）

```kotlin
/** 结果正文的硬上限，与 `clipToolOutput` 的 100KB 惯例一致。 */
internal const val SUB_AGENT_RESULT_MAX_CHARS = 100_000

/** 一次投递的输入。`description` 为 null 时从工具结果里取。 */
internal data class TaskDelivery(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String?,
    val result: String?,
    val error: String?,
)

private fun clipTaskResult(text: String?): String? = text?.let {
    if (it.length <= SUB_AGENT_RESULT_MAX_CHARS) it else it.take(SUB_AGENT_RESULT_MAX_CHARS) + "[truncated]"
}

private fun UIMessagePart.Tool.outputText(): String =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/**
 * 本次投递要改写的工具结果：`sub_agent` 且输出 JSON 里 `task_id` 命中。
 *
 * 用内嵌 `task_id` 作锚点是因为 `Tool.execute` 拿不到自己的 `toolCallId`
 * （签名是 `suspend (JsonElement) -> List<UIMessagePart>`）。
 */
private fun UIMessagePart.Tool.matchesTask(taskId: String): Boolean =
    toolName == SUB_AGENT_TOOL_NAME && outputText().contains("\"task_id\":\"$taskId\"")

/**
 * 把一次终态投递写进会话：改写工具结果（只留状态）+ 追加一条可见标记。
 *
 * 返回 `null` 表示无需变更——找不到对应工具结果，或者该任务的标记已经在会话里（幂等）。
 * 结果正文只进标记 metadata，**绝不进工具结果**（决策 5）。
 *
 * `markerText` 是**函数**而不是成品字符串：可见文案要过 `stringResource`（Android 资源在本文件里用不了），
 * 而 description 可能来自工具结果（`delivery.description` 为 null 时）——所以由调用方拿着解析后的
 * description 去拼文案。中断对账那条路径就是靠它才能显示出子代理的名字而不是 taskId。
 */
internal fun Conversation.applyTaskDelivery(
    delivery: TaskDelivery,
    markerText: (description: String) -> String,
): Conversation? {
    if (messageNodes.any { node -> node.messages.any { it.subAgentTaskMarkerOrNull()?.taskId == delivery.taskId } }) {
        return null
    }

    var matched = false
    val clipped = clipTaskResult(delivery.result)
    val clippedError = clipTaskResult(delivery.error)
    var description = delivery.description

    val newNodes = messageNodes.map { node ->
        val newMessages = node.messages.map { message ->
            val newParts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !part.matchesTask(delivery.taskId)) return@map part
                matched = true
                val original = runCatching {
                    JsonInstant.parseToJsonElement(part.outputText()).jsonObject.toMutableMap()
                }.getOrNull()
                if (description == null) {
                    description = original?.get("description")?.jsonPrimitive?.contentOrNull
                }
                val rewritten = buildJsonObject {
                    // status/reason/task_id 由本次投递决定；result/error 一律不留在工具结果里（决策 5）
                    original?.forEach { (key, value) ->
                        if (key !in setOf("status", "reason", "task_id", "result", "error")) {
                            put(key, value)
                        }
                    }
                    put("type", JsonPrimitive(SUB_AGENT_TOOL_NAME))
                    put("status", JsonPrimitive(delivery.status))
                    if (delivery.reason != null) put("reason", JsonPrimitive(delivery.reason))
                    put("task_id", JsonPrimitive(delivery.taskId))
                    put("description", JsonPrimitive(description ?: delivery.taskId))
                }
                // 保留可能存在的非文本部件（子代理目前只产出文本，但别在这里埋雷）
                part.copy(
                    output = listOf(UIMessagePart.Text(rewritten.toString())) +
                        part.output.filter { it !is UIMessagePart.Text },
                )
            }
            if (newParts == message.parts) message else message.copy(parts = newParts)
        }
        if (newMessages == node.messages) node else node.copy(messages = newMessages)
    }

    if (!matched) return null

    val effectiveDescription = description ?: delivery.taskId
    val marker = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = markerText(effectiveDescription),
                metadata = buildJsonObject {
                    put(SUB_AGENT_TASK_METADATA_KEY, buildJsonObject {
                        put("taskId", JsonPrimitive(delivery.taskId))
                        put("status", JsonPrimitive(delivery.status))
                        put("reason", delivery.reason?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("description", JsonPrimitive(effectiveDescription))
                        put("result", clipped?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("error", clippedError?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                },
            ),
        ),
    )

    return copy(messageNodes = newNodes + marker.toMessageNode())
}

/**
 * 中断对账的判据：工具结果仍是 `started` **且** registry 里没有对应的存活任务。
 *
 * 一条判据同时覆盖「进程被回收后重启」与「任务在运行中丢失」；改写后 `status` 变终态，
 * 所以这条判据天然幂等。
 */
internal fun List<UIMessage>.interruptedSubAgentTaskIds(isLive: (String) -> Boolean): List<String> {
    val ids = mutableListOf<String>()
    forEach { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().forEach { part ->
            if (part.toolName != SUB_AGENT_TOOL_NAME) return@forEach
            val json = runCatching { JsonInstant.parseToJsonElement(part.outputText()).jsonObject }.getOrNull() ?: return@forEach
            if (json["status"]?.jsonPrimitive?.contentOrNull != "started") return@forEach
            val taskId = json["task_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (!isLive(taskId)) ids += taskId
        }
    }
    return ids
}
```

本任务需要的 import 追加：`kotlinx.serialization.json.JsonNull`、`kotlinx.serialization.json.buildJsonObject`、`kotlinx.serialization.json.JsonPrimitive`、`kotlinx.serialization.json.jsonObject`、`me.rerere.rikkahub.data.model.Conversation`、`me.rerere.rikkahub.data.model.toMessageNode`、`me.rerere.rikkahub.utils.JsonInstant`。

> **不需要 `import kotlinx.serialization.json.put`**：本文件里 `put(key, value)` 的 value 全是 `JsonElement`，解析到的是 `JsonObjectBuilder` 的**成员**重载；只有 `put(key, String)` 那种才需要扩展函数（测试文件里用到了，所以测试文件有它）。

- [ ] **Step 3: CI 验证**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/SubAgentDelivery.kt \
        app/src/test/java/me/rerere/rikkahub/service/SubAgentDeliveryTest.kt
git commit -m "feat(subagent): 投递期纯逻辑——工具结果改写（不含结果正文）、标记落地、中断判据"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`，`:app:testDebugUnitTest` 执行且无 skipped。

---

### Task 4: 待触发投递队列 `TaskDeliveryQueue`

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/TaskDeliveryQueue.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/TaskDeliveryQueueTest.kt`

**Interfaces:**
- Consumes: 无
- Produces：`class TaskDeliveryQueue { fun enqueue(taskId: String): Boolean; fun peek(): String?; fun takeNext(): String?; fun remove(taskId: String): Boolean; fun contains(taskId: String): Boolean; fun size(): Int; fun clear() }`

> 设计要点：这个队列**只决定顺序与去重**。终态入库是幂等的（Task 3 的标记判据），所以队列内容丢了也不会丢信息——进程重启后队列为空，靠对账与会话里的标记重新判定。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDeliveryQueueTest {

    @Test
    fun `先进先出`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.enqueue("sub_b")

        assertEquals("sub_a", queue.peek())
        assertEquals("sub_a", queue.takeNext())
        assertEquals("sub_b", queue.takeNext())
        assertNull(queue.takeNext())
    }

    @Test
    fun `同一个任务只入队一次`() {
        val queue = TaskDeliveryQueue()

        assertTrue(queue.enqueue("sub_a"))
        assertFalse(queue.enqueue("sub_a"))
        assertEquals(1, queue.size())
    }

    @Test
    fun `取出后再入队是允许的`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.takeNext()

        assertTrue(queue.enqueue("sub_a"))
        assertEquals("sub_a", queue.peek())
    }

    @Test
    fun `remove 只删指定任务并报告是否删到`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.enqueue("sub_b")

        assertTrue(queue.remove("sub_b"))
        assertFalse(queue.remove("sub_b"))
        assertEquals(1, queue.size())
        assertEquals("sub_a", queue.peek())
    }

    @Test
    fun `contains 与 clear`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")

        assertTrue(queue.contains("sub_a"))
        assertFalse(queue.contains("sub_missing"))
        queue.clear()
        assertTrue(queue.size() == 0)
        assertFalse(queue.contains("sub_a"))
    }
}
```

- [ ] **Step 2: 实现**

```kotlin
package me.rerere.rikkahub.service

/**
 * 每会话的「待触发投递」队列：哪个 taskId 该在会话空闲时开一轮生成。
 *
 * 只负责顺序与去重。真正的终态入库由 `ChatService.deliverTaskResult` 做，且是幂等的
 * （判据是会话里标记的位置），所以这个队列在进程退出后为空是安全的——对账会重新判定。
 */
class TaskDeliveryQueue {
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun enqueue(taskId: String): Boolean {
        // kotlin.collections.ArrayDeque.addLast 返回 Unit，不能直接当表达式返回值用
        if (pending.contains(taskId)) return false
        pending.addLast(taskId)
        return true
    }

    @Synchronized
    fun peek(): String? = pending.firstOrNull()

    @Synchronized
    fun takeNext(): String? = if (pending.isEmpty()) null else pending.removeFirst()

    @Synchronized
    fun remove(taskId: String): Boolean = pending.remove(taskId)

    @Synchronized
    fun contains(taskId: String): Boolean = pending.contains(taskId)

    @Synchronized
    fun size(): Int = pending.size

    @Synchronized
    fun clear() = pending.clear()
}
```

- [ ] **Step 3: CI 验证**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/TaskDeliveryQueue.kt \
        app/src/test/java/me/rerere/rikkahub/service/TaskDeliveryQueueTest.kt
git commit -m "feat(subagent): 每会话待触发投递队列（顺序 + 去重）"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`。

---

### Task 5: `ConversationSession` 加 `loaded` 与投递队列

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/ConversationSessionTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `TaskDeliveryQueue`
- Produces：`ConversationSession.loaded: Boolean`（可写，默认 false）、`ConversationSession.taskDeliveries: TaskDeliveryQueue`

- [ ] **Step 1: 写失败测试**（追加到 `ConversationSessionTest`）

```kotlin
    @Test
    fun `loaded 默认为 false 且可置位`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertFalse(session.loaded)
            session.loaded = true
            assertTrue(session.loaded)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `待触发的任务投递不把会话标记为在用`() = runBlocking {
        // §5.4：投递物是「写进会话的记录」，不需要靠钉住会话保住——所以刻意不纳入 isInUse。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.taskDeliveries.enqueue("sub_1")
            assertFalse("投递队列不该让会话常驻（靠 ensureLoaded 而不是钉住）", session.isInUse)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
```

- [ ] **Step 2: 实现**（`ConversationSession.kt`）

在 `val messageQueue = MessageQueue()` 之后追加：

```kotlin
    /**
     * 会话真实内容是否已从库里载入。
     *
     * `getOrCreateSession` 建的是**空壳**（`Conversation.ofId()`），只有 `initializeConversation`
     * 或 `ChatService.ensureLoaded` 才会把真实内容填进来。任何要改会话内容的背景路径
     * （子代理投递、中断对账）都必须先看这个标志——否则会拿空壳去 `saveConversation`，
     * 而 `ConversationRepository.updateConversation` 是「删光节点再写」，等于抹掉历史。
     */
    @Volatile
    var loaded: Boolean = false

    /** 待触发的子代理投递（按任务粒度，逐个开一轮）。刻意不纳入 [isInUse]，见 memory/设计 §5.4。 */
    val taskDeliveries = TaskDeliveryQueue()
```

- [ ] **Step 3: CI 验证**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt \
        app/src/test/java/me/rerere/rikkahub/service/ConversationSessionTest.kt
git commit -m "feat(subagent): ConversationSession 记录载入状态与待触发投递"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`。

---

### Task 6: `GenerationKeepAlive` + 前台服务通知文案

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/GenerationKeepAlive.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatGenerationForegroundService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（`launchGenerationJob` 换用封装）
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/me/rerere/rikkahub/service/GenerationKeepAliveTest.kt`

**Interfaces:**
- Consumes: 无
- Produces：
  - `class GenerationKeepAlive(context: Application) { fun hold(conversationId: Uuid, backgroundTask: Boolean = false): Uuid?; fun release(token: Uuid?) }`
  - `internal fun foregroundNotificationLabelRes(activeBackgroundFlags: Collection<Boolean>): Int`
  - `ChatGenerationForegroundService.acquire(context, generationId, conversationId, backgroundTask: Boolean = false): Boolean`（新增参数带默认值，旧调用点不受影响）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.service

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationKeepAliveTest {

    @Test
    fun `只有后台任务时显示后台任务文案`() {
        assertEquals(
            R.string.notification_sub_agent_running,
            foregroundNotificationLabelRes(listOf(true, true)),
        )
    }

    @Test
    fun `只要有一个聊天生成就显示生成中文案`() {
        assertEquals(
            R.string.notification_live_update_title,
            foregroundNotificationLabelRes(listOf(true, false)),
        )
        assertEquals(
            R.string.notification_live_update_title,
            foregroundNotificationLabelRes(listOf(false)),
        )
    }
}
```

- [ ] **Step 2: 实现 `GenerationKeepAlive`**

```kotlin
package me.rerere.rikkahub.service

import android.app.Application
import kotlin.uuid.Uuid

/**
 * 前台服务持有权的薄封装：`acquire`/`release` 配对，token 即持有者 id。
 *
 * `ChatGenerationForegroundService` 本来就是按 id 引用计数的（`activeGenerations` 空了才停服务），
 * 所以聊天生成与后台子代理任务可以共用它，不需要各自的常驻通知。
 */
class GenerationKeepAlive(private val context: Application) {

    /** 返回 null 表示没能进前台（例如系统前台服务配额耗尽）——调用方继续跑但不受保护。 */
    fun hold(conversationId: Uuid, backgroundTask: Boolean = false): Uuid? {
        val token = Uuid.random()
        val started = ChatGenerationForegroundService.acquire(
            context = context,
            generationId = token,
            conversationId = conversationId,
            backgroundTask = backgroundTask,
        )
        return token.takeIf { started }
    }

    fun release(token: Uuid?) {
        if (token == null) return
        ChatGenerationForegroundService.release(context, token)
    }
}
```

- [ ] **Step 3: 改前台服务**（`ChatGenerationForegroundService.kt`）

3a. 在文件顶层（`private const val TAG` 之后）加纯函数：

```kotlin
/**
 * 通知文案：只要有一个聊天生成在跑就显示「正在生成」，否则显示「后台任务运行中」。
 * 抽成顶层函数是为了能在 JVM 单测里钉住这条判定。
 */
internal fun foregroundNotificationLabelRes(activeBackgroundFlags: Collection<Boolean>): Int =
    if (activeBackgroundFlags.isNotEmpty() && activeBackgroundFlags.all { it }) {
        R.string.notification_sub_agent_running
    } else {
        R.string.notification_live_update_title
    }
```

3b. `companion object` 里的 `acquire` 加参数：

```kotlin
        private const val EXTRA_BACKGROUND_TASK = "background_task"

        fun acquire(
            context: Context,
            generationId: Uuid,
            conversationId: Uuid,
            backgroundTask: Boolean = false,
        ): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                putExtra(EXTRA_BACKGROUND_TASK, backgroundTask)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
            }.getOrDefault(false)
        }
```

3c. 持有者表改成带标记的结构。把这一行：

```kotlin
    private val activeGenerations = linkedMapOf<String, String>()
```

改成：

```kotlin
    private data class ActiveGeneration(val conversationId: String, val backgroundTask: Boolean)

    private val activeGenerations = linkedMapOf<String, ActiveGeneration>()
```

3d. `acquire(intent)` / `release(intent)` / `updateForegroundNotification`：

```kotlin
    private fun acquire(intent: Intent) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopService()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopService()
        val backgroundTask = intent.getBooleanExtra(EXTRA_BACKGROUND_TASK, false)
        activeGenerations[generationId] = ActiveGeneration(conversationId, backgroundTask)
        updateForegroundNotification()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            stopService()
        } else {
            updateForegroundNotification()
        }
    }

    private fun updateForegroundNotification() {
        val conversationId = activeGenerations.values.lastOrNull()?.conversationId
        val labelRes = foregroundNotificationLabelRes(activeGenerations.values.map { it.backgroundTask })
        try {
            val notification = buildNotification(conversationId, labelRes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }
```

`buildNotification` 签名改成：

```kotlin
    private fun buildNotification(conversationId: String?, labelRes: Int) =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(labelRes))
            .setContentIntent(conversationId?.let { getConversationPendingIntent(it) })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
```

（`setContentIntent` 接受 null 是合法的；`Compat` 用回 `NotificationCompat.CATEGORY_PROGRESS`。）

3e. `onTimeout` 里的：

```kotlin
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
```

改成：

```kotlin
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it.conversationId) }.getOrNull() }
```

- [ ] **Step 4: 加文案**

`res/values/strings.xml`（紧挨 `<string name="tool_ui_sub_agent_started">` 那一组之后）：

```xml
  <string name="notification_sub_agent_running">Background task running</string>
```

`res/values-zh/strings.xml`（同一位置）：

```xml
  <string name="notification_sub_agent_running">后台任务运行中</string>
```

- [ ] **Step 5: 注册到 Koin 并让 `launchGenerationJob` 用它**

`di/AppModule.kt` 在 `single(createdAtStart = true) { ChatNotificationManager(...) }` 之前插入：

```kotlin
    single {
        GenerationKeepAlive(get())
    }
```

并加 `import me.rerere.rikkahub.service.GenerationKeepAlive`。

`ChatService.kt`：构造函数里加参数（放在 `private val folderRepository: FolderRepository,` 之后）：

```kotlin
    private val keepAlive: GenerationKeepAlive,
```

`launchGenerationJob` 改成：

```kotlin
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
```

`di/AppModule.kt` 的 `ChatService(...)` 注册里加一行 `keepAlive = get(),`。

- [ ] **Step 6: CI 验证**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/GenerationKeepAlive.kt \
        app/src/main/java/me/rerere/rikkahub/service/ChatGenerationForegroundService.kt \
        app/src/main/java/me/rerere/rikkahub/service/ChatService.kt \
        app/src/main/java/me/rerere/rikkahub/di/AppModule.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
        app/src/test/java/me/rerere/rikkahub/service/GenerationKeepAliveTest.kt
git commit -m "feat(subagent): 前台服务持有权抽成 GenerationKeepAlive，通知文案区分后台任务"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`，`:app:testDebugUnitTest` 无 skipped。

---

### Task 7: `SubAgentRuntime` 接线 + 事件改名

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/event/AppEvent.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentRuntime.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（订阅处、pendingCount 复原）
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`

**Interfaces:**
- Consumes: Task 1 `SubAgentTaskRegistry` / `TaskStatus` / `SubAgentFailReason`；Task 6 `GenerationKeepAlive`
- Produces：
  - `AppEvent.SubAgentTaskFinished(conversationId: Uuid, taskId: String, description: String, status: TaskStatus, reason: SubAgentFailReason?, result: String?, error: String?)`
  - `SubAgentRuntime(providerManager, settingsStore, appScope, eventBus, registry, keepAlive)`
  - `SubAgentRuntime.cancel(taskId: String): Boolean`
  - `LocalTools.subAgentTaskRegistry: SubAgentTaskRegistry`

- [ ] **Step 1: 改事件**

`data/event/AppEvent.kt`：把 `SubAgentCompleted` 整段替换成：

```kotlin
    /**
     * 子代理任务到达终态（完成 / 失败 / 用户取消 / 进程中断对账）。
     *
     * [result] 是结果正文，**只给 AI**：ChatService 会把它写进标记 metadata，不写进工具结果、不展示给用户。
     */
    data class SubAgentTaskFinished(
        val conversationId: Uuid,
        val taskId: String,
        val description: String,
        val status: TaskStatus,
        val reason: SubAgentFailReason?,
        val result: String?,
        val error: String?,
    ) : AppEvent()
```

并加 import：`import me.rerere.rikkahub.data.ai.tools.local.SubAgentFailReason`、`import me.rerere.rikkahub.data.ai.tools.local.TaskStatus`。

- [ ] **Step 2: 改 `SubAgentRuntime`**

本步要**顺带删掉** Task 1 特意保留下来的旧状态（现在没有别的使用者了）：

```kotlin
    /** 所有异步任务的追踪状态 */
    private val tasks = ConcurrentHashMap<String, TaskInfo>()
```

以及末尾两个方法：

```kotlin
    /** 供 TaskList/TaskGet 工具读取的任务列表快照 */
    fun getTaskInfos(): List<TaskInfo> = tasks.values.toList()

    /** 供 TaskGet 工具读取的单个任务详情 */
    fun getTaskInfo(taskId: String): TaskInfo? = tasks[taskId]
```

还有 `data class TaskInfo(...)`。`ChatService` 里那处 `getTaskInfos()` 调用在 Step 4 换成 registry 的 `liveCount()`。

构造函数签名改成：

```kotlin
class SubAgentRuntime(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
    private val registry: SubAgentTaskRegistry,
    private val keepAlive: GenerationKeepAlive,
) {
```

`executeAsync` 整段替换成：

```kotlin
    fun executeAsync(
        prompt: String,
        description: String,
        conversationId: Uuid,
        modelOverride: Uuid? = null,
        tools: List<Tool> = emptyList(),
        systemPrompt: String? = null,
    ): AsyncSubAgentHandle {
        val taskId = "sub_${Uuid.random().toString().take(8)}"
        registry.register(
            taskId = taskId,
            conversationId = conversationId,
            description = description,
            prompt = prompt,
        )
        val job = appScope.launch {
            // 子代理的网络流必须自己持有前台服务：父生成一结束就会释放它，而任务可能还要跑很久。
            val token = keepAlive.hold(conversationId, backgroundTask = true)
            try {
                val result = executeSync(
                    prompt = prompt,
                    modelOverride = modelOverride,
                    tools = tools,
                    systemPrompt = systemPrompt,
                )
                finish(
                    taskId = taskId,
                    status = if (result.success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                    reason = null,
                    result = result.text.takeIf { result.success },
                    error = result.error.takeIf { !result.success },
                )
            } catch (e: CancellationException) {
                // 用户点了「取消任务」：仍然要留一条终态回执，否则会话里永远停在 started。
                withContext(NonCancellable) {
                    finish(
                        taskId = taskId,
                        status = TaskStatus.FAILED,
                        reason = SubAgentFailReason.USER_CANCELLED,
                        result = null,
                        error = "cancelled",
                    )
                }
                throw e
            } finally {
                keepAlive.release(token)
                jobs.remove(taskId)
            }
        }
        jobs[taskId] = job
        return AsyncSubAgentHandle(taskId = taskId, job = job)
    }

    /** 取消一个还在跑的任务。返回 false 表示任务不存在或已经到达终态。 */
    fun cancel(taskId: String): Boolean {
        val job = jobs.remove(taskId) ?: return false
        job.cancel()
        return true
    }

    private suspend fun finish(
        taskId: String,
        status: TaskStatus,
        reason: SubAgentFailReason?,
        result: String?,
        error: String?,
    ) {
        val info = registry.finish(taskId, status, reason, result, error) ?: return
        eventBus.emit(
            AppEvent.SubAgentTaskFinished(
                conversationId = info.conversationId,
                taskId = info.taskId,
                description = info.description,
                status = status,
                reason = reason,
                result = result,
                error = error,
            )
        )
    }
```

类体里加：

```kotlin
    /** 正在跑的 job，用于「取消任务」。与 registry 同生命周期，两者一起在终态时移除。 */
    private val jobs = ConcurrentHashMap<String, Job>()
```

新增 import：`kotlinx.coroutines.NonCancellable`、`kotlinx.coroutines.withContext`、`java.util.concurrent.ConcurrentHashMap`、`me.rerere.rikkahub.service.GenerationKeepAlive`。

- [ ] **Step 3: `LocalTools` 构造与暴露**

```kotlin
    val subAgentTaskRegistry = SubAgentTaskRegistry()

    val subAgentRuntime by lazy {
        SubAgentRuntime(
            providerManager = providerManager,
            settingsStore = settingsStore,
            appScope = appScope,
            eventBus = eventBus,
            registry = subAgentTaskRegistry,
            keepAlive = keepAlive,
        )
    }
```

构造函数加 `private val keepAlive: GenerationKeepAlive,`（放在 `private val appScope: AppScope,` 之后），并加 import（`me.rerere.rikkahub.service.GenerationKeepAlive`、`SubAgentTaskRegistry` 同包不需 import）。

`di/AppModule.kt` 的 `LocalTools(get(), get(), get(), get(), get(), get(), get())` 改成多一个 `get()`：

```kotlin
    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get())
    }
```

> **更正（Task 7 实现者指出，已核实）**：位置顺序**不**需要刻意对齐。Koin 的 `get()` 是 `inline fun <reified T : Any> get(): T`，`T` 由**该位置形参的声明类型**推断，所以每个 `get()` 解析成什么类型只取决于「它是第几个实参」，与书写顺序无关——把两个 `get()` 对调会产生完全相同的代码。要保证的是**实参个数与形参个数一致**（多一个或少一个才是编译错误）。原话「位置顺序必须一致，否则编译报错」把机制说错了；结论（在 `keepAlive` 形参的位置插一个新 `get()`）仍然正确，就是实现者所做的。

- [ ] **Step 4: 改订阅方与 `RouteActivity`**

`ChatService.kt` 的 `init` 块：

```kotlin
                if (event is AppEvent.SubAgentTaskFinished) {
                    handleSubAgentRecall(event)
                }
```

`handleSubAgentRecall` 里 `event.success` 是**三处**（已实地核对：`:719` 的 `statusText`、`:741` 的 `fireRecall(...)` 实参、`:745` 的 `PendingRecall(success = ...)`），全部改成 `event.status == TaskStatus.COMPLETED`；`event.result` 现在是可空的，`:729` 的 `<result>` 那行改成 `event.result ?: event.error ?: ""`。

> 新事件**丢掉 `prompt` 字段是安全的**：已 grep 全仓，`SubAgentCompleted` 的消费者只有 `ChatService`（`:204` / `:715`）与 `RouteActivity`（`:281`），无一处读 `prompt`。
> `Step 2` 的 import 清单里 `java.util.concurrent.ConcurrentHashMap` **本文件已经 import 过**（`:22`），不要再加一遍——删掉 `tasks` 之后它依然需要，因为新的 `jobs` 也用它。

pendingCount 那行复原成：

```kotlin
                val pendingCount = localTools.subAgentTaskRegistry.liveCount()
```

`RouteActivity.kt` 的 `is AppEvent.SubAgentCompleted -> Unit` 改成 `is AppEvent.SubAgentTaskFinished -> Unit`。

- [ ] **Step 5: CI 验证**

```bash
# 只加本任务改的 6 个文件。**不要用 `git add -A`**：若探针/临时文件落在仓库内会被一并提交。
git add app/src/main/java/me/rerere/rikkahub/data/event/AppEvent.kt \
        app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SubAgentRuntime.kt \
        app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt \
        app/src/main/java/me/rerere/rikkahub/service/ChatService.kt \
        app/src/main/java/me/rerere/rikkahub/RouteActivity.kt \
        app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
git commit -m "feat(subagent): runtime 接入任务登记表与保活，事件改为带终态与原因的 SubAgentTaskFinished" \
           -m "（一两句说清「为什么」）" \
           -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`。

**这一步之后旧 recall 机制仍在跑（只是换了事件类型），刻意留到 Task 8 才删，方便二分。**

> 但**「行为与今天完全一致」并不严格成立**，别为了让它们一致而回退这两条——它们正是本任务要引入的行为：
> ① 用户取消时会补一条 `USER_CANCELLED` 终态事件（今天取消什么都不发，会话里永远停在 `started`）；
> ② 用 `keepAlive.hold` 自己持有前台服务（今天子代理在父生成结束、父释放 FGS 之后没有任何保护）。

---

### Task 8: `ChatService` 接入新投递通道，删除旧机制

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

**Interfaces:**
- Consumes: Task 2/3 的全部纯函数、Task 5 的 `loaded`/`taskDeliveries`、Task 7 的 `SubAgentTaskFinished`
- Produces：`ChatService.cancelSubAgentTask(taskId: String)`、`ChatService.deliverTaskResult(event)`、`ChatService.ensureLoaded(conversationId)`

- [ ] **Step 1: 加 `ensureLoaded`（**不**拆共用方法）**

**① `initializeConversation`**（`ChatService.kt:360` 起）**保持原样，只在末尾补一行**：

```kotlin
    suspend fun initializeConversation(conversationId: Uuid, folderId: Uuid? = null) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val baseConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            )
            val newConversation = (if (folderId != null) baseConversation.copy(folderId = folderId) else baseConversation)
                .updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
        // 必须置位：§6.1 明写「initializeConversation 完成后置 true」。漏了这行的后果不是「慢一点」，
        // 而是 `ensureLoaded` 之后每次都会以为会话没载入过，于是**每条投递都重新读库 + updateConversation
        // 整段替换内存态**——用户正在生成时会把还没落盘的流式内容冲掉（正是 §6.1 警告的那件事）。
        getOrCreateSession(conversationId).loaded = true
        // 补这一行：这条路径是「软件退出 / 子代理中断」失败回执的**主要**来源——新进程里 registry
        // 是空的，用户打开那个会话时工具结果仍停在 started，正是这里把它补成终态。
        // 静默：只补标记不触发生成（决策 2），AI 下次在这个会话里发言时由派生通知看到。
        reconcileInterruptedSubAgentTasks(conversationId)
    }
```

**② 新增 `ensureLoaded`**：

```kotlin
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
```

> **为什么不按原计划「拆出 `loadConversation` 给两条路径共用」**：两者的不变量是**相反**的。`initializeConversation` 是「用户打开了会话」——必须**总是**重载、并且**总要**切全局助手；`ensureLoaded` 是背景路径——**已载入就绝不重载**（§6.1：内存态在生成期间领先于库，重载会把正在生成的内容冲掉），**绝不**切全局助手，而且还要在锁内复查 `loaded`。把这些藏进 `selectAssistant` / `createIfMissing` 两个布尔开关里，改一处就可能悄悄改掉另一条路径的语义。两处真正必须共有的只有末尾那一行对账，所以它在两边各写一次、各带一句为什么——重复 4 行，换来两条路径的不变量各自局部可见。

- [ ] **Step 2: 中断对账**

在 `ensureLoaded` 之后加：

```kotlin
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
        val interrupted = conversation.currentMessages.interruptedSubAgentTaskIds { taskId ->
            localTools.subAgentTaskRegistry.get(taskId) == null
        }
        if (interrupted.isEmpty()) return

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
            saveConversation(conversationId, current)
        }
    }
```

**（控制器裁定，Task 8 修复轮补入）**

> **同时要把 `SubAgentDelivery.kt` 的参数改名**：`internal fun List<UIMessage>.interruptedSubAgentTaskIds(isLive: (String) -> Boolean)` → `isTracked`，函数体 `if (!isLive(taskId)) ids += taskId` → `if (!isTracked(taskId)) ids += taskId`。
> 这不是洁癖：**这个缺陷的根因就是那个名字**——`isLive` 让调用方以为「不存活 ⇒ 判定中断」，而正确的问法是「本进程是否登记过它」。保留旧名，下一个人很可能把 `isLive` 再传回来，于是同一个 bug 以「无冲突、编译器不报、测试不报」的方式复活。
> `SubAgentDeliveryTest` 的四处调用都是**尾随 lambda 位置传参**（无具名实参），所以**测试文件无需任何改动**；`SubAgentTaskRegistry.isLive` 本身语义不变（它仍表示「在跑」），只有这个谓词参数的**问法**变了。

> **本步需要的 import**：`ChatService.kt` 要加 `import me.rerere.rikkahub.data.ai.tools.local.SubAgentFailReason`（`reconcileInterruptedSubAgentTasks` 与 `taskMarkerText` 都用到）。`TaskStatus` 若也报未解析则一并加；两者都在 `data.ai.tools.local` 包。

- [ ] **Step 3: 投递入口**

在 `reconcileInterruptedSubAgentTasks` 之后加：

```kotlin
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
        awaitIdle(session)

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
            // 无变更 = 这条结果已被消化过（标记已存在，或工具结果已不在）。记一行日志：
            // 现场只有这一行能看出「投递被跳过」。
            Log.w(TAG, "deliverTaskResult: ${event.taskId} 无需变更，跳过（已投递或工具结果已不存在）")
            return
        }

        // 先在**内存**里落一次，再落库：`saveConversation` 内的 `updateConversation` 在它自己的一次
        // 挂起读库**之后**才执行，两条并发投递会双双读到「还不含对方标记」的内存态，后写者会把先写者
        // 从内存与库里一起抹掉。先同步写内存可保证第二条投递读到的是「已含第一条标记」的状态。
        updateConversation(event.conversationId, updated)

        // 只有来自「实时完成」的投递才触发新的一轮；中断对账走的不是这条入口。
        session.taskDeliveries.enqueue(event.taskId)
        saveConversation(event.conversationId, updated)
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
```

> `saveConversation` 最后会调 `advanceConversation`，投递的触发就发生在那里（先 `enqueue` 再 `save` 是刻意的顺序）。

- [ ] **Step 4: 把 `dispatchNextQueuedMessage` 改成 `advanceConversation` 并加投递分支**

整段替换：

```kotlin
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
        val taskId = session.taskDeliveries.peek() ?: return null
        val stillPending = session.state.value.currentMessages.pendingTaskMarkers().any { it.taskId == taskId }
        session.taskDeliveries.takeNext()
        if (!stillPending) {
            // 这条结果已经被后续回复消化掉了（例如用户中途说了话），不必再开一轮。
            return startTaskDelivery(session)
        }
        val job = launchGenerationJob(
            conversationId = session.id,
            keepAliveInBackground = true,
        ) {
            handleMessageComplete(session.id)
        }
        session.setJob(job, cancelPrevious = false)
        job.invokeOnCompletion { appScope.launch { advanceConversation(session.id) } }
        return job
    }
```

然后把全文件里所有 `dispatchNextQueuedMessage(` 换成 `advanceConversation(`（`git grep -n "dispatchNextQueuedMessage" -- app/src/main` 应清零）。

- [ ] **Step 5: 注入逻辑改为派生**

`handleMessageComplete` 里这段（约 `:884-898`）：

`generationLoop.generateText(...)` 里 `messages =` 这个实参**整段**目前长这样（原文照抄，含全部嵌套）：

```kotlin
                messages = conversation.currentMessages.let { raw ->
                    val base = if (messageRange != null) {
                        raw.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        raw
                    }
                    // 注入用户不可见的子代理完成通知，固定在触发 recall 时的位置
                    val notes = pendingNotifications[conversationId].orEmpty()
                    if (notes.isEmpty()) {
                        base
                    } else {
                        val offset = messageRange?.start ?: 0
                        val withNotes = base.toMutableList()
                        notes.sortedByDescending { it.insertedAt }.forEach { note ->
                            val pos = note.insertedAt - offset
                            if (pos in 0..withNotes.size) {
                                withNotes.add(pos, UIMessage.system(prompt = note.xml))
                            }
                        }
                        withNotes
                    }
                },
```

整段替换成：

```kotlin
                messages = conversation.currentMessages.let { raw ->
                    val base = if (messageRange != null) {
                        raw.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        raw
                    }
                    // 按标记派生待汇报的子代理任务通知（不落库，只存在于这一次请求）
                    injectTaskNotifications(base, localTools.subAgentTaskRegistry.liveCount())
                },
```

- [ ] **Step 6: 删除旧机制**

删除以下全部内容（`git grep -n "pendingNotifications\|pendingRecall\|handleSubAgentRecall\|fireRecall\|setSessionJob\|checkPendingRecall\|SubAgentNotification\|PendingRecall" -- app/src/main` 应清零）：

1. 字段与内部类：

```kotlin
    // 子代理完成通知（用户不可见，仅注入 AI 上下文）
    private data class SubAgentNotification(
        val insertedAt: Int,
        val xml: String,
    )
    private val pendingNotifications = ConcurrentHashMap<Uuid, MutableList<SubAgentNotification>>()

    // 子代理 pending recall（存事件数据，待生成结束后触发）
    private data class PendingRecall(
        val description: String,
        val success: Boolean,
    )
    private val pendingRecall = ConcurrentHashMap<Uuid, PendingRecall>()
```

2. `init` 块改为：

```kotlin
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
```

3. 函数 `handleSubAgentRecall`、`fireRecall`、`setSessionJob`、`checkPendingRecall` 整段删除。

4. `job.invokeOnCompletion { checkPendingRecall(conversationId) }` —— **原计划只列了 `sendQueuedMessage` 一处，实为三处**（另两处在 `regenerateAtMessage`、`handleToolApproval`，都是各自 `session.setJob(job)` 之后的兜底回调）。三处全删（`advanceConversation` 已由生成结束回调负责）。**硬判据**：删完后 `git grep -n "checkPendingRecall" -- app/src/main` 必须清零——漏一处编译不过。

5. `getOrCreateSession` 的 `onGenerationFinished` 里那行 `appScope.launch { dispatchNextQueuedMessage(id) }` 现在是 `appScope.launch { advanceConversation(id) }`（Step 4 的全局替换已覆盖）。

6. `onGenerationFinished` 里的 `session.messageQueue.pause()` 等逻辑**保持不动**。

**（Fix 轮 2 附带，控制器裁定）**：`handleMessageComplete` 的 `.collect` 里那句过滤目前写的是**字面量** `part.text.contains("<task-notification>")`。改成用 `SubAgentDelivery.kt` 里已有的 `TASK_NOTIFICATION_TAG` 常量。这不是洁癖：这条过滤是「派生通知只存在于请求里、绝不写回会话」那道边界的**唯一**守卫，而字面量与常量会各自漂移——一旦有人改常量、或将来有 transformer 重写该文本，注入块就会变成**持久化的可见消息**。该字面量是既有代码（非本任务引入），顺手一并改。

- [ ] **Step 7: ChatVM 暴露取消**

`ui/pages/chat/ChatVM.kt` 加：

```kotlin
    fun cancelSubAgentTask(taskId: String) = chatService.cancelSubAgentTask(taskId)
```

- [ ] **Step 8: 加文案**

`res/values/strings.xml`：

```xml
  <string name="sub_agent_task_marker_finished">Agent \"%1$s\" finished</string>
  <string name="sub_agent_task_marker_failed">Agent \"%1$s\" failed</string>
  <string name="sub_agent_task_marker_cancelled">Agent \"%1$s\" cancelled</string>
  <string name="sub_agent_task_marker_interrupted">Agent \"%1$s\" interrupted (app exited)</string>
  <string name="sub_agent_error_app_exit">App exited or the background process was reclaimed before the task finished.</string>
```

`res/values-zh/strings.xml`：

```xml
  <string name="sub_agent_task_marker_finished">Agent \"%1$s\" 已完成</string>
  <string name="sub_agent_task_marker_failed">Agent \"%1$s\" 失败</string>
  <string name="sub_agent_task_marker_cancelled">Agent \"%1$s\" 已取消</string>
  <string name="sub_agent_task_marker_interrupted">Agent \"%1$s\" 已中断（应用退出）</string>
  <string name="sub_agent_error_app_exit">应用已退出或后台进程在任务完成前被回收。</string>
```

> **上面代码块里的 `\"` 就是正确写法，照抄即可，不要改。** aapt2 的字符串资源支持反斜杠转义，`\"` 是合法的双引号写法；本仓 `res/values/strings.xml` 里已有 **5 处** `\"`（如 `sub_agents_page_delete_message`、`history_page_delete_conversation_confirm`）而 **0 处** `&quot;`。（原先这里写的「`\"` 不是合法转义、改用 `&quot;`」是本控制器写错的：它会把正确的代码改成与本仓惯例不一致的写法。）

- [ ] **Step 9: CI 验证**

```bash
# 显式列出本任务改的文件。**不要用 `git add -A`**：探针/临时文件若落在仓库内会被一并提交。
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt \
        app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(subagent): 投递走统一两步通道（终态入库 + 保活触发），删除 pendingNotifications 与单槽位 recall" \
           -m "（一两句说清「为什么」）" \
           -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`，`:app:testDebugUnitTest` 全部通过（含 Task 1–6 新增的用例）。

---

### Task 9: 卡片终态与取消入口

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: Task 8 的 `ChatService.cancelSubAgentTask` / `ChatVM.cancelSubAgentTask`；工具结果 JSON 里的 `status` / `reason` / `task_id`
- Produces：`interface SubAgentTaskActions { fun cancel(taskId: String) }`、`val LocalSubAgentTaskActions = staticCompositionLocalOf<SubAgentTaskActions?> { null }`

- [ ] **Step 1: 加动作通道**

在 `BuiltinToolUIs.kt` 的 `SubAgentToolUI` 定义之前插入：

```kotlin
/** 子代理卡片的动作通道：由 ChatPage 提供，避免把回调一层层穿过 ChatList/ChatMessage。 */
interface SubAgentTaskActions {
    fun cancel(taskId: String)
}

val LocalSubAgentTaskActions = staticCompositionLocalOf<SubAgentTaskActions?> { null }
```

- [ ] **Step 2: 卡片显示终态与原因，并在运行中提供取消**

`SubAgentToolUI.Preview` 里，把状态 pill 那一段：

```kotlin
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "started" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_started))
                    "completed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_completed))
                    "failed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_failed))
                }
                taskId?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
```

替换成：

```kotlin
            val reason = content.getStringContent("reason")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "started" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_started))
                    "completed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_completed))
                    "failed" -> ToolPill(
                        when (reason) {
                            "user_cancelled" -> stringResource(R.string.tool_ui_sub_agent_cancelled)
                            "app_exit" -> stringResource(R.string.tool_ui_sub_agent_interrupted)
                            else -> stringResource(R.string.tool_ui_sub_agent_failed)
                        },
                    )
                }
                taskId?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
            // 失败时只显示分类短文案；原始 error 正文在标记 metadata 里，只给 AI（决策 5）
            if (status == "failed") {
                val reasonLabel = when (reason) {
                    "user_cancelled" -> stringResource(R.string.tool_ui_sub_agent_reason_user_cancelled)
                    "app_exit" -> stringResource(R.string.tool_ui_sub_agent_reason_app_exit)
                    else -> stringResource(R.string.tool_ui_sub_agent_reason_error)
                }
                Text(
                    text = reasonLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val taskIdForCancel = taskId
            if (status == "started") {
                val actions = LocalSubAgentTaskActions.current
                if (actions != null && !taskIdForCancel.isNullOrBlank()) {
                    TextButton(onClick = { actions.cancel(taskIdForCancel) }) {
                        Text(stringResource(R.string.tool_ui_sub_agent_cancel_action))
                    }
                }
            }
```

> **注意**：`SubAgentToolUI.Preview` 里原有的 `if (!error.isNullOrBlank()) { Text(error, …) }` 与 `if (!result.isNullOrBlank()) { ToolTerminalOutput(result) }` 两段**保持原样**——异步结果正文已不在工具结果里，自然不会显示；同步子代理的结果仍要能读（决策 5）。同时确认该文件有 `stringResource`、`TextButton`、`MaterialTheme`、`Arrangement` 的 import，缺哪个补哪个。

- [ ] **Step 3: ChatPage 提供实现**

`ui/pages/chat/ChatPage.kt` 里 `ChatList(` 那个调用点（约 `:445`）外面包一层：

```kotlin
            CompositionLocalProvider(
                LocalSubAgentTaskActions provides remember(vm) {
                    object : SubAgentTaskActions {
                        override fun cancel(taskId: String) = vm.cancelSubAgentTask(taskId)
                    }
                },
            ) {
                ChatList(
                    ...   // 原样保留
                )
            }
```

加 import：`androidx.compose.runtime.CompositionLocalProvider`、`me.rerere.rikkahub.ui.components.message.tools.LocalSubAgentTaskActions`、`me.rerere.rikkahub.ui.components.message.tools.SubAgentTaskActions`。

- [ ] **Step 4: 加文案**

`res/values/strings.xml`：

```xml
  <string name="tool_ui_sub_agent_cancelled">Cancelled</string>
  <string name="tool_ui_sub_agent_interrupted">Interrupted</string>
  <string name="tool_ui_sub_agent_reason_user_cancelled">Cancelled by user</string>
  <string name="tool_ui_sub_agent_reason_app_exit">App exited before the task finished</string>
  <string name="tool_ui_sub_agent_reason_error">Model or network error</string>
  <string name="tool_ui_sub_agent_cancel_action">Cancel task</string>
```

`res/values-zh/strings.xml`：

```xml
  <string name="tool_ui_sub_agent_cancelled">已取消</string>
  <string name="tool_ui_sub_agent_interrupted">已中断</string>
  <string name="tool_ui_sub_agent_reason_user_cancelled">用户取消</string>
  <string name="tool_ui_sub_agent_reason_app_exit">应用退出，任务未完成</string>
  <string name="tool_ui_sub_agent_reason_error">模型或网络错误</string>
  <string name="tool_ui_sub_agent_cancel_action">取消任务</string>
```

- [ ] **Step 5: CI 验证**

```bash
git add -A
git commit -m "feat(subagent): 卡片显示终态与失败分类，运行中可取消任务"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run view <run-id> --json conclusion,headSha
gh run view <run-id> --json jobs
```

Expected: `conclusion = "success"`。

- [ ] **Step 6: 交设备核验**

CI 绿之后装 debug 包，按 spec §10.3 的 8 条清单验。**其中第 1 条（离开会话后历史完整）是本轮最重要的一条**——它是缺陷①的正反面证据。

---

## 自检记录

**Spec 覆盖**：§4.2 六个缺陷 → ①=Task 8（`ensureLoaded` + 对账）、②=Task 6/7（keepAlive）、③=Task 3+8（中断判据 + 对账）、④=Task 2+8（派生通知替代 pendingNotifications）、⑤=Task 4+8（FIFO 替代单槽位）、⑥=Task 3+9（工具结果改写 + 卡片终态）；§5 组件 → Task 1/2/3/4/6；§6 → Task 3；§7 → Task 2+8 Step 5；§8 → Task 1+3+8；§9 影响面 → 全部任务；§10.1 单测 → Task 1–6；§10.3 设备核验 → Task 9 Step 6；§10.2 CI → 每任务末步。

**Task 7 实现者指出的机制性错误**（控制器裁定，已记账）：我在 Task 7 Step 3 写的「位置顺序必须与构造参数顺序一致，否则编译报错」把机制说错了 —— Koin 的 `get()` 是 `inline fun <reified T : Any> get(): T`，`T` 由该位置**形参的声明类型**推断，因此每个 `get()` 的类型只取决于它的实参位置，与书写顺序无关（对调两个 `get()` 产生相同代码）；真正会编译报错的是实参个数与形参个数不符。结论（在 `keepAlive` 形参处插一个新 `get()`）不受影响。已把计划里那句改对，并记下「实现者纠正了控制器的裁定」这件事本身。

**Task 8 任务审查发现的缺陷 · Critical（控制器裁定，已记账）**：投递的 ① **无条件**改动会话节点树（追加标记节点 + 改写工具结果），而它可能发生在**某个生成还在飞**的时候——只要该生成处于「请求已构建、首个 `GenerationChunk.Messages` 未到」的窗口（一次 provider 往返，很宽）。此时：`updateCurrentMessages` 按下标合并（`messages[index]` → `messageNodes[index]`，节点里没有该消息就追加并把 `selectIndex` 移过去），而生成用的是冻结快照，于是该生成的 assistant 消息会落进**标记节点** ⇒ 标记对 `currentMessages` 隐形 ⇒ `pendingTaskMarkers()` 找不到它 ⇒ `startTaskDelivery` 判 `stillPending = false` ⇒ **不触发那一轮、结果正文再也派发不出去**（正文只在标记 metadata 里）；同时工具结果的终态改写会被那一轮的旧版本按 id 覆盖回去。**设计前提本身是错的**：spec §7 断言「生成中的 assistant 位置在标记之前」，那**只在首个 chunk 落地之后**成立。旧机制没踩到，是因为 `handleSubAgentRecall` 只在空闲时才追加可见节点（`if (session?.getJob()?.isActive != true)`）——Task 8 去掉了这个前提。修法：`deliverTaskResult` 开头加「等会话空闲」闸门（`while` + 复查当前值，因为 `first {}` 按发射时的值判定，而生成结束回调排空队列时可能在我们恢复前又起一轮），闸门放在 `ensureLoaded` **之前**（对账也改节点树）；`reconcileInterruptedSubAgentTasks` 自身也加「有生成在飞则跳过」的守卫（`initializeConversation` 路径会走到）；① 落库前先同步写一次内存以消除并发投递的读-改-写竞争。**验收标准第一条**在这个窗口里会静默失效。

**Fix 轮 3 的制品同步（控制器记账）**：Fix 轮 3 的指令要求实现者删掉 `deliverTaskResult` 里那句已经不再成立的「等到之后…不会有别的协程插进来」，而**我忘了同步删掉计划里同样的两行**——实现者照指令改了代码、并明确报告「brief 文件本身仍带着它们，我没有改你的制品」。这是**两份材料不一致**：审查者若拿 brief 对代码会比出两行差异。已把计划那两行删掉并重新生成 brief，使计划与提交后的代码逐字一致。

> 教训：凡在派发消息里给出「删掉/改掉某句」的指令，必须同时改计划——否则 brief（由计划生成）与代码会分叉，而分叉的方向恰好是「计划里留着已被判定为错的句子」。

**Fix 轮 3（控制器裁定，同上 Critical 的收尾）**：实现者报回「闸门与改写之间的那段挂起不可利用」，我复核后**判为过于乐观**，并要求把它关死。机制：闸门原本只在**进函数时**判定一次，而 `ensureLoaded` 在会话未载入时要读库（对账命中还要落库）——那是一段真实挂起；同一会话的**第二条并发投递**（D1 落库后其 `saveConversation` 尾部排空队列并起了新一轮 G，D2 才从自己的读库挂起中恢复并改写节点树），或**用户队列消息被排空**（`removeQueuedMessage` / `finishEditQueuedMessage` 都会调 `advanceConversation`），都能在这段挂起里起一轮生成 ⇒ 机制链重新成立。修法：抽 `awaitIdle(session)` 并在**紧贴改写**处再调一次，使「看到空闲 → 改完节点」这一段局部地不挂起。这一条同样没有单测能覆盖。

**同轮附带两条 Minor**：① `handleMessageComplete` 的注入过滤用字面量 `"<task-notification>"` 而非 `TASK_NOTIFICATION_TAG`（既有代码）——该过滤是「通知绝不写回会话」那道边界的唯一守卫；② `applyTaskDelivery` 返回 null（= 已投递/工具结果已不存在）时没有任何日志，现场分辨不出「投递被跳过」。

**Task 8 实现者发现的计划缺陷 · 严重（控制器裁定，已记账）**：Task 8 Step 2 的中断对账判据写的是 `registry.isLive(taskId)`，而 `isLive` =「registry 里那条是 IN_PROGRESS」。于是**本进程里刚刚完成、投递尚未落地**的任务会被误判为中断：那一刻工具结果仍是 `started`（① 终态入库在 `ensureLoaded` 返回之后才做），registry 里已是 COMPLETED ⇒ `isLive` 为假 ⇒ 判中断 ⇒ 写上一条**假的**「应用退出」回执并落库；随后真正的投递走到 `applyTaskDelivery` 时标记已存在、按幂等契约返回 `null` ⇒ **真投递被静默丢弃**（结果正文丢失、`enqueue` 永不执行、**不触发 AI 回复**）。触发条件只是 `session.loaded == false`，即**会话被 5 秒空闲回收**——正是验收标准第一条（「切屏走了之后仍能跑完并触发回复」）的主场景；spec §10.3 的第 1 条设备核验本会撞上它。spec 自身也自相矛盾：§8.3 写 `!isLive`，而它自己的测试表写「registry 里**不存在**」。修法：判据改为 `get(taskId) == null`（本进程完全不认识它），并把谓词参数由 `isLive` 改名为 `isTracked`（**缺陷的根因就是这个名**，留着它下一个人还会把 `isLive` 传回来）。`SubAgentDeliveryTest` 的四处调用都是尾随 lambda、无具名实参，故测试零改动。（发现方：Task 8 实现者，在审查前自行核出并完整给了可达性链与两个备选方案，未擅自改计划——这正是常设指令要求的做法。）

**同轮修正的两处 Task 8 事实性错误**（实现者指出）：① Step 6.4 只列了 `sendQueuedMessage` 一处 `checkPendingRecall`，实为**三处**（`regenerateAtMessage`、`handleToolApproval`）——虽因「漏改即编译不过」而自纠，但错误计数会让人以为改完一处就收工；② 本步**缺一个必需 import**（`SubAgentFailReason`，`reconcileInterruptedSubAgentTasks` 与 `taskMarkerText` 都要用），照 brief 抄会编译不过。

**控制器自查抓出的计划缺陷（四）**（Task 8 派发前核出，已记账）：Step 8 那段转义说明**自相矛盾且事实错误** —— 它断言「`\"` 不是合法转义」并要求改用 `&quot;`，而 Step 8 自己的代码块用的就是 `\"`，且本仓 `values/strings.xml` 里已有 5 处 `\"`、0 处 `&quot;`（aapt2 的字符串资源本就支持反斜杠转义）。照那段说明做，实现者会把**正确的**代码改成与本仓惯例不一致的写法 —— 这是最坏的一类指令错误：叫人对的东西动手。已改写成「照抄 `\"` 即可」并附上 5/0 的实测计数。同一轮还把 Step 9 的 `git add -A` 换成显式路径（与 Task 7 同类）。

**控制器自查抓出的计划缺陷（三）**（我自己引入、自己在 Task 8 派发前核出，已记账）：我在「让 `ensureLoaded` 自成一体、`initializeConversation` 保持原样」这条修正里**丢掉了 `loaded = true`** —— 原计划那行在 `loadConversation` 尾部（`getOrCreateSession(conversationId).loaded = true`），而两条路径都靠它。后果不是「慢一点」：用户打开会话后 `loaded` 永远为 false ⇒ 之后每条投递都走「重新读库 + `updateConversation`」整段替换内存态 ⇒ 用户正在生成时把未落盘的流式内容冲掉，正是 §6.1 警告的那件事。spec §6.1 本来就写着「`initializeConversation` 完成后置 true」，是我改计划时把它弄丢了。

> **教训（同一处修正里犯了两次，必须记下来）**：拆「共享的载入方法」时，我逐项搬了它的**主职责**，却连丢两次它的**尾部副作用**——先是 `reconcileInterruptedSubAgentTasks`（差点砍掉「软件退出补失败回执」的主要路径），后是 `loaded = true`。凡拆一个既有方法，必须把它**除主职责之外还顺手做了什么**逐条列出并逐一交代去向，而不是只盯它「读库 + updateConversation」那两行。（这条已在 Task 8 派发前修好，未进入任何已派发的 brief。）

**任务 7 派发前核出的计划缺陷（二）**（控制器裁定，已记账）：Step 5 用 `git add -A` —— 实现者若在仓库内留下任何探针/临时文件，会被一并提交进 master。改为显式列出本任务的 6 个路径。同时把「行为与今天一致」这句写准：它只是「旧 recall 机制刻意留到 Task 8 才删」，而 `executeAsync` 本任务确实新增了行为（取消时补 `USER_CANCELLED` 终态事件；用 `keepAlive` 自己持有前台服务），这两条不能为了「保持一致」而回退。

**任务 7 派发前核出的计划事实性错误**（控制器裁定，已记账）：Task 7 Step 4 写「`handleSubAgentRecall` 里两处 `event.success`」，实为**三处**（`:719`/`:741`/`:745`）——虽然新事件没有 `success` 字段、编译器会逼出全部三处（不会静默漏改），但错误计数会让人以为改完两处就结束。同时补记「新事件丢掉 `prompt` 是安全的」的核验结论（消费者只有 `ChatService` 与 `RouteActivity`，无处读它），避免实现者为了「保字段」而自造无消费者的成员。

**任务 5 审查发现的计划缺陷**（控制器裁定，已记账）：`ensureLoaded` 的「查 `loaded` → 读库 → `updateConversation`」不是原子的 —— `loaded` 只是 `@Volatile`（可见性，不是原子性），而 `getConversationById` 是挂起调用、两条背景协程都会在它那里让出，于是两条都带着各自读到的那份走到 `updateConversation`（整段替换 state），后到者抹掉先到者刚追加的内容并落库；那条工具结果因此在库里仍是 `started`，会被对账判成「中断」，给出**错的失败回执**。修法：`ensureLoaded` 自成一体（不再与 `initializeConversation` 共用 `loadConversation`——两者不变量相反），在 `synchronized(session)` 内复查 `loaded` 并置位。**不改 Task 5**：`@Volatile var loaded` 本身是对的，原子性属于「载入」这个操作，而它住在 ChatService。（发现方：Task 5 的任务审查者，判为 Important。）

**任务 5 派发前发现的计划缺陷（二）**（控制器裁定，已记账）：Task 6 给 `launchGenerationJob` 加的 `backgroundTask` 形参**全集无人传** —— 子代理自己的网络流不走这条通道（Task 7 直接用 `keepAlive.hold(..., backgroundTask = true)`），而 Task 8 的投递触发传的是 `keepAliveInBackground = true` 且不传 `backgroundTask`；一轮「AI 回复生成」本就该显示「正在生成回复…」而不是「后台任务运行中」。留着它 = 邀请后来者把回复生成误标成后台任务。修法：从 `launchGenerationJob` 去掉该形参（`acquire` / `hold` 上的保留，它们真的被用到）。

**任务 5 派发前发现的计划缺陷**（控制器裁定，已记账）：Task 8 的 `handleTaskFinished` 在整段投递期间不持会话引用 —— 而 `taskDeliveries` 刻意不进 `isInUse`（§5.4），空闲定时器又是提前 arm 的（`release()` 归零时装、fire 时才判 `refCount <= 0 && !isGenerating`），投递又会在 `saveConversation` 落库处让出线程：三者叠加，一颗先前装好的定时器可以在「enqueue 之后、advanceConversation 之前」把会话连同队列一起回收，于是 ② 触发永远不会发生（① 终态仍在库，所以这不是数据丢失，而是验收标准第一条「触发 AI 的回复」的静默失效，且只在「子代理恰好在目标会话最后一次 release 后 5 秒内完成」这个窄窗口里发生）。修法是在 `handleTaskFinished` 里 `addConversationReference` / `finally { removeConversationReference }` —— 3 行，且调用返回即释放，不改变 §5.4 的结论。

**任务 3 实现者发现的计划缺陷**（控制器裁定，已记账）：①幂等用例对第二次投递用 `!!`，而该函数在「标记已存在」时按约定返回 `null` —— 照抄必 NPE；实现者先按测试侧最小改动改成 `?: once`，控制器进一步裁定改为显式 `assertNull`，因为 `?: once` 会让断言退化成自己跟自己比（正确返回 null 时 `twice === once`）。②导入清单写漏 `buildJsonObject` / `JsonPrimitive`，且把并不需要的 `put` 列了进去（本文件的 `put` 全解析到 `JsonObjectBuilder` 成员重载）。

**任务 2 实现者发现的计划缺陷**（控制器裁定，已记账）：转义用例的 `<summary>` 断言写的是 `finished`，而实现按 `marker.status` 字面量输出 `completed` —— 断言必失败（实现者实测量化：修正前 13 PASS/1 FAIL，`expected finished actual completed`）。裁定接受实现者的改法：`<status>` 元素已承载 `completed|failed`，`<summary>` 是散文；改造前的代码本来就用状态词拼 summary，用字面量既与既有行为一致、也不动实现。

**任务 1 审查后修正的计划缺陷**（控制器裁定，已记账）：`finish` 的「读-判-写」改为 `ConcurrentHashMap.compute` —— 原写法在两次调用都读到 `IN_PROGRESS` 时会双双写入，注释里承诺的「先到者胜」并不成立（审查判为 Important、plan-mandated）。

**Pre-flight 扫描修掉的计划缺陷**（由控制器在开工前修正，已记账）：T2 的 `?.let {} ?: run {}` 改为 if/else；T2 转义测试里 `</result>` 的期望计数 2 → 1；`applyTaskDelivery` 的 `markerText` 由成品字符串改为 `(String) -> String`（否则中断对账只能拿 taskId 当子代理名）；改写时显式剔除 `result`/`error`；截断断言改为 `MAX + 后缀长度`；T4 `addLast` 返回 Unit 不能当表达式；T7 去掉 `cancel().let { true }`。

**已知缺口（执行者注意）**：
- **`github-build-only` 环境**：本机跑不了 Gradle，所以每个任务的「跑测试」都是 push + CI。计划里每个任务都给了完整的 CI 命令，Task 1–6 可以合并到一次 CI。
- **Task 8 Step 8 的 strings.xml 转义**：Android 资源里双引号要写 `&quot;`（计划里已注明正确写法，别照抄上面 `\"` 的示例）。
- **Task 2 与 Task 3 共用一个文件**：Task 2 只写「读」那一半，import 保持最小；Task 3 才引入写 metadata 需要的 `JsonNull` / `buildJsonObject` / `put` / `JsonPrimitive`。别在 Task 2 就抄 Task 3 的 import。
- 未覆盖但 spec 明确为「非目标」的：DB 迁移、断点续跑、同步子代理结果可见性、任务面板、watchdog——都不是缺口。
