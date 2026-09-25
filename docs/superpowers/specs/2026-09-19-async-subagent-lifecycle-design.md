# 异步子代理生命周期重做 — 设计文档

**日期**：2026-09-19
**起点提交**：`4a89d505`（本文档之前最后一次提交，工作树干净）
**相关记忆**：`subagent-tool-control-handoff`、`subagent-workflow-tools`、`workspace-backup-zip-modes`（显示层 vs 内容层边界的先例）

---

## 1. 背景与问题

用户报告：

1. 子代理在**切屏/离开会话后**，返回的消息收不到；
2. 要求「在其它对话中时，子代理能正常运行、返回，并触发 AI 的回复」；
3. 要求「子代理意外中断，或软件退出时，也要有失败信息的正常返回」。

代码走查确证了 6 个缺陷（§4），其中第一个是**数据丢失**：离开会话约 5 秒后 session 被回收，子代理完成时的投递会把该会话的历史整段替换掉。

---

## 2. 目标 / 非目标

### 目标

- 后台子代理无论用户当前在哪个会话、App 是否在前台，都能跑完并把结果落回原会话，并触发一轮 AI 回复。
- 任何终态（完成 / 模型或网络失败 / 用户取消 / 进程中断）都有一条统一、可持久、AI 能读到的回执。
- 会话历史永不因投递被破坏。
- **异步子代理的结果正文不对用户展示**，只给 AI（§6.3、决策 5）。

### 非目标（明确不做）

| 不做 | 理由 |
|---|---|
| 不建新表、不动 DB schema | 验收标准用「终态进会话」就能满足（§6） |
| 不做断点续跑 | 进程死了就判失败，不重放 |
| **不引入「隐藏/不可见消息」概念** | 会话里不放需要展示层过滤的东西——那会逼聊天列表、全选、导出、搜索索引各自记得过滤一遍（替代做法见 §6.3） |
| 不改同步子代理（`run_in_background=false`）的结果展示 | 它的结果就是当轮给模型的 tool result，与 UI 解析的是同一串文本（`ChatMessageTools.kt:112-119`）；藏它要改 3 个展示面，还会牺牲「用户能读到子代理报告」（决策 5） |
| 不做会话内「运行中任务面板」 | 只把卡片状态做对 + 一个取消按钮 |
| 不改消息队列（`QueuedMessage` / `MessageQueue`） | 队列语义是「用户输入」，混入任务结果要为一件事改两个核心并发件 |
| 不给 AI 加 `task_list` / `task_get` | `eb850b94` 因模型疯狂轮询而有意删除，保持删除 |
| 不做总时长 watchdog | 流式请求已有网络超时兜底；真卡死由用户取消 |

---

## 3. 决策记录（用户拍板）

| # | 问题 | 决定 |
|---|---|---|
| 1 | 子代理运行期间怎么保活 | **复用现有生成通知**：占着 `ChatGenerationForegroundService` 那条通知，完成/失败后随生成通知一起消失，不新增常驻通知 |
| 2 | 重启后被打断的任务怎么办 | **静默补标记，等下次生成**：改写工具结果为失败 + 追加可见标记，**不自动开一轮生成**；AI 下次在该会话发言时看到 |
| 3 | 多个子代理先后完成时怎么回流 | **逐个回流，各开一轮**（与工具描述里「逐个通知」的语义一致）；待触发集合因此必须是**按任务粒度**的 FIFO，不是单槽位 |
| 4 | 整体方案 | **B 独立投递通道**：不借道消息队列；新增任务注册表 + 保活封装 + 统一投递入口 |
| 5 | 结果正文给用户看吗 | **只藏异步**：异步的结果正文进标记 metadata，任何展示面都读不到；同步子代理保持现状（结果在卡片里可读） |

---

## 4. 现状与六个确证缺陷

### 4.1 现状链路

```
sub_agent(run_in_background=true)
  └─ SubAgentRuntime.executeAsync              (SubAgentRuntime.kt:176，裸 appScope.launch)
       └─ 完成 → eventBus.emit(AppEvent.SubAgentCompleted)        (AppEvent.kt:28)
            └─ ChatService.init 订阅                              (ChatService.kt:199-205)
                 └─ handleSubAgentRecall                          (ChatService.kt:717)
                      ├─ 存隐藏 <task-notification> → pendingNotifications      (:734)
                      └─ 主 agent 空闲 ? fireRecall : pendingRecall             (:745-753)
                           └─ fireRecall                                      (:758)
                                ├─ 追加可见 SYSTEM 标记「Agent "x" finished」
                                └─ setSessionJob { handleMessageComplete }    (:782)
                                     └─ 生成前注入 pendingNotifications       (:884-898)
```

