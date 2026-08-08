# 交接文档：跨包名恢复兼容（rebase + images 备份 + 诊断）+ 气泡透明度时间线修复 — 下一阶段入口

**日期**：2026-08-08
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `55c032f`，工作树干净，debug 与 release CI 全绿。

---

## 0. 一句话概况

接上一交接 `9d133d8`（scrape_web 阶段）。本阶段两件事：**① 跨包名恢复兼容**（恢复时把备份内容里的绝对 `file:///data/user/0/<包名>/files/` 路径重定位到当前包；settings 即时 rebase、DB 用"哨兵 + 重启后一次性重写"；加 restore_diag 落盘回放 / 备份 uploadFiles / 启动头像存在性三层诊断；`images/` 目录纳入备份）；**② 气泡透明度下工具调用链图标"浅色白底" + 时间线竖线**（根因=同色半透明遮罩二次合成变浅；多轮演进后最终用 CompositionLocal 修复"共享可变 var 组合期赋值/绘制期读取串值"的坑）。末尾版本号升 3.1.1。全程 CI 绿。master `82d96d0` → `55c032f`（14 commits）。

---

## 1. 已完成工作（commit 链）

### 一、跨包名恢复兼容（备份零改动，恢复端 rebase）

| commit | 内容 |
|---|---|
| 229dc91 | 新增 **`RestorePathRebaser.kt`**（`data/sync/`）：正则 `file:///data/(user/\d+\|data)/[^/]+/files/` → 当前 `filesDir`，同包名幂等 + `foreignPrefixCount`；WebDavSync/S3Sync 恢复 `settings.json` 时先 rebase 再 decode；DB 恢复写 `noBackupFilesDir/restore_path_rebase_pending` 哨兵；新增 `RestorePathRebaserTest`（幂等/旧包名/work profile/JSON 全量/非 file:// 不动） |
| 3d1d304 | **RikkaHubApp 启动一次性 DB 重写**：按哨兵扫描 `message_node.messages` + `GenMediaEntity.source_paths`，逐行 UPDATE 后删哨兵（rawSupport 同 MessageFtsManager 取 DB 方式） |
| 2f022ad | **CI 修复**：`arrayOf(rebase(), id)` 被 Kotlin 推断为 `Array<Comparable<*> & Serializable>` 交集类型 → reified 推断编译错误；两处改 `arrayOf<Any>(...)` |

### 二、恢复/备份诊断（定位"头像丢失"闭环）

| commit | 内容 |
|---|---|
| e9b8eee | 恢复期落盘 `noBackupFilesDir/restore_diag.txt`（备份名 + items、settings 解码后 userAvatar 类型/图片头像数/rebase 次数、`restoredUploadFiles` 计数）——**恢复会 exitProcess 重启，进程内 Logging 丢失，必须落盘** |
| 8bab157 | 启动 `replayRestoreDiagnostics()`（回放 diag 进日志页后删文件）+ `logAvatarDiagnostics()`（每次启动对 userAvatar/助手头像/背景输出 `scheme/rebased/fileExists/url`） |
| 69af64e | 备份端 `prepareBackupFile` 输出 `items/uploadFiles/zipBytes`（Logging.log，日志页可见） |

**头像问题结论**（用户日志 + 诊断已定位）：restore 机制完全正常（`rebaseCount=4 userAvatar=Image`），但**备份包本身 `restoredUploadFiles=0`**（8/2 的旧备份无 upload/ 条目，历史数据残缺，可能是旧版跨包流程只 sed settings 留下的）。用户已确认头像恢复正常。

### 三、`images/` 目录纳入备份 + 恢复

| commit | 内容 |
|---|---|
| ab591d2 | 备份端把 `filesDir/images/` 顶层文件以 `images/<name>` 写 zip；恢复端解回 `filesDir/images/`。`GenMediaEntity.path` 是相对路径天然跨包，`source_paths` 已由 3d1d304 的启动重写覆盖 |

### 四、气泡透明度：图标白底 + 时间线竖线（多轮演进，最终根因见 §2）

