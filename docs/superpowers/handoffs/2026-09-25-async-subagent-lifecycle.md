# 交接：异步子代理生命周期重做（2026-09-25）

**状态**：设计期九个任务 + **设备核验期的 20 个修复提交**全部入库、CI 全绿；最终整支审查（opus）判「Safe to merge for the `sub_agent` path」，三条验收标准已**在真机上逐条验过**（2026-09-25 晚）。
**范围**：`9d2f1d47..343ff283`（69 个提交 = 设计期 49 + 设备核验期 20），累计 34 文件 +3384/−450。
**本文件最后更新于** `343ff283`；之后若又有改动，以 `git log` 为准。
**怎么读**：§1–§8 是设计期的记录（仍然有效）；**§10 起是设备核验期的记录**——真机上暴露的问题、根因、修法与教训，以及新增的承重约束与诊断设施。**接手时先读 §2 + §10 + §12。**
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
837e2b63/fa90d606/7e8a8882 会话重载与背景写回不再抹掉标记（最终审查的 Important ③，含一轮编译错误修正 + 一行 memory-first 收尾）
```

计划/spec 的修正提交（纯文档）：`9d2f1d47` 起共 12 个 docs 提交，全部记在计划的「自检记录」里。

---

## 2. 必须知道的承重约束（改了就会静默失效）

> 1–5 条来自设计期与最终审查；6–13 条是设备核验期用真机现场换来的，**每一条背后都有一个具体的失效现场**（见 §10）。

1. **投递只许在「无在飞生成」时改动会话节点树。** `Conversation.updateCurrentMessages` 按**下标**合并，而 `GenerationLoop` 用请求时的**冻结快照**；生成期间追加节点会让该生成的 assistant 消息按 index 落进标记节点，标记对 `currentMessages` 隐形 ⇒ 不触发回复、正文派发不出去。守护：`ChatService.deliverTaskResult` 里两处 `awaitIdle(session)`（进函数时 + **紧贴 `applyTaskDelivery` 之前**）+ `reconcileInterruptedSubAgentTasks` 的跳过守卫。**没有任何测试覆盖这条不变量**——只有注释。
2. **`awaitIdle` 返回到 `updateConversation` 之间不得有任何挂起点。** `isActive == false` 也可能是「LAZY job 已装未 start」，靠的正是「同一不挂起片段」这个性质。
3. **通知必须是 USER（+ `isSynthetic`）而不是 SYSTEM。** `ClaudeProvider.buildMessages` 滤掉**全部** SYSTEM 消息（`ClaudeProvider.kt:558`），Responses API 丢去除首条外的 ⇒ 用 SYSTEM 会让通知在那些 provider 上根本送不到模型，而通知是结果正文唯一的去向。写回过滤的判据是 **USER + isSynthetic + 含标签三者同时满足**（只看标签会**误剔**真实消息 ⇒ 回复静默丢失 + 下标错位）。
4. **`TASK_NOTIFICATION_TAG` 那道过滤是「通知绝不写回会话」的唯一守卫**，且它同时保持 assistant 的节点下标对齐。判据是 **USER + `isSynthetic` + 含标签三者同时满足**——只看标签会**误剔**真实消息（含模型复述），后果是回复静默丢失 + 下标错位，不可恢复。
5. **每个「写整个会话对象」的路径，都必须先 `updateConversation` 同步写内存、再 `saveConversation` 落库。** `saveConversation` 的第一句是挂起的 `existsConversationById`，内存写入在它之后才发生；中间那段挂起里若有投递恢复执行并写入标记，整对象写回就会用「不含标记的」快照覆盖内存与库 ⇒ 不触发回复 + 正文从库里消失。目前三处都照做了：`deliverTaskResult`、`reconcileInterruptedSubAgentTasks`、`mergeConversationState`。**这是本计划第三次踩同一条规则**（前两次写对了，第三次新写函数时漏了）——新写的写者不会自动继承旧写者的教训。

6. **写回不得依赖「快照下标」。** `Conversation.updateCurrentMessages` 是 `messages[index] → messageNodes[index]`，而会话可能在生成期间长大（另一个子代理的回执、并发的另一轮）⇒ 回复会落进**别人的节点**：先是「合成一条 + 1/2 分支」，再是「下一条消息的回复改掉前面那条」。除 `messageRange != null` 的重新生成/分支路径（它的 1/2 语义必须落回指定节点），**所有写回一律用 `updateCurrentMessagesAppendingNew`**（已存在按 id 就地替换，新消息成为末尾新节点）。**这条语义已被咬过三次**，见 §10.7。
7. **标记绝不进 prompt；注入必须从「还带标记的列表」派生。** 标记是给用户看的回执（正文在 metadata 里），到模型手上的唯一通道是注入的通知。把标记提前滤掉再注入 ⇒ 通知一条都派生不出来（模型只能看着 `completed` 猜）。见 §10.6。
8. **一条结果一条回复。** 触发轮按队列逐条开（取队首即销账）、且只注入自己那条的通知；只有用户自己发起的轮次注入全部未汇报的结果。旧的「标记之后是否已有 assistant 文本」判据在三结果同时到达时必然退化，见 §10.5。
9. **同一会话同一时刻只允许一轮生成。** 开轮前硬查 `activeJobCount() > 0`（**不能只查 `generationJob` 的引用**：`setJob` 会无条件替换它）。见 §10.9。
10. **用户按停止要连投递队列一起清**（`taskDeliveries.clear()`），否则当前轮停下后队列立刻再开一轮——「停止要点三次」。见 §10.8。
11. **前台服务的启动 API 取决于前后台**：前台用 `startService`（**不附带「5 秒内必须 startForeground」的契约**），后台才用 `startForegroundService`。用错 ⇒ `startForeground` 一旦失败（catch 里 `stopSelf`）契约永不满足 ⇒ 系统在主线程抛 `RemoteServiceException` **杀掉进程**。见 §10.1。
12. **子代理的工具结果必须写回 `Tool part.output`**（`isExecuted = output.isNotEmpty()`；provider 只把 `isExecuted` 的 Tool part 发成 `role:"tool"` 消息），**且只执行没执行过的调用**（`unexecutedToolCalls()`，否则死循环）。空输出要写占位文本——留空会让这条调用被整条丢掉。见 §10.2/§10.3。
13. **子代理的次数/时长上限已按用户要求全部移除**（轮数 / 同一调用重复 / 单工具预算 / 8 分钟墙钟期限）。真因修掉之后它们只会砍掉合法长任务；代价是「真卡住时没有任何回执」，判定看日志页最后一行 `step N:` 是否推进。见 §10.10。

---

## 3. 未决与推迟项（交后续裁决）

### 3.1 已修：三处无锁整对象写（最终审查的 Important ③，用户裁决「现在修」）

`initializeConversation` / `generateTitle` / `generateSuggestion` 三处曾可能吞掉投递刚写的标记。**已修**（`837e2b63`→`fa90d606`→`7e8a8882`）：
- `initializeConversation` **已载入则跳过重载** + 载入部分加锁并在锁内复查 `loaded`——顺带**恢复了 spec §6.1 那条被违反的规则**（「已载入的会话绝不重新载入」）。**行为变化已被复核判定安全**：本仓恢复流程不可能在会话存在时改写活库（暂存恢复在 `startKoin` 之前应用、WebDAV/S3 只 `stageRestore` 后重启、`ChatService.cleanup()` 零调用者）。
- 新增 `mergeConversationState`：`generateTitle` / `generateSuggestion`（两处）改为在 Main 上以**已载入会话的实时内存态**为基合并（未载入时退回库读；**绝不能**用 `state.value` 兜底——那是 `Conversation.ofId` 的空壳，写它等于清空会话）。
- 过程留痕：计划里那版**编译不过**（`synchronized` 块内放了两个挂起调用，Kotlin 报 `The 'first' suspension point is inside a critical section`；即便能编译也会因挂起后 MONITOREXIT 泄漏会话监视器），且第一版 `mergeConversationState` **漏了 memory-first**、由复核抓出后补上（见 §2.5）。

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

**2026-09-25 晚已在真机上逐条验过并全部通过**（期间暴露的问题见 §10；用户结论：「没问题了」）：
切屏后子代理仍跑完并触发回复 ✔、三条并行结果各得一条回复 ✔、退出应用回来后不崩 ✔、
停止按钮一次即可停 ✔、不再出现「已中断（应用退出）」的误报 ✔。

**原清单（保留备查）——真正要上设备的 4 项**：
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
- **（2026-09-25 追加）** 合并后做「诊断轮」时，重写对账判据函数**把极性写反**：原参数叫 `isTracked` 却收「没登记」的 lambda，名字与语义相反，我照名字抄了一遍，于是本进程登记过的任务全被判「应用退出」。单测跟着那个撒谎的名字写，全绿；只有设备日志抓到了。
- **引入「落库前先同步写内存」这条不变量后，只在眼前那两处应用了它，新写的第三个写者漏了**——新代码不会自动继承旧代码的教训，除非把它写成规则并逐处核对。
- 在 `suspend` 函数里写 `synchronized` 时没有逐个检查块内调用是否挂起——照着自己写对的那处（`ensureLoaded`）套模板，而新函数多了两个天然挂起的副作用，代价是一轮编译失败。
- 把「只看标签更稳」当取舍写进计划，而没先把两种失效模式的后果摆出来比较（漏过滤可恢复 vs 误过滤不可恢复）。

---

## 8. 下一步（给下一个会话）

**这条线的工作已经结束**：九个任务 + 设备核验期的 20 个修复全部在 `master` 上、CI 全绿、最终整支审查判可合并；设备核验 §4 **已全部通过**（2026-09-25 晚）。

1. ~~设备核验~~ **已完成**（见 §4 顶部与 §10：期间暴露并修掉了 10 类问题，其中 3 类是我在修前几类时引入的回归）。
2. **可选改进**：§3.3 那条「① 的持久化被推迟最多一整轮生成」。不是缺陷，是权衡；若要做，方向写在那一节。
3. **其余推迟项**：§3.2 已由最终审查分诊为「没有一条是 must-fix」。

### 需要原文时去哪找

| 要什么 | 去哪 |
|---|---|
| 全部裁定（约 65 条 `Ruling:`，含每条的理由与代价） | `.superpowers/sdd/2026-09-19-async-subagent-lifecycle/progress.md`（867 行，**git-ignored，按用户裁决保留**） |
| 每个任务的需求原文 | 同目录 `task-N-brief.md`（由计划生成） |
| 每轮实现者的自述与 CI 证据 | 同目录 `task-N-report.md` |
| 各轮审查的输入 diff | 同目录 `review-<BASE>..<HEAD>.diff` |
| 计划里的全部修正记录 | `docs/superpowers/plans/2026-09-19-async-subagent-lifecycle.md` 末尾的「自检记录」 |
| 设计的权威表述 | `docs/superpowers/specs/2026-09-19-async-subagent-lifecycle-design.md` |

### 恢复时的第一件事

```bash
cd X:/projects/rikkahub
git log --oneline -3 && git rev-parse HEAD origin/master && git status --short
```

`HEAD` 应等于 `origin/master`、工作树应干净。若最后几条提交里有你没见过的，先读它们的提交信息（每条都写了「为什么」），再读上面那张表里对应的报告。

---

## 9. 2026-09-25 追加：诊断轮与一处我造成的回归

**症状**：设备核验第一条失败——切屏后子代理确实跑完了（日志里 `finish … stored=COMPLETED`），但会话里出现 `Agent "x" 已中断（应用退出）`，且不触发回复。

**根因**：**我引入的回归**（不是原设计缺陷）。见 §7 最后一条与计划「自检记录」里那条合并后回归：我重写对账判据函数时把极性写反了，`registry.get(taskId) == null` 这个「没登记」的 lambda 被当成「已登记」用，于是**本进程登记过的任务全被判中断**。

**为什么只有设备能抓**：两个方向自洽（单测照那个撒谎的参数名写，全绿），而判据的唯一边界是「本进程是否认识它」——这恰好是单测里被 mock 掉的那一层。设备日志里 `reconcile: judged=sub_X … known=sub_X`（同一毫秒，判据说没登记、证据表说登记着）是决定性证据。

**修法**：把极性从代码里删掉——参数由布尔谓词 `isTracked: (String) -> Boolean` 改为 `knownTaskIds: Set<String>`，判据 `taskId in knownTaskIds`，调用点传 `registry.all().map { it.taskId }.toSet()`（判定与诊断日志用**同一份**集合）。补双向回归测试。

- **子代理自身循环的既有缺陷（2026-09-25，多代理并行时暴露，已修）**：`SubAgentRuntime.executeSync` 把工具结果追加成 **user 消息**，`UIMessagePart.Tool.output` 始终为空；provider 只把 `output` 序列化成 `role:"tool"` 消息 ⇒ 模型以为工具没返回东西，于是把开场白当结论交回来（白卷）或反复重调工具（打转 ⇒ 三代理并行时 CPU 97%）。修法：就地写回 `Tool part`（与 `GenerationLoop` 同形）、空回复判失败、`MAX_SUB_AGENT_STEPS = 64`。**与本轮异步改造无关，但它说明这条链路此前只被「能否送达」检验过，没被「送回来的对不对」检验过。**

- **子代理的四道上限已按用户要求全部移除**（轮数 32 / 同一调用重复 3 / 单工具 10 / 墙钟 8 分钟）：它们是给「打转」装的护栏，而打转的真因是工具结果没送到模型（已修）。**代价要记住**：任务若真的卡住（provider 流挂死、或某个工具一直报错导致模型反复重试），不会再有任何回执——卡片停在「运行中」、前台服务一直被持有。要重新加回来只需恢复这四个常量与对应判断（`SubAgentRuntime`）；判定卡死的最快手段是看日志页最后一行 `step N: …` 是否还在推进。
- **本次会话最后两轮各自的回归**（留档）：①「标记不进 prompt」那一版把滤标记放在了注入**之前** ⇒ 通知一条都派生不出来（模型干看「已完成」说「结果没送到」）；②触发轮复用了上一条助手消息 ⇒ 多条回复堆在同一条消息里。两处都已修，并留下可核日志（`inject: round=trigger task=X → N 条通知`）。

**同时留下的一套取证设施**（`04c49baf..` 之后的 4 个提交，均为纯诊断，判定语义不变）：
- 每条日志带 `pid=… procStart=<毫秒>(HH:mm:ss)`；
- 子代理卡片自带 `launched_at` / `process_started_at`（随卡片落库，重启后仍可核「这张卡片是哪个进程写的」）；
- `register`/`finish` 带 **registry 实例指纹**；对账行带判了哪些 taskId、卡片自带的两条时间、registry 指纹、已知集合；
- 投递进入/放行/跳过/完成各一行；前台服务的 `acquire 失败` / `进前台成功` / `进前台失败（已清空全部持有者）` / `停服务` 全部进应用内「日志」页。
- 教训：**这套日志第一次派上用场，抓的就是我自己**——此前它被设计成「给下一个人的取证工具」。设备核验清单 §4 的 4 条仍然有效，其中「切屏后跑完并触发回复」现在有了逐条可核的日志依据。

---

## 10. 设备核验期间（2026-09-25 晚）暴露的问题与修复

**范围**：`04c49baf..343ff283`（20 个提交，17 文件 +971/−70）。CI 全绿，包挂在 `nightly-debug`。
下按发现顺序记「现场 → 根因 → 修法」。**§2 的承重约束里，凡本节新增的都标了 ⑨⑩… 编号。**

### 10.1 「应用退出」回执是真的：前台服务契约把进程杀了（最要紧的一条）

- **现场**：真机崩溃栈
  `android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{… ChatGenerationForegroundService}`；
  以及此前反复出现的「应用退出/后台进程被回收」回执。
- **根因**：`acquire` 用 `startForegroundService` 起服务 ⇒ 系统开 5 秒硬计时器；服务里
  `startForeground` 有真实失败面（那一瞬间退到后台的 FGS 限制、配额耗尽…），我们的 catch 里
  `activeGenerations.clear() + stopSelf()` ⇒ **契约永不满足** ⇒ 5 秒后系统在主线程抛异常杀进程。
- **修法**（`85b6b3fe`）：前台用 `startService`（**不附带那条契约**），后台才用
  `startForegroundService`；新增 `AppForegroundTracker`（ActivityLifecycleCallbacks 计数）。
  `KeepAliveService` 里同形问题一并改。
- **教训**：此前我把「应用退出」归给厂商省电/Doze——**方向错了，凶手是我们自己**。

### 10.2 子代理看不到自己的工具结果（既有缺陷，与本轮异步改造无关）

- **现场**：子代理「已完成」但正文是一句「让我去看看最近的消息」（白卷）；或反复重调同一工具
  （三个并行时 CPU 97%）。
- **根因**：`executeSync` 把工具结果**追加成 `UIMessage.user(...)`**，而承载调用的
  `UIMessagePart.Tool.output` 始终为空。provider 只把 `output` 序列化成紧跟 `tool_calls` 的
  `role:"tool"` 消息（`ChatCompletionsAPI` 的 `PartGroup.Tools`，content 取自 `toolResultText()`）
  ⇒ 模型看到「tool_call(content="") + 一条 user 消息」。
- **修法**（`b8d01f10`）：结果就地写回 `Tool part`（`withToolOutputs`，与 `GenerationLoop` 同形）；
  工具不存在/抛异常也一律变成工具结果；**空回复判失败而非成功**（`subAgentCompletion`）。
- **附带的坑**：工具没有输出时必须写占位文本（`TOOL_NO_OUTPUT_NOTE`）——`isExecuted` 就是
  `output.isNotEmpty()`，留空会让这条调用被 provider **整条丢掉**，下一轮还会被当成「没执行」重跑。

### 10.3 任何工具调用都死循环（**我引入的**）

- **现场**：`step 37: search_web -> 1 part(s)` 一行一秒地涨到几十轮；「连 eval_javascript 算 1+1 也循环」。
- **根因**：`StreamChunkHandler` 只在「列表末尾不是助手消息」时才新建助手消息，否则**合并进末尾那条**
  （主循环因此每轮都用 `filter { !it.isExecuted }` 挑调用）。10.2 的就地写回之后，末尾那条助手消息
  会一直带着上一轮那个**已执行**的 Tool part ⇒ 每轮重跑它一次。
- **修法**（`c914c38a`）：`unexecutedToolCalls()` 只执行没执行过的；每轮补一条空的助手消息作为
  **轮次边界**（与主循环 `responseBaseMessages` 同形）。

### 10.4 中断对账判据的极性被我写反（**我引入的**）

- **现场**：卡片刻着 `reason: app_exit`、但同一毫秒的日志里 `judged=sub_X` 与 `known=sub_X` 同时出现。
- **根因**：原函数收 `isTracked: (String) -> Boolean`，而调用点传的 lambda 是 `get(taskId) == null`
  （**意思是「没登记」**）——名字与语义相反，靠函数体 `if (!isTracked(...))` 的双重否定救着用；
  我重写时**照着名字抄**成 `if (isTracked(...)) return`，判据整个反转。
- **修法**（`dcb2f4e1`）：**把极性从代码里删掉** —— 参数改为 `knownTaskIds: Set<String>`，
  判据 `taskId in knownTaskIds`；调用点传 `registry.all().map { it.taskId }.toSet()`，
  **判定与诊断日志用同一份集合**。补双向回归测试。
- **教训**：名字与语义不一致的参数，重写时**不能信名字**；凡是「布尔参数的语义可被反向解释」的地方，
  都换成只有一个读法的表示（集合/枚举）。

### 10.5 三条结果只得到一条回复

- **现场**：`startTaskDelivery … pending=3 queueLeft=2`，此后不再开轮（三条结果一条回复）。
- **根因**：跳轮判据「标记之后是否已有带非空文本的 assistant 消息」在三结果几乎同时到达时必然退化：
  第一条结果的回复落在**所有**标记之后 ⇒ 后两条被判「已被回复」直接丢掉。
- **修法**（`3dafbc98`）：队列就是账本（取队首即销账），触发轮只开自己那条、且
  `injectTaskNotifications(onlyTaskIds = setOf(taskId))` 只注入自己那条通知。

### 10.6 标记进了 prompt / 注入顺序反了（**我引入的**）

- **现场**：主代理说「另一个也跑完了，不过结果还没送到我手上」——它看得见「已完成」却拿不到正文。
- **根因（两层）**：① 标记是 SYSTEM 消息，Chat Completions 类 provider 会把 SYSTEM 原文发给模型
  （Claude 那类整类滤掉，所以只在部分 provider 上暴露）；② 我为了「标记不进 prompt」把过滤放在
  注入**之前**，而通知的正文正是从标记 metadata 里读的 ⇒ **通知一条都派生不出来**。
- **修法**（`ba8c4cfa` + `753abc0f`）：**从还带标记的列表派生通知，再滤掉标记**（只作用于最终发给
  provider 的那份）；加 `inject: round=… → N 条通知` 作为可核证据。

### 10.7 回复堆在同一条消息 / 改掉前面那条回复 / 三条回复叠在一起（同一个根因，**咬过三次**）

- **现场**：① 完成回执与主代理回复「合成一条」并出现 1/2 分支；② 之后发消息，回复**直接改掉前面那条**；
  ③ 三条回复视觉上叠成一团。
- **根因**：`updateCurrentMessages` 是 `messages[index] → messageNodes[index]`。**本轮请求构建之后
  只要有节点被追加**（另一个子代理的回执、或并发的另一轮），回复就落进**别人的节点**；节点里对
  「没有的新 id」是「追加 + 移动 selectIndex」⇒ UI 上先是分支 1/2，再就是「前面那条被改写」。
- **修法**（`ba8c4cfa` → `da0a135a`）：新增 **`updateCurrentMessagesAppendingNew`**
  （已存在按 id 就地替换、新消息一律成为末尾新节点）；**除 `messageRange != null` 的重新生成/分支
  路径（它的 1/2 语义必须落回指定节点）之外，所有写回都用它**。对正常追加的轮次两者等价。
- **教训**：**在一个可能在生成期间长大的会话上使用「快照下标」**——这条规则已被咬三次
  （标记被吃掉的 Critical、1/2 分支、改掉前面那条回复）。别再用下标语义做写回。

### 10.8 停止要点三次

- **现场**：三条结果排队待开轮时按停止，只停住当前那轮，队列立刻再开下一轮。
- **根因**：`stopGeneration` 取消该会话的全部生成 job，但 `taskDeliveries` 没清；完成回调与
  `saveConversation` 尾部立刻把下一条排空。
- **修法**（`343ff283`）：停止时 `session.taskDeliveries.clear()`。
- **已知取舍**：被停掉的那几条结果**下次打开会话时仍会被重新排队**（它们确实还没被汇报过）。
  若要「停过就不再提」，需给标记加一条「用户已放弃」的印记——**这是个明确的设计选择，尚未裁定**。

### 10.9 同会话并发开轮（硬闸）

- **风险**：`setJob` 会无条件替换 `_generationJob.value`（`cancelPrevious=false` 时不取消旧的），
  而 `advanceConversation` 只查「当前 job 引用是否为空」——引用被清/替换时判据与实际活跃 job 分叉，
  后果就是同会话多轮流式回复并存（10.7-③ 与 10.8 的现场都与此相符）。
- **修法**（`da0a135a`）：`startTaskDelivery` 开轮前硬查 `activeJobCount() > 0`，有在飞的就**推迟**
  （队首不动，等那一轮的完成回调再排空）。

### 10.10 子代理不设上限（用户裁定）

`7e260273` 加的 8 分钟墙钟期限与 `90173bbe` 加的重复判据/单工具预算、以及轮数上限，
在 `753abc0f` **全部移除**（用户要求）。它们是给 10.3 那个「打转」装的护栏，而真因已修。
**代价**：任务真卡住时不再有任何回执（卡片停在「运行中」，前台服务一直被持有）；
判定看日志页最后一行 `step N:` 是否还在推进。要加回来只需恢复那四个常量与判断。

---

## 11. 诊断设施：应用内「日志」页该怎么读

日志页是**内存环形缓冲（100 条，最早的行先被挤出）**，每条都带
`pid=<pid> procStart=<epoch 毫秒>(HH:mm:ss)` —— **procStart 是判断「这是不是一个新进程」的稳定证据**
（不要依赖「最老的一行」，它会被挤掉）。关键行：

| 日志行 | 该怎么读 |
|---|---|
| `process start: …` | 进程出生（可能已被挤出） |
| `register <taskId> conv=… registry=@<hash>` | 子代理登记。**registry 指纹**与下面 reconcile 行不一致 ⇒ 两个 registry 实例（DI 问题） |
| `finish … attempted=<s>/<r> stored=<s>/<r>` | 终态。两者不同 = 「先到者胜」契约生效（取消与完成撞车） |
| `startTaskDelivery: task=… 开一轮 activeJobs=N …` | **N 必须为 0**；出现「已有在飞生成…本轮推迟」说明硬闸挡住了并发开轮 |
| `inject: round=trigger\|user task=… → N 条通知` | 触发轮 **N 必须为 1**；N=0 说明通知没派生出来（标记被提前滤掉那类回归） |
| `deliver entry/proceed/done/skip: … generating=…` | `proceed` 那行紧贴写回，**`generating` 必须为 false**（为 true = 闸门漏了） |
| `writeback: round=… snapshot=N nodes=M` | **N 必须等于 M**；不等 = 本轮期间会话被追加过节点（下标错位根因） |
| `reconcile: judged=… known=… registry=@…` | 判据命中的 taskId 与它自带的证据（卡片创建时刻/发起进程启动时刻） |
| `进前台成功/失败 …`、`停服务 holders=…` | 前台服务有没有真的生效（10.1 的现场证据） |
| `step N: <tool>(<参数前缀>) -> K part(s): <输出前缀>` | 子代理的每一轮：查「为什么打转/白卷」只看这一行 |

---

## 12. 本轮追加的失误清单（接 §7）

1. **重写带布尔参数的函数时照名字抄极性** ⇒ 判据反转（10.4）。名字本身是错的（`isTracked` 收「没登记」），
   而我**没去看调用点**。
2. **「把标记挤出 prompt」时把过滤放在了注入之前** ⇒ 通知一条都派生不出来（10.6）。顺序敏感的地方改一处要看全链。
3. **又一次拿「快照下标」写回会话** ⇒ 回复落进别人的节点（10.7）。这已是同一处语义第三次咬人。
4. **给「打转」装护栏时先解释后取证**：把打转归给「模型贪多/上游限流」，被用户一句
   「连 1+1 也循环」直接推翻——**先问机制、再装护栏**。
5. **把「应用退出」归给厂商省电**（10.1）；**把用户「进程不可能死」当成需要反驳的误解**——
   事实是进程真的被系统杀了，而原因在我们自己的前台服务用法里。
6. **两次把构建搞红**（重复 import、`List.size()` 当函数、`executeSync` 改块体漏闭括号）：
   本机无编译器 ⇒ 改完必须靠 CI 收口，别把「读起来对」当「编得过」。
7. **诊断行放错位置导致自己误判**：`deliver proceed` 最初记在闸门**之前**，`generating=true` 其实正常，
   我差点据此误报「闸门漏了」。诊断行必须放在它要断言的那一刻。