三份状态全在内存：`SubAgentRuntime.tasks`、`pendingNotifications`、`pendingRecall`。

### 4.2 缺陷

| # | 缺陷 | 锚点 | 后果 |
|---|---|---|---|
| ① | 会话被回收后投递写进**空壳会话** | `getOrCreateSession` 用 `Conversation.ofId()` 建空壳（`ChatService.kt:241-250`）；`ConversationSession.isInUse` 只看 refCount / generationJob / 队列（`ConversationSession.kt:48-52`），异步子代理一条都不占 → 离开会话 5 秒（`IDLE_TIMEOUT_MS`）即回收；`fireRecall` 在空壳上追加并生成，`onSuccess` → `saveConversation`（`ChatService.kt:1372`）→ `ConversationRepository.updateConversation` 是 `deleteByConversation` + `saveMessageNodes`（`ConversationRepository.kt:347-358`）= **整段替换** | **会话历史被换成「Agent x finished」+ 一条回复**；会话若已被删除还会被 `insertConversation` 复活（`ChatService.kt:1372-1385`）。另外空壳取的是**当前全局助手**，recall 会用错助手/模型 |
| ② | 异步子代理与 recall 生成都没有前台服务 | `executeAsync` 裸 `appScope.launch`（`SubAgentRuntime.kt:191`）；`setSessionJob` 裸 `appScope.launch`（`ChatService.kt:782-785`）。正常发消息走 `launchGenerationJob` 带 FGS（`ChatService.kt:334-356`）。且**父生成结束时 FGS 就释放了**，子代理还要跑很久 | 进程退回 cached 状态可被冻结/回收 → 任务永不完成或完成也写不进。「切屏走了收不到」的直接成因。对照：`run_in_background=false` 跑在生成 job 内，天然有 FGS，所以只有异步这条坏 |
| ③ | 进程退出/意外中断没有任何失败回执 | `tasks` 是内存 Map；DB 里工具结果永远停在 `{"status":"started"}`；`finishInterruptedPendingTools`（`ChatService.kt:1058`）只处理 approval 仍 pending 的工具，而 sub_agent 输出已非空 → `isExecuted = true`，不算 pending | 重启后卡片显示「已启动」，AI 不知道任务死了，永远没有失败回执（正是需求 2、3） |
| ④ | `pendingNotifications` 从不清理 | 只在 `:884` 读，从不删除 | 每完成一次子代理，永久往该会话**每一次**后续生成注入一份过期通知 → 上下文持续膨胀 + 模型反复看到过期任务 |
| ⑤ | `pendingRecall` 每会话只有一个槽位 | `ChatService.kt:193-197`、`:745-749` | 并发完成时后者覆盖前者；通知都注入，可见消息只剩最后一条 |
| ⑥ | 工具卡片永远停在「已启动」 | 全仓无任何 `copy(output = ...)` 事后改写；`isExecuted = output.isNotEmpty()`（`UIMessagePart.kt:200`） | 卡片状态与事实不符；重启后更误导 |

### 4.3 顺带发现的边界问题

- `setSessionJob` → `session.setJob(job)` 默认 `cancelPrevious = true`（`ConversationSession.kt:86`）：`handleSubAgentRecall` 的「判空 → 触发」之间有竞态窗口，会把用户此刻新发起的生成掐掉。
- 「停止生成」不取消子代理（`cancelJobs` 只取消 session 的 job）；子代理之后照样 fire recall，把用户刚停掉的会话重新点着。
- 无任何取消入口（AI 侧与用户侧都没有）。
- `SubAgentRuntime` 全局单例、`tasks` 全局共享，任务与会话只有 `conversationId` 一个字段弱关联。
- `handleSubAgentRecall` 光读一次 `getConversationFlow` 就会建出 session 并常驻到 idle 回收。

---

## 5. 架构

### 5.1 组件

