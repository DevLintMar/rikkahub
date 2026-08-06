# 记忆注入序号分隔 + 流式传参占位符标题修复

**日期**：2026-08-06
**需求来源**：用户反馈两条（前序工具描述纠正已先行修复，见 6462125）
**执行记录**：改动极小（各 1 处 Kotlin 改动 + sed 串替换），控制器直接修复落地，未走 SDD 子代理派发。Task 1 = `2e437bd`，Task 2 = `8d0cb53`，CI run 31083315002 ✅。

## Global Constraints（约束所有任务）

1. **本机无 Android 编译器**：禁止 gradle/kotlinc/测试。验证 = 静态阅读 + CI（`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`，核对 headSha）。
2. 字符串双写 en+zh（六 locale 同步，若涉及已有 key）。
3. runCatching 不能包 suspend。
4. 精确执行，永不越界。
5. 中文 conventional commit；本地提交后控制器统一推送验证。

## 现状关键事实

- `GenerationPrompts.buildMemoryPrompt(activeMemories, savedMemories)`：活跃/已保存记忆都用 `- 标题 — 描述` 列表行（活跃多一行缩进全内容）。内容以 `-` 开头的记忆与列表行混杂，AI 无法区分条目边界。
- `ToolUIContext.arguments = tool.inputAsJson()`（Message.kt：解析失败返回空 JsonObject，**不抛异常**）；流式传参期间 input 是残缺 JSON → arguments 为空对象 → 参数取不到。
- **占位符泄露的根因**：`memoryToolTitle`（MemoryToolsUIs.kt:54-68）的兜底分支对**已参数化**的串做了**零参** `stringResource(titleResId)` → Android 对缺参格式串返回原串 → UI 显示字面 `%1$s`（上轮终审 M1，此前判定不可达——流式期 arguments 为空使其实际可达，用户已目击）。
- 全仓核查结论（26 个渲染器 title()）：
  - **有问题**：仅 `memoryToolTitle` 零参兜底（影响七个记忆渲染器）。
  - **已有保护**（参数取不到 → 走非参数化 default 串，无泄露）：edit/read/write_file、glob、grep（`if (x != null) ... else ..._default`）、shell（`?: return ..._default`）。
  - **无保护但无泄露风险**（fallback 传 `?: ""`，不显示占位符；流式期标题带尾随空格，纯外观）：search_web、conversation_search、calendar_create（BuiltinToolUIs）、memory legacy title（BuiltinToolUIs legacyTitle 链 `?: ""`）。

---

## Task 1：注入序号分隔

**文件**：`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt`

**改动**：`buildMemoryPrompt` 中两个列表改用 `forEachIndexed` 输出序号行（序号从 1 起，与 id 无关）：
- 活跃：`1. 标题 — 描述` 行 + 下一行缩进全内容
- 已保存：`1. 标题 — 描述` 行（尾部 read_memory 说明保留不变）

**验收**：多条记忆（含内容以 `-` 开头）在注入中逐条以序号开头，边界清晰。

## Task 2：流式传参期标题不显示占位符

**文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt`

**改动**：
1. `memoryToolTitle` 兜底分支改为 `stringResource(titleResId, "")`（传空参而非零参）；更新 KDoc（"均取不到时返回带空参的标题串，避免流式传参期显示字面占位符"）。
2. 七个记忆工具 title 串（六 locale）去掉 `: %1$s` / `：%1$s` 前的空格——传空参会渲染尾随冒号+空格，去空格后为"读取记忆："，可接受；若保留空格则出现悬挂空格。（实现者也可选择保留空格——以无占位符泄露为硬标准，外观从简。）

**验收**：AI 正在流式调用记忆工具（参数未传完）时，概览标题只显示"xx记忆"类基础文案（无 `%1$s`）；参数传完后正常显示"xx记忆：标题"。

## Verification（控制器执行）

- 两任务 review 通过后统一 push + CI 触发 + headSha 核对。
- 设备核验：注入序号格式（可在对话中让 AI 读系统提示或看 logcat）；流式调用记忆工具时标题无占位符。