| commit | 内容 |
|---|---|
| 3f8619f | 去图标"浅色白底"：半透明时去掉图标同色遮罩 + 时间线竖线（初版，线也去了） |
| 242eda2 | 半透明时改画分段时间线（行内 matchParentSize 版——**有结构性缺陷**，见 §2） |
| 8a39936 | 时间线改步骤单元级：`onGloballyPositioned` 测图标行高，竖线跳过图标 20dp、贯穿 belowLabel/展开内容 |
| 6b52986 | 聚合头下方去线（`aggregateMode \|\| index==0` —— **bug**，见 §2） |
| 555c307 | 改 `index == 0`（**仍 bug**，见 §2） |
| 55c032f | **最终修复**：`StepTimelineFlags` CompositionLocal 每步注入稳定 first/last |

### 五、版本号

| commit | 内容 |
|---|---|
| 7106009 | 3.1.0 → 3.1.1（versionCode 1173 → 1174） |

---

## 2. 关键设计决策（认知遗留）

### 跨包名恢复机制
- **备份 zip 本身干净**：`prepareBackupFile` 条目全相对路径（`settings.json`/`rikka_hub.db`(-wal/-shm)/`upload/…`/`skills/…`/`fonts/…`/`images/…`），DB 文件名固定 `rikka_hub`（包相对）。真正跨包不兼容 = **持久化在内容里的绝对 file:// URI** 三处：`message_node.messages`（聊天附件，主导）、`settings.json`（头像/背景）、`GenMediaEntity.source_paths`（图编辑源图，当前无 UI 消费）。
- **settings 即时 rebase**（解码前对 JSON 字符串做正则替换，对齐 memory `cross-package-backup-restore` 里验证过的 sed 做法）；**DB 不能恢复时改**（Room WAL 连接活着、-wal/-shm 刚覆盖，另开连接有竞态）→ 写哨兵、重启后首启一次性 SQL UPDATE 后删哨兵。重启本来就是恢复流程的一部分（BackupDialog `exitProcess(0)`）。
- **rebase 幂等**：正则 `[^/]+` 通吃任意包名（`me.rerere.rikkahub` 旧包/work profile `user/\d+`），同包名恢复替换结果相同 → 无副作用。
- 存储格式**不改为相对路径**（那是更深改造）；用恢复时 rebase 满足"备份不变、恢复兼容"。
- **known 边界**：`workspaces/`（rootfs + files）仍不入备份（memory `backup-coverage-gaps` 第三项未动）；图片编辑参考图存 cacheDir 不入备份，`source_paths` rebase 后指向缺失文件但无 UI 消费，影响忽略。

### 时间线白底/竖线（最大教训）
- **白底根因**：步骤图标后有 20dp `LocalCardColor` 同色遮罩（遮父级竖线用）。卡片半透明时遮罩用同一半透明色在已合成卡片上二次合成 → `αC+(1-α)(αC+(1-α)W)` 明显变浅 → 每个链内图标背后一块浅色底。新加的聚合头/折叠箭头无遮罩，所以干净。
- **修复模式**：不透明卡片维持原样（父级连续线 + 图标遮罩）；半透明卡片去遮罩，改**步骤单元级 drawBehind 分段时间线**：`onGloballyPositioned` 测图标行高，竖线跳过图标 20dp、贯穿 belowLabel/展开内容到单元底。首步不画上段（聚合头下方无线），末步无下方内容不画下段。
- **共享可变 var 串值（本轮最大的坑）**：`isFirstStep/isLastStep` 放 `ChainOfThoughtScopeImpl` 上，组合期遍历里赋值、绘制期 `drawBehind` 才读取 → **绘制时全读到最后一次迭代的值** → 所有步骤都画成首步/末步（竖线全变短 / 聚合头下方错出线）。**教训：绘制期要读的状态必须组合期固化——用 CompositionLocal（配 `key(index)`）每步注入，不能放共享 var。**
- 行内 `matchParentSize().drawBehind` 的缺陷：`matchParentSize` 匹配的是图标列 Box（高=20dp），上下段长度=0，线画不出来。