| # | 组件 | 位置 | 职责 |
|---|---|---|---|
| 1 | `SubAgentTaskRegistry`（新） | `data/ai/tools/local/` | 进程内任务表 `taskId → SubAgentTaskInfo{conversationId, description, startedAt, status, reason, result, error}`；`isLive(taskId)` 判定存活。纯 Kotlin、无 Android 依赖、可 JVM 单测。**不持久化**——终态一律进会话 |
| 2 | `GenerationKeepAlive`（新） | `service/` | 薄封装 `ChatGenerationForegroundService`：`hold(conversationId): Uuid?` / `release(token)`。`ChatService.launchGenerationJob` 与 `SubAgentRuntime` 共用 |
| 3 | `SubAgentRuntime`（改） | `data/ai/tools/local/` | `executeAsync`：登记 registry → `keepAlive.hold` → 执行 → 写终态 → emit `SubAgentTaskFinished` → `release`。**不再关心会话、通知、可见消息** |
| 4 | `SubAgentDelivery.kt`（新，纯函数） | `service/` | 位置规则、对账判据、工具结果改写、标记构造、通知派生——全部是可 JVM 单测的 `internal` 纯函数 |
| 5 | `TaskDeliveryQueue`（新） | `service/` | 每会话的「待触发投递」FIFO：按 taskId 去重、终态已入库的条目丢弃、**不取消当前生成** |
| 6 | `ChatService`（改） | `service/` | `deliverTaskResult`（唯一投递入口）、`ensureLoaded`、`reconcileInterruptedSubAgentTasks`、`cancelSubAgentTask` |

### 5.2 数据流

```
子代理完成 / 失败 / 取消
  └─ SubAgentTaskRegistry 写终态
       └─ emit AppEvent.SubAgentTaskFinished(conversationId, taskId, description, status, reason, result, error)
            └─ ChatService.deliverTaskResult(...)          ← 四条终态路径唯一入口
                 ├─ ① 终态入库（随时可做、可丢）
                 │    ensureLoaded(conversationId)
                 │    一次 saveConversation：
                 │      a. 把该 taskId 的 sub_agent 工具结果改写成终态 JSON   ← 幂等锚点 + 卡片状态（不含结果正文）
                 │      b. 追加一条可见标记节点（文本=状态给人看；metadata=taskId/status/reason/结果正文，只给 AI）
                 └─ ② 触发回复（需要时机）
                      会话空闲 → launchGenerationJob(keepAlive = true)   ← 带保活的通道
                      会话忙   → TaskDeliveryQueue 排队，生成结束回调里逐个触发
```

做完 ① 之后进程立刻死掉也不丢：工具结果是终态、标记已入库，下一次任何生成都会带上（§7 的判据只看标记位置，不需要额外状态）。

### 5.3 正常完成的时序

1. AI 调 `sub_agent(background)` → registry 登记 → `hold` 保活 → 工具结果返回 `{"type":"sub_agent","status":"started","task_id":"sub_xxx",…}`
2. 父生成结束、父生成释放 FGS，但**子代理自己 hold 着** → 服务仍在前台（`activeGenerations` 本来就是多持有者引用计数，`ChatGenerationForegroundService.kt:105-118`）。通知文案按「只有后台任务」显示为「后台任务运行中」
3. 子代理完成 → registry 写终态 → emit 事件
4. `deliverTaskResult` → ① 入库 → ② 触发
5. 触发那一轮的请求里带上派生的通知（§7），AI 回复

### 5.4 为什么不需要「钉住会话」

消息队列必须钉住会话，是因为排队的用户消息只活在内存里（`ConversationSession.isInUse` 里的 `messageQueue.state.value.messages.isNotEmpty()`）。子代理的投递物不同——是**写进会话的记录**：只要写之前确保已从库里载入真实会话，会话被回收就不再是错误。保活只服务于子代理自己的网络流，与 session 无关。

---

## 6. 终态入库的两个新原语

### 6.1 `ensureLoaded(conversationId)`

- `ConversationSession` 新增 **`loaded: Boolean`**（默认 false）；`initializeConversation` 完成后置 true。
- `ensureLoaded`：若 session 不存在或 `!loaded`，从 `conversationRepo.getConversationById` 载入并 `updateConversation`。
- **不调用 `settingsStore.updateAssistant`**：现有 `initializeConversation`（`ChatService.kt:360-380`）会顺带切换全局助手，那是「用户打开了会话」的语义；背景投递不该有这个副作用。实现上把「只载入」抽成一个私有方法，`initializeConversation` 复用它。
- **`loaded = true` 的会话绝不重新载入**：内存态在生成期间可能领先于库（`saveConversation` 只在生成结束时落盘），重载会把正在生成的内容冲掉。

### 6.2 工具结果改写

