# 交接：异步子代理生命周期重做（2026-09-25）

**状态**：九个任务全部完成、全部推送、CI 全绿；最终整支审查（opus）判「Safe to merge for the `sub_agent` path」，三条验收标准在代码上成立。
**范围**：`9d2f1d47..9bd2ccda`（44 个提交），代码改动面 22 文件 +1660/−272。
**权威文档**：设计 `docs/superpowers/specs/2026-09-19-async-subagent-lifecycle-design.md`；计划 `docs/superpowers/plans/2026-09-19-async-subagent-lifecycle.md`（含全部裁定与自检记录）。

> 本文件是设计 §9 承诺的那份交接（最终审查指出它当时**没写**，此为一并补齐）。

---

## 1. 交付了什么

异步子代理（`sub_agent` 带 `run_in_background: true`）的生命周期重做。三条验收标准与它们在代码上的落点：

| # | 标准 | 落点 |
|---|---|---|
| (a) | 用户离开会话/切屏后，子代理仍能跑完、结果送回该会话、**并触发一轮 AI 回复** | `SubAgentRuntime.executeAsync` 自己 `keepAlive.hold` 前台服务（父生成结束释放自己的 token 后仍存活）→ 终态发 `SubAgentTaskFinished` → `ChatService.deliverTaskResult`：① 终态入库（改写工具结果为只有状态的 JSON + 追加一条可见标记，结果正文进标记的 `Text.metadata`）② 会话空闲则带保活触发一轮、忙则进每会话 FIFO 由生成结束回调排空 |
| (b) | 子代理意外中断要有失败回执 | 取消 → `NonCancellable` 里补 `USER_CANCELLED` 终态；模型/网络错误 → `FAILED`（reason 为 null） |
| (c) | 软件退出也要有回执 | 重启后 registry 为空 ⇒ 载入会话时对账把仍是 `started` 的工具结果补成 `failed/app_exit`（静默，不触发生成） |

**AI 侧通知是派生的**：请求构建时按标记派生（判据「标记之后是否已有带非空文本的 assistant 消息」），**不落库**，靠 `.collect` 的过滤剔除、不写回会话。用户不可见。

### 提交链（关键节点）

```
22a54787 SubAgentTaskRegistry（+134955a6 compute 原子化）
6f47020c 投递纯逻辑·读半边
2398d9af 投递纯逻辑·写半边（+27a4f7b9 幂等契约 +53d107ea 补断言）
b7e7fe06 TaskDeliveryQueue
9f74be8c ConversationSession.loaded / taskDeliveries
e12a122b GenerationKeepAlive + FGS 文案（+cba6cb58 哨兵断言）
6fa180ba SubAgentRuntime 接线 + 事件改名（+fe3b5498 事件以 registry 为准）
40ae2cb9 ChatService 新通道、删旧机制（+3c972020 对账判据 +a2a84069/bb17918f/d9fcffc5 空闲闸门四轮）
d0316c51 卡片终态 + 取消入口（+eb6c185d error 守卫）
5b7ed7d8 通知改 USER（Claude 上 SYSTEM 送不到模型）+ 无锚点也留回执
9bd2ccda 写回过滤收窄为 USER+isSynthetic+含标签
```

计划/spec 的修正提交（纯文档）：`9d2f1d47` 起共 12 个 docs 提交，全部记在计划的「自检记录」里。

---

## 2. 必须知道的四条承重约束（改了就会静默失效）