### 诊断设计
- 恢复会 `exitProcess(0)`，**进程内 Logging 缓冲清空** → 恢复期日志必须落盘（`noBackupFilesDir/restore_diag.txt`）+ 启动回放。`noBackupFilesDir` 不入 Auto Backup 也不入备份 zip。
- 日志页 = 设置 → 请求日志（`LogPage`，`Logging.log` 走 TextLog）。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `55c032f`（`fix(ui): 时间线首/末标记改用 CompositionLocal`），工作树干净
- **debug CI 绿**：`31211710584`（headSha `55c032f`）/ **release nightly-build 绿**：`31209873074`（headSha `55c032f`）——均为最终版
- 中途失败：`31190327965`（3d1d304，arrayOf 交集类型编译错）→ `2f022ad` 修复后全绿
- release 3.1.1 APK 已产出（run 31209873074），下载链接可在 Actions 页取

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（scrape_web 阶段） | `docs/superpowers/handoffs/2026-08-07-scrape-web-rework-next-phase.md` |
| 本阶段核心文件 | `app/src/main/java/me/rerere/rikkahub/data/sync/RestorePathRebaser.kt`、`data/sync/webdav/WebDavSync.kt`、`data/sync/S3Sync.kt`、`RikkaHubApp.kt`（rewriteRestoredFileUris/replayRestoreDiagnostics/logAvatarDiagnostics）、`ui/components/ui/ChainOfThought.kt` |
| 单元测试 | `app/src/test/java/me/rerere/rikkahub/data/sync/RestorePathRebaserTest.kt` |
| 相关 memory | `cross-package-backup-restore`（旧 PC sed 方案）、`backup-coverage-gaps`（workspaces 未备份） |

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装最新 debug APK（`55c032f` 起）：
1. **时间线**（气泡透明度 + 非白背景）：聚合头与首行图标之间**无**竖线；其余步骤竖线**完整**贯穿（含下方小字/展开内容）；首图标上方/末图标下方无线；图标无浅色白底
2. **跨包名恢复闭环**：源应用先确认头像正常 → **重新导出**新备份（日志页应见 `prepareBackupFile: uploadFiles=N`，N>0）→ 目标包导入 → `restore: restoredUploadFiles=N` 应 >0 → 头像/聊天附件恢复
3. **images/ 还原**：AI 生成图片历史（`images/` 里的）跨包名恢复后重新可见
4. **release 3.1.1**：装 release 包跑一遍 1-3（同包名恢复无副作用）

### ② 遗留 Minor / defer（不阻塞）

- **`workspaces/` 仍不入备份**：rootfs（可重下）+ `workspaces/<id>/files`（用户沙盒文件）——备份恢复后 workspace 标记 BROKEN（`checkWorkspaceIntegrity`），行为与现状一致；要纳入需评估 zip 体积
- 存储格式深改（相对路径持久化替代绝对 URI）未做——当前恢复时 rebase 已满足跨包需求
- `GenMediaEntity.source_paths` 的图（cacheDir 临时文件）不入备份，rebase 后指向缺失文件（无 UI 消费，忽略）
- 上一阶段遗留：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope 建模等（见 2026-08-07 交接文档 §5-②）

### ③ 历史挂起（更早遗留，未变）

- 乱召回（语义搜索 bug，需 rawTop logs）；Task 12 ripgrep artifact 流水线；UpdateChecker.kt 删 `return@flow`
- 本阶段 plan：`atomic-frolicking-hare.md`（已被本阶段跨包名计划覆盖，旧 scrape_web 内容已无）

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list ...` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **Compose 绘制期状态铁律**（本轮最大教训）：`drawBehind` 绘制期读取的状态必须组合期固化（CompositionLocal/remember），**绝不放共享可变 var**。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；六 locale 占位符串全写（本阶段未改字符串）。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。
- **本机 git 换行警告**（LF→CRLF）为仓库既有状态，非错误。

## 7. 停靠点

- **已完成**：跨包名恢复兼容（rebase + images 备份 + 三层诊断，头像问题定位为备份包历史残缺）+ 气泡透明度时间线修复（CompositionLocal 终版）+ 版本 3.1.1。master `55c032f`，debug/release CI 全绿。
- **待确认**：设备核验（§5-①）。
- **恢复动作**：读本文档 §4/§5；下一阶段开始前先让用户装 `55c032f` 的包核验本轮改动（重点时间线与 images 还原）。