- 定位：在该会话当前分支里找 `UIMessagePart.Tool` 且 `toolName == "sub_agent"`、`output` 文本中含 `"task_id":"<taskId>"` 的那一个 part。`Tool.execute` 拿不到自己的 `toolCallId`（签名是 `suspend (JsonElement) -> List<UIMessagePart>`），所以用输出里内嵌的 `task_id` 作锚点。
- 改写内容：

```json
{"type":"sub_agent","status":"completed","reason":null,"task_id":"sub_xxx","description":"搜索 AI 新闻"}
{"type":"sub_agent","status":"failed","reason":"user_cancelled|app_exit","task_id":"sub_xxx","description":"搜索 AI 新闻"}
```

- `status` 维持 `completed|failed` 两值（现有 UI 的三个 pill 不用扩枚举）；`reason` 区分失败性质，`null` = 模型/网络错误。这个 JSON 只用于两件事：渲染卡片 pill、做对账锚点。
- **结果正文与原始 error 不写在这里**（决策 5）：`UIMessagePart.Tool.output` 的 Text 文本同时是「发给模型的 tool 消息」和「UI 解析的对象」——`ToolUIContext.content` 就是把它拼起来再 parse（`ChatMessageTools.kt:112-119`），于是卡片、导出为图片（`Export.kt:313-316`）、详情里的原始 JSON 开关、`ChatPrewarm` 全都读得到。结果改放 §6.3 的 metadata，这些展示面**一个都不用改**。
- **改写是幂等锚点**：对账判定「工具结果仍为 `started`」，改写后不会再命中。
- **同步子代理不走这条路**：`run_in_background=false` 的结果必须留在 tool result 里（那是模型当轮的通道），保持现状（决策 5）。

### 6.3 可见标记：唯一的新节点，文本与元数据分工

投递追加**一条** SYSTEM 消息：

```kotlin
UIMessage(
    role = MessageRole.SYSTEM,
    parts = listOf(
        UIMessagePart.Text(
            text = "Agent \"搜索\" finished",          // 用户看得见：只有状态，没有结果
            metadata = buildJsonObject {               // 用户看不见，也从不发给 provider
                put("subAgentTask", buildJsonObject {
                    put("taskId", "sub_xxx")
                    put("status", "completed")         // completed | failed
                    put("reason", JsonNull)            // null | user_cancelled | app_exit
                    put("result", "……子代理写的完整报告……")   // 结果正文；失败时为 null
                    put("error", JsonNull)             // 原始错误正文；成功时为 null
                })
            },
        )
    ),
)
```

- `UIMessagePart.Text.metadata: JsonObject?` 是 `@Serializable`（`UIMessagePart.kt:82-85`）→ **随 `nodes` blob 一起持久化**，这是选它而不选隐藏节点的原因；也是**结果正文唯一的持久化载体**（决策 5）。
- **三条通道的分工**（决策 5 的落点）：

| 载体 | 发给模型 | UI 渲染 | 持久化 |
|---|---|---|---|
| 工具结果（`Tool.output` 的 Text） | ✔ 就是 tool 消息正文 | ✔ 卡片 / 导出为图片 / 原始 JSON 开关 / prewarm 全读它 | ✔ |
| `Text.text` | ⚠️ 在历史里，但**不是所有 provider 都收得到**：`ClaudeProvider.buildMessages` 滤掉全部 SYSTEM 消息（`ClaudeProvider.kt:555`），Responses API 丢弃除首条外的 SYSTEM ⇒ SYSTEM 角色的文本到不了模型 | ✔ | ✔ |
| `Text.metadata` | ✖ 只发 `text` | ✖ 渲染器只读 `text` | ✔ |

- 结果正文写入前过 `clipToolOutput`（`ToolOutputLimits.kt:17`，100KB 硬截断）。子代理的最终结果是模型写的总结，天然有界，所以**不引入 `/tool_outputs` 指针机制**（避免依赖助手是否有 shell 访问）。
- 文案随 `reason` 变：`finished` / `已取消` / `已中断（应用退出）`。
- 卡片上的失败只显示**分类短文案**（应用退出 / 已取消 / 模型或网络错误），原始 error 只在 metadata 里给 AI。
- **会话里不存在任何「需要展示层过滤」的节点**：聊天列表渲染、全选、导出为图片、复制、FTS/向量索引全都无需改动（见 §2 非目标第 3 条）。
- 反例警示：`UIMessage.isSynthetic` 是 `@Transient`（`Message.kt:29-30`），**不持久化**，重新载入后一律为 false——任何「按 isSynthetic 判定」的写法都是错的。

---

## 7. 通知是**派生**的，不是存储的