1. **投递只许在「无在飞生成」时改动会话节点树。** `Conversation.updateCurrentMessages` 按**下标**合并，而 `GenerationLoop` 用请求时的**冻结快照**；生成期间追加节点会让该生成的 assistant 消息按 index 落进标记节点，标记对 `currentMessages` 隐形 ⇒ 不触发回复、正文派发不出去。守护：`ChatService.deliverTaskResult` 里两处 `awaitIdle(session)`（进函数时 + **紧贴 `applyTaskDelivery` 之前**）+ `reconcileInterruptedSubAgentTasks` 的跳过守卫。**没有任何测试覆盖这条不变量**——只有注释。
2. **`awaitIdle` 返回到 `updateConversation` 之间不得有任何挂起点。** `isActive == false` 也可能是「LAZY job 已装未 start」，靠的正是「同一不挂起片段」这个性质。
3. **通知必须是 USER（+ `isSynthetic`）而不是 SYSTEM。** `ClaudeProvider.buildMessages` 滤掉**全部** SYSTEM 消息（`ClaudeProvider.kt:558`），Responses API 丢去除首条外的 ⇒ 用 SYSTEM 会让通知在那些 provider 上根本送不到模型，而通知是结果正文唯一的去向。写回过滤的判据是 **USER + isSynthetic + 含标签三者同时满足**（只看标签会**误剔**真实消息 ⇒ 回复静默丢失 + 下标错位）。
4. **`TASK_NOTIFICATION_TAG` 那道过滤是「通知绝不写回会话」的唯一守卫**，且它同时保持 assistant 的节点下标对齐。

---

## 3. 未决与推迟项（交后续裁决）

### 3.1 待你裁决的一条（最终审查的 Important ③）

`initializeConversation` / `generateTitle` / `generateSuggestion` 三处**无锁整对象写**（`ChatService.kt` 约 `:355-372`/`:1257-1262`/`:1290-1298`）可能吞掉投递刚写的标记 ⇒ `stillPending = false` ⇒ 不触发回复，且**要落库的写者**会把无标记的内存态持久化（正文丢失）。窗口 1–2 次 DB 往返（毫秒级）。
- **未修**：属既有无锁写类别、修法是让那三处合并进实时 state 而非用陈旧快照整对象替换，但它们是「用户打开会话」「生成标题」「生成建议」三个与子代理不相干的功能，改其状态更新语义超出本计划声明的影响面。
- **建议**：作为独立小任务做（`updateConversationState` 式合并 + `initializeConversation` 取 `ensureLoaded` 的同一把锁）。

### 3.2 其余推迟项（最终审查已分诊：**没有一条是 must-fix**）

- **窄竞态/边界**：`cancel` 落在协程体开始前（不发回执、registry 停在 IN_PROGRESS）；投递在 `registry.finish` 之后抛异常（卡片停在 `started` 直到重启）；并发投递的两次 `saveConversation` 写序交错（下一次 save 自愈）；`SubAgentTaskRegistry.register` 是无条件 `put` 而 taskId 是 8 位随机十六进制（碰撞概率单对 2.3e-10，但后果是**收据投递到错误会话**）。
- **体验**：取消一个已结束的任务是静默空操作（无 toast）。
- **代码整洁**：`SubAgentTaskRegistry.isLive/all`、`TaskDeliveryQueue.contains/remove/clear` 无生产调用者；`AsyncSubAgentHandle.job` 被丢弃；`startTaskDelivery` 那行 `invokeOnCompletion` 与会话自身的 `onGenerationFinished` 重复。
- **文档**：`ConversationSession.kt` 里「见 memory/设计 §5.4」的路径悬空；`SubAgentDelivery.kt` 的 KDoc 前向引用；`docs/superpowers/handoffs/2026-09-11-*.md:53-54` 仍把已删的 `pendingNotifications` 等列为「fork 需保留」。
- 完整 25 条在 `.superpowers/sdd/2026-09-19-async-subagent-lifecycle/dispatch-blocks.md` §7（**该目录是 git-ignored 的临时工作区，收尾后会清掉**；需要长期保留的是本节）。

### 3.3 一条已知的设计权衡

步 ① 的持久化现在被闸门推迟最多**一整轮生成**。期间进程若死，一个「已完成」的任务会得到假的 `app_exit` 回执（仍设计「① 可丢」的范围内，但窗口从毫秒级涨到一轮生成）。若要缩短：把 `applyTaskDelivery` 拆两半——不增删节点的部分（工具结果改写）可提前做，只把**追加标记节点**推迟。

---

## 4. 设备核验清单（spec §10.3，2026-09-25 修订）