今日的实现是「请求构建时把 `pendingNotifications` 注入进去」（`ChatService.kt:884-898`）且从不清理（缺陷④）。

新做法：**通知不落库**。请求构建时按标记派生一条临时的 **USER** 消息（`UIMessage.user(...).copy(isSynthetic = true)`；**不能是 SYSTEM**——`ClaudeProvider` 与 Responses API 会把 SYSTEM 整类丢弃，那样通知就送不到模型，见第 199 行表格的更正），只存在于这一次请求里，靠 `.collect` 里那条「含 `TASK_NOTIFICATION_TAG` 即剔除」的过滤保证它不会写回会话状态（过滤只看标签、不看角色）：

> 对 `currentMessages` 扫描带 `subAgentTask` metadata 的标记：若该标记**其后已经存在一条带非空文本的 assistant 消息**，视为已汇报，不再派生；否则为该 taskId 派生一条：
>
> ```xml
> <task-notification>
>   <task-id>sub_xxx</task-id>
>   <status>completed|failed</status>
>   <reason>…|（无）</reason>
>   <pending-tasks>N</pending-tasks>       <!-- 运行时从 registry 取，是活信息 -->
>   <summary>Agent "搜索" completed</summary>
>   <result>…</result>                     <!-- 取自标记 metadata 的 result/error（§6.3） -->
> </task-notification>
> ```

- **「只通知一次」由此免费获得**：AI 回复一出现，标记就不再派生通知；不需要「已投递」标志位，不需要清理，不会像现在这样永久重复注入（缺陷④消失）。
- **判据是可靠的，不只是「失效也安全」**：标记总在投递时追加到**末尾**，所以任何排在标记**之后**的 assistant 回复，其请求必然是在标记已存在之后构建的——也就是必然已经派生过这条通知。于是「标记之后已有 assistant 文本 ⇒ 视为已汇报」不会漏发。反过来，**已经落过至少一个 chunk** 的、正在流式生成的那条 assistant 消息位置在标记**之前**（`updateCurrentMessages` 按下标合并，标记是后追加的），不会被误判成已汇报。
  **但这只在首个 chunk 落地之后成立。** 生成处于「请求已构建、首个 chunk 未到」的那段时间里，该 assistant 消息还没有节点；此时若追加标记节点，按下标合并会把它塞进**标记节点**（`selectIndex` 随之转向它）——标记对 `currentMessages` 隐形，`pendingTaskMarkers()` 找不到它，下游判 `stillPending = false` 而**不触发那一轮**，结果正文（只在标记 metadata 里）再也派发不出去；工具结果的终态改写也会被那一轮的旧版本按 `id` 覆盖回去。
  ⇒ **因此「追加标记 / 改写工具结果」必须只在会话没有在飞生成时做**（见 `ChatService.deliverTaskResult` 的闸门与它的注释）。这条约束是承重的，且没有单测能覆盖它（需要整条生成管线），只能靠代码注释与审查守住。
- **跨进程可靠**：标记（含 metadata 里的结果正文）已入库，进程死了也不丢，下次生成照常派生（正好满足决策 2「静默补标记，等下次生成」）。
- **已知边界**：重新生成 / 切换分支会截断目标点之后的节点，这类路径下标记可能被丢掉——今天那条可见标记也是同样的行为，不是本轮引入的回归。
- 触发投递的那一轮不受影响：标记是投递时追加在**末尾**的，一定位于最后一条 assistant 消息之后 → 一定派生。

---

## 8. 状态机与四条终态路径

### 8.1 状态

| 状态 | 写入者 | 落点 |
|---|---|---|
| IN_PROGRESS | `executeAsync` 登记 | registry（内存）+ 工具结果 `started`（库，幂等锚点） |
| 终态 | `deliverTaskResult` | 工具结果改写成终态 + 可见标记节点（一次 `saveConversation`） |

`TaskStatus` 枚举保持 `IN_PROGRESS / COMPLETED / FAILED` 三值；取消归入 `FAILED` + `reason = user_cancelled`。

### 8.2 四条路径走同一个入口

| 路径 | 触发 | 工具结果 | 触发回复 |
|---|---|---|---|
| 完成 | `executeSync` 返回 success | `completed` + `result` | ✅ 一轮 |
| 失败 | `executeSync` 捕获异常 / 返回失败 | `failed` + `error`（`reason = null`） | ✅ 一轮（AI 才能解释或改道重试） |
| 用户取消 | 卡片上的「取消任务」 | `failed, reason = user_cancelled` | ✅ 一轮 |
| 进程中断 | 载入会话时对账 | `failed, reason = app_exit` | ❌ 静默（决策 2） |

### 8.3 中断对账

- **时机**：`ensureLoaded` 载入完成后跑一次 `reconcileInterruptedSubAgentTasks(conversationId)`。
- **判据**：`sub_agent` 工具结果 `status == "started"` **且 registry 里没有这个 taskId**（`registry.get(taskId) == null`），即「本进程完全不认识它」——只有这一种情形才等于「它随上一个进程一起死了」。
  **不能用 `!registry.isLive(taskId)`**：本进程里刚完成、投递尚未落地的任务，那一刻工具结果仍是 `started` 而 registry 已是 `COMPLETED`，既不是 live 又还没被改写 ⇒ 会被误判成中断并写出一条假的「应用退出」回执，随后真正的投递因标记已存在、按幂等契约返回 `null` 而被**静默丢弃**（结果正文丢失、且不触发回复）。这与本文件 §10.3 第 1 条设备核验的主场景（切屏离开、会话被回收）正面冲突。
  （措辞曾自相矛盾：本节原写 `!isLive`，而下文测试表写的是「registry 里**不存在**」——测试表是对的，本节按它修正。）
- **幂等**：改写后 status 变 `failed`，下次不再命中；同一会话有多个中断任务时逐个补标记，**不额外触发**生成。
- **为什么不在启动时全量扫库**：要在消息 JSON blob 上做 LIKE 查询，既脆又慢；而「下次生成」必然发生在会话被打开之后，懒对账在语义上已经足够。
- **不区分「进度死了多少」**：只要没有存活任务就是中断，不猜、不续跑。

### 8.4 取消语义

- 「停止生成」**不**取消子代理——后台任务与生成解耦，这正是能离开会话去别处的前提。
- 修掉现存竞态：`deliverTaskResult` 起 job 时用 `cancelPrevious = false`；会话忙则进 `TaskDeliveryQueue`，由生成结束回调逐个触发。**绝不取消用户正在跑的生成**（现状是 `setSessionJob` 默认 `cancelPrevious = true`）。
- 新增用户侧取消入口：`SubAgentToolUI` 详情里「取消任务」按钮（仅 `started` 时显示）→ `ChatService.cancelSubAgentTask(taskId)` → `job.cancel()` → 以 `reason = user_cancelled` 回流。对不存在 / 已完成 / 未运行的 taskId 幂等（no-op）。
- AI 侧不加取消工具，也不加 `task_list` / `task_get`。

---

## 9. 影响面清单

| 文件 | 动作 |
|---|---|
| `data/ai/tools/local/SubAgentTaskRegistry.kt` | 新增 |
| `data/ai/tools/local/SubAgentRuntime.kt` | 改：登记 registry、`hold` 保活、只上报终态；`TaskInfo` 加 `conversationId` / `reason` / 时间戳 |
| `service/SubAgentDelivery.kt` | 新增：位置规则、对账判据、工具结果改写、标记构造、通知派生（纯函数） |
| `service/TaskDeliveryQueue.kt` | 新增 |
| `service/GenerationKeepAlive.kt` | 新增 |
| `service/ChatService.kt` | 改：`deliverTaskResult` / `ensureLoaded` / `reconcileInterruptedSubAgentTasks` / `cancelSubAgentTask`；删 `pendingNotifications`、`pendingRecall`、`handleSubAgentRecall`、`fireRecall`、`setSessionJob`、`checkPendingRecall` |
| `service/ConversationSession.kt` | 改：`loaded` 标志 + 本会话的 `TaskDeliveryQueue` |
| `data/event/AppEvent.kt` | 改：`SubAgentCompleted` → `SubAgentTaskFinished`（带 `status` / `reason`） |
| `ui/components/message/tools/BuiltinToolUIs.kt` | 改：`SubAgentToolUI` 读 `reason`（已完成 / 已失败 / 已取消）+ 「取消任务」按钮 |
| `di/AppModule.kt` | 改：注册 `GenerationKeepAlive` |
| `data/ai/tools/local/LocalTools.kt` | 改：给 `SubAgentRuntime` 注入 keepAlive + registry |
| `RouteActivity.kt` | 改：事件分支改名 |
| `res/values/strings.xml`、`res/values-zh/strings.xml` | 改：新增文案（取消任务 / 已取消 / 已中断 / 后台任务运行中）。**只加这两个 locale**——现有 `tool_ui_sub_agent_*` 也只有 en/zh |
| `docs/superpowers/` | 交接文档 + 本 spec |