**已由静态分析判定成立的 7 条**（不必再上设备）：对账不误判且不自动触发生成；`stopGeneration` 不会取消子代理任务（`cancelPrevious=false`，且 `session.activeJobs` 从不含子代理 job）；单槽位状态已不存在；结果正文 100k 硬截断且通知里的正文来自裁剪后的 metadata；标记文案是资源字符串且注入的 XML 在写回前被过滤（不会有裸 XML 落库）；「历史完整」那一半已有 JVM 断言（5→6 节点 + 未动节点的 `assertSame`）；取消按钮的组合局部**能**到达详情面板（`ToolDetailSheet` 的 `Dialog` 经 `setParentCompositionContext` 继承，provider 是它的祖先）。

**真正要上设备的 4 项**：
1. **切屏后子代理仍跑完并触发回复**（本轮的验收核心）——特别留意 **Claude / Responses 模型**：通知的角色已改进（见 §2.3），但需在设备上确认回复里**确实带上了子代理的结果正文**。
2. Doze / OEM 杀进程下长时间任务是否存活（前台服务 + 保活）。
3. 卡片上的取消按钮**点击**真的能取消（静态只能到「按钮可见且通道接通」）。
4. 历史完整性（离开会话再回来，历史没被抹掉）——这是缺陷①的正反面证据。

---

## 5. 最有分量的几条裁定（为什么这么做）

- **数据不进工具结果、只进标记 metadata**：工具结果的 Text 同时是「发给模型的 tool 消息」和「UI 解析对象」（卡片、导出图、原始 JSON、prewarm 全读它），要把结果藏给 AI 就得换载体。代价：`nodes` blob 变大。
- **通知派生而不存储**：天然「只通知一次」，`pendingNotifications` 永久不清理那类缺陷结构性消失，不必引入「隐藏消息」概念。
- **不把结果投递到「已完成的会话」**：`ensureLoaded` 对库里不存在的会话返回 false、**不复活**（`saveConversation` 对不存在的 id 会 insert）。
- **对账判据是「本进程完全不认识这个 taskId」而不是「它不是 IN_PROGRESS」**：后者会把本进程里刚完成、投递未落地的任务误判成中断 ⇒ 写假回执 + 真投递被幂等守卫丢弃。
- **修复要连名字一起改**（`isLive` → `isTracked`）：缺陷的根因是那个名字，留着它，下一个人还会传回 `isLive`。

---

## 6. 复盘：这轮真正有效的东西

1. **「计划可质疑」常设指令**（用户要求）拦下了 3 处计划缺陷，其中 Task 8 实现者提出的那个是**整个会话最有价值的发现**——它指出计划的设计前提错了（spec §7 断言「生成中的 assistant 位置在标记之前」，那只在首个 chunk 落地之后成立），若照抄会在 CI 全绿之后才由设备核验暴露，而症状恰好是用户最初抱怨的那类。
2. **反向对照**（删掉被守的那行/把守卫放宽，确认测试恰好变红）——多轮实现者都做了，它把「断言有没有牙」从主张变成证据。
3. **pre-flight 逐条核锚点**：抓出 12 处计划缺陷（我 9、实现者 3），包括「教人把对的代码改错」的转义说明、`git add -A`（三次）、错误的行号与处数引用。
4. **单任务审查的结构性盲区**：最终整支审查发现的两条 Important（`run_workflow` 复用同一 `executeAsync`、provider 层过滤 SYSTEM）**都是任务边界之外**的东西——每任务审查只看本仓新写的代码，看不到真实调用方与被复用模块的既有约束。

## 7. 我自己的失误（留档）

- 拆共享方法时**连丢两次它的尾部副作用**（先是 `reconcileInterruptedSubAgentTasks`、后是 `loaded = true`）。
- 把写回过滤的取舍判断写反了一次（「只看标签更稳」——错，它把可恢复的漏过滤换成了不可恢复的误过滤）。
- 行号引用（`ClaudeProvider.kt:555`）没在写下时核对，被复制到六处。
- 一条派发指令要求删注释，却忘了同步删计划里的同一句（两份材料分叉）。