**不需要改**：`ChatList.kt`、`Export.kt`、`MessageQueue.kt`、`ChatMessage*.kt`、FTS / 向量索引（会话里没有需要过滤的节点）。

---

## 10. 测试计划

### 10.1 JVM 单测（CI 真跑）

仓库惯例：`ChatService` 本身需要 Application + Koin，从不实例化，只测它的顶层 `internal` 函数；`ConversationSession` / `MessageQueue` 是直接 new 出来测。新逻辑按同一惯例抽成纯函数与独立类。

| 用例 | 钉住什么 | 形状 |
|---|---|---|
| 标记之后已有 assistant 文本则不再派生通知 | §7 位置规则（替代「投递一次」状态） | `internal fun pendingTaskNotificationsFor(messages, liveCount): List<UIMessage>` |
| 末尾的标记一定派生通知 | 触发投递的那一轮不能漏 | 同上 |
| 通知正文取自标记 metadata 的 result/error | 通知是**派生**的，不是存储的 | 同上 |
| **异步投递的工具结果里没有结果正文** | 决策 5：工具结果是 UI 最容易读到的载体，放进去等于给用户看 | 断言改写后的 output JSON 无 `result` / `error` 键 |
| 对账只认 registry 里不存在的 `started` 任务 | §8.3 判据：存活任务不误判；已完成/已失败的旧结果不重复命中（幂等） | `internal fun interruptedSubAgentTasks(messages, isLive): List<…>` |
| 投递改写不动历史节点数、只改那一个工具结果 | **修复①的回归测试**：5 节点的已载入会话，投递后 6 节点、原 5 个逐字段不变 | `internal fun Conversation.applyTaskDelivery(...)` |
| 标记的 metadata 往返 | `subAgentTask.taskId/status/reason/result/error` 经 JSON 往返后仍可读（**持久化能力**是地基：结果正文只存在这里） | JSON 往返断言 |
| 未载入的 session 不会被投递路径当成已载入 | 空壳覆盖历史那道门：`loaded = false` 时必须先走 loader | `ConversationSessionTest` 现有形状（假 loader，断言 loader 被调用、状态来自 loader） |
| 任务注册表 | 登记 / 终态 / `isLive` / 并发登记 / 取消已完成任务的幂等 | 新类，纯 Kotlin |
| 待触发 FIFO | 顺序、同 taskId 去重、终态已入库的条目丢弃、**忙时不取消当前生成** | 新类，纯 Kotlin |
| 终态 JSON 形状 | `status` / `reason` / `task_id` / `description` 能被现有 `SubAgentToolUI` 的 `getStringContent` 读出（**不含** result/error） | 同 `ToolStateSerializationTest` |

### 10.2 CI

本机无 Android 编译器——push 后 `gh workflow run nightly-build-debug.yml --ref master`，只认 `--json conclusion,headSha` ＋ `--json jobs`（确认 `build` 没被 skip 成假绿）。`gh run watch` 的退出码不可信。

### 10.3 设备核验（用户执行，CI 绿 ≠ 功能对）

| # | 怎么验 | 期望 |
|---|---|---|
| 1 | 会话 A 起后台子代理 → 立刻返回会话列表 → 等它完成 → 回 A | 可见标记 + AI 新回复，**A 的历史完整无损**（修复①的正面证据） |
| 2 | 起子代理后切到别的 app / 锁屏 1–3 分钟再回来 | 子代理已跑完并已回复；通知栏全程有「运行中」通知 |
| 3 | 子代理运行中强杀 app → 重开进 A | 卡片「已失败（应用退出）」+ 可见标记，历史完整，**不自动开生成**；AI 下次发言时知道任务失败 |
| 4 | 运行中点卡片「取消任务」 | 卡片「已取消」+ 可见标记 + AI 回复一轮 |
| 5 | 子代理运行中在 A 里正常发消息 | 生成不被打断；子代理完成后**额外**多一轮 |
| 6 | 三个子代理并发（跨会话） | 逐个回流、各一轮，互不覆盖 |
| 7 | 让子代理产出超长结果 | 工具结果按 100KB 截断，上下文不爆 |
| 8 | 长会话里全选 → 导出为图片 / 查看历史 | 标记行正常显示、无裸 XML、无异常节点 |

**无法自动化**：真机后台被杀概率、国产 ROM 的保活效果——只能靠 2 / 3 两条人工观察。

---

## 11. 风险与已知取舍

| 风险 | 说明 / 缓解 |
|---|---|
| 事后改写工具结果会破坏 prompt cache 前缀 | 只在一个 task 终态时改一次该 part，生成期间不改；不改则卡片与历史永远错，代价可接受 |
| 依赖 `UIMessagePart.Text.metadata` 持久化 | 单测钉住往返（10.1）；这是选它替代「隐藏节点」的前提，**且结果正文只存在于这里**（决策 5）——若哪天 metadata 被改成 `@Transient`，结果会永久丢失，本设计必须重估 |
| metadata 里可放最多 100KB 结果 → `nodes` blob 变大 | 这是「用户看不到结果」的代价；100KB 硬截断兜底（§6.3） |
| **标记消息的 metadata 会被注入变换器抹掉——若它被回写进会话**（已核，当前不可达） | `PromptInjectionTransformer.applyInjections` 在配置了 BEFORE/AFTER_SYSTEM_PROMPT 时会把**第一条 SYSTEM 消息**的 parts 整体替换掉（`PromptInjectionTransformer.kt:128-160`），而标记正是 SYSTEM 消息。**当前不可达**：变换链的输出放在 `GenerationLoop.generateInternal` 的独立变量 `internalMessages`（`:393`）里，只作为命名参数喂给 provider（`:473`/`:510`）；回写状态用的是未变换的那份（`:481` 的 `attemptMessages`、`:515` 的 `messages`）。→ **别把 `generateInternal` 的 `var messages: List<UIMessage> = messages` 改成使用 `internalMessages`**：那会让这段注入改写回写进会话，metadata 一丢，幂等守卫与「标记之后是否已有 assistant 文本」的判据就同时静默失效（缺陷③④以无冲突、无测试失败的方式复活） |
| **SYSTEM 消息在部分 provider 上被整类丢弃**（已核，本计划据此修正了通知的角色） | `ClaudeProvider.buildMessages` 用 `it.role != MessageRole.SYSTEM` 过滤（`ClaudeProvider.kt:555`），`ResponseAPI` 丢弃除首条外的 SYSTEM ⇒ 任何「靠 SYSTEM 消息把内容送给模型」的设计在 Claude / Responses 上都不成立。派生通知因此改用 USER + `isSynthetic`（本仓既有做法 `TimeReminderTransformer.kt:77`）。**若将来有人把通知改回 SYSTEM 以求「用户不可见」，这条会在 Claude 上静默失效**——而它恰好是结果正文唯一的去向 |
| 通知注入块与生成回写的边界 | 派生的 `<task-notification>` 只存在于请求里；`handleMessageComplete` 的 `.collect` 按 `TASK_NOTIFICATION_TAG` 把它们剔除，不写回会话状态（§7）。这条边界一旦被删，注入块会被当成新消息写进历史 |
| 标记节点混进对话历史（AI 会看到 `Agent "x" finished`） | 有意为之——它就是可见回执；内容短、无语义歧义 |
| 卡片停在前台服务通知上时间长 | 复用生成通知的代价；卡死由用户取消（不做 watchdog） |
| `ensureLoaded` 与用户打开会话竞态 | `loaded` 是一次性单向标志，载入后不再重载；两条路径都走同一个 `updateConversation` |
| 取消发生在「已写终态、未触发」的窗口 | `cancelSubAgentTask` 对非存活 taskId 是 no-op，不会重复投递 |

---

## 12. 回滚

- 改动集中在 4 个新文件 + 10 个既有文件；`deliverTaskResult` 是唯一入口，回滚只需把 `ChatService` 的事件订阅还原到旧实现。
- 不涉及 DB schema，**没有不可回滚的迁移**。
- 旧行为的三份内存状态（`pendingNotifications` / `pendingRecall` / `tasks`）随实现一起删除，无残留数据需要清理。

---

## 13. 实施顺序（供后续写实施计划）

1. 纯逻辑先行：`SubAgentTaskRegistry`、`TaskDeliveryQueue`、`SubAgentDelivery.kt` 的纯函数 + 单测（不碰 `ChatService`，CI 可单独验证）。
2. `ConversationSession.loaded` + `ensureLoaded` + `GenerationKeepAlive` + 单测。
3. `ChatService` 接线：`deliverTaskResult` / 对账 / 取消；删旧机制。
4. `SubAgentRuntime` 登记 + 保活 + 事件改名。
5. 卡片终态 + 取消按钮 + 文案（en/zh）。
6. push → CI → 设备核验清单。
