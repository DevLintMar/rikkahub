# 工具展示修正 + 更新禁用 + 官方信息移除 + 时间提醒总是插入（4 项）

## Context

用户在上一轮 6 项质量修复基础上，提出 4 项新改动：

1. **ask_user 显示修正**（纠正 Task 5 的理解）：只有 AI 正在执行询问（`loading`=true、显示三个点）时显示"正在询问用户..."；AI 已提出问题等待用户回答时恢复显示"询问 X 个问题"。
2. **暂时禁用更新检查/自动更新**：`UpdateChecker.checkUpdate()` 不再发起网络请求（`https://updates.rikka-ai.com/`），UpdateCard 保持 Loading 不展示。
3. **移除官方渠道/赞助/标注信息**（用户确认范围：保留关于页指向 fork 仓库的链接）：赞助弹窗、捐赠页+SponsorAPI、设置页文档链接（docs.rikka-ai.com）、分享文案官网行、RikkaHub provider 描述里的"官方/赞助"措辞 + 死串清理。
4. **时间提醒加"总是插入"选项**：在助手"时间提醒"开关下方新增一个选项，开启后每个用户消息前都插入时间提醒（忽略 1 小时间隔阈值）。

## Global Constraints

- 本机无编译器：静态编写 + review，编译验证全靠 CI
- CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前）
- 字符串双写：英文 `values/` + 中文 `values-zh/`（ja/ko/ru/zh-rTW 有 key 时一并改/删）；删除字符串先 grep 零引用，再从存在该 key 的所有 locale 删
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm
- `assembleDebug` 不编译测试源码；`applyTimeReminder` 已有单参数测试 → 新参数加默认值保持兼容
- Assistant 是 `@Serializable`（DataStore JSON 存储）→ 新字段带默认值安全，无需迁移

---

## Task 1: ask_user 显示修正（loading → "正在询问用户..."）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`

`loading` 语义：`loading = loading && !step.tool.isExecuted`（AI 正在执行询问、显示三个点）。等待回答阶段 `loading=false`。

- [ ] **Step 1: AskUserToolStep label 逻辑**

```kotlin
text = if (loading) {
    stringResource(R.string.chat_message_tool_ask_running)
} else if (!isPending && questions.size <= 1) {
    firstQuestion
} else {
    stringResource(R.string.chat_message_tool_ask_questions, questions.size)
},
```

（等待回答 `isPending` 时落入 else → "询问 X 个问题"；已回答单问题 → firstQuestion。）

- [ ] **Step 2: 更新字符串值（带省略号）**

en: `chat_message_tool_ask_running` → `Asking the user...`
zh: `chat_message_tool_ask_running` → `正在询问用户...`

- [ ] **Step 3: push + CI**

---

## Task 2: 暂时禁用更新检查/自动更新

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt`

- [ ] **Step 1: checkUpdate() 短路**

```kotlin
fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
    // 暂时禁用更新检查/自动更新：不发起网络请求（如需恢复，还原为 git 历史中的原逻辑）
}.catch {
    emit(UiState.Error(it))
}.flowOn(Dispatchers.IO)
```

（flow 无 emit → `stateIn(…, UiState.Loading)` 保持 Loading → UpdateCard 无 onError/onSuccess 分支 → 不渲染任何内容。`downloadUpdate` 保留但 UI 不可达。）

- [ ] **Step 2: push + CI**

---

## Task 3: 移除官方渠道/赞助/标注信息

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt`
- Delete: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingDonatePage.kt`、`app/src/main/java/me/rerere/rikkahub/data/api/SponsorAPI.kt`、`app/src/main/java/me/rerere/rikkahub/data/model/Sponsor.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- Modify: `app/src/main/res/`（6 个 locale 的 strings.xml）

**保留**：关于页（`SettingAboutPage.kt`）的官网/GitHub/许可证链接（用户确认保留）。

- [ ] **Step 1: SettingPage.kt 移除 3 个块 + 清理 imports**

移除（并清理对应未使用 import）：
1. 赞助弹窗 `if (settings.launchCount > 100 && …) { AlertDialog(…) }`（约 L95-119）→ 清 `HugeIcons.WavingHand01`、`Button`
2. 文档条目（`docs.rikka-ai.com`，约 L349-360）→ 清 `HugeIcons.Book01`，删 `setting_page_documentation(_desc)`
3. 捐赠条目（约 L367-372）→ 清 `HugeIcons.InLove`，删 `setting_page_donate(_desc)`

- [ ] **Step 2: 删除 3 个文件 + 移除 DI + 路由**

- `SettingDonatePage.kt`、`SponsorAPI.kt`、`Sponsor.kt` → `bash ~/.claude/scripts/trash.sh`
- `DataSourceModule.kt`：删 `import me.rerere.rikkahub.data.api.SponsorAPI` + `SponsorAPI.create(get())`
- `RouteActivity.kt`：删 `import …SettingDonatePage`、`entry<Screen.SettingDonate> { SettingDonatePage() }`、`data object SettingDonate : Screen`

- [ ] **Step 3: 改 2 个字符串（6 locale 值对齐）**

`setting_page_share_text`：去掉 `\n\nWebsite: https://rikka-ai.com/` / `\n\n官网: https://rikka-ai.com/` 段（ja/ko/ru/zh-rTW 同减对应官网段）。

`rikkahub_provider_description`：去掉"官方/Official"与"使用用户赞助资金/using user sponsorship funds"措辞：
- en → `Free AI service, providing some free models for new users as fallback, no API key required. Has strict rate limits, if you have purchased your own provider, please use your own provider as much as possible.`
- zh → `免费 AI 服务，为新用户提供部分免费模型作为备用，无需 API 密钥。有严格速率限制，如已购买自有服务商，请尽量使用自有服务商。`

- [ ] **Step 4: 删死串（6 locale）**

`setting_page_sponsor_alert_title/desc/confirm/dismiss`、`setting_page_documentation(_desc)`、`setting_page_donate(_desc)`、`donate_page_title/donation_methods/kofi_desc/afdian_desc/sponsor_list` —— 共 13 个 key，从所有 6 个 locale 删。

- [ ] **Step 5: push + CI**

---

## Task 4: 时间提醒"总是插入"选项

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformer.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Assistant 新字段**

`Assistant.kt` 在 `enableTimeReminder`（L50）后加：

```kotlin
val timeReminderAlwaysInsert: Boolean = false, // 时间提醒总是插入（忽略间隔阈值）
```

- [ ] **Step 2: Transformer 逻辑**

`TimeReminderTransformer.kt`：

```kotlin
override suspend fun transform(ctx, messages): List<UIMessage> {
    if (!ctx.assistant.enableTimeReminder) return messages
    return applyTimeReminder(messages, alwaysInsert = ctx.assistant.timeReminderAlwaysInsert)
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    alwaysInsert: Boolean = false,
): List<UIMessage> {
    ...
    if (alwaysInsert || gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
        result.add(buildTimeReminderMessage(gapSeconds, currInstant))
    }
    ...
}
```

（默认参数保持现有测试 `applyTimeReminder(messages)` 兼容；首条用户消息本就总是插入。）

- [ ] **Step 3: 设置 UI 加选项**

`AssistantMemoryPage.kt` 在 `enableTimeReminder` 的 `item(...)` 之后（约 L248 后）追加：

```kotlin
item(
    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_always)) },
    supportingContent = {
        Text(stringResource(R.string.assistant_page_time_reminder_always_desc))
    },
    trailingContent = {
        Switch(
            checked = assistant.timeReminderAlwaysInsert,
            onCheckedChange = {
                onUpdateAssistant(
                    assistant.copy(timeReminderAlwaysInsert = it)
                )
            }
        )
    }
)
```

- [ ] **Step 4: 新增字符串（en + zh）**

```xml
<string name="assistant_page_time_reminder_always">Always insert</string>
<string name="assistant_page_time_reminder_always_desc">Insert a time reminder before every user message, ignoring the time-gap threshold</string>
<!-- zh: 总是插入 / 在每个用户消息前都插入时间提醒，忽略时间间隔阈值 -->
```

- [ ] **Step 5: push + CI**

---

## Verification

- 每任务独立 commit → push → `gh workflow run nightly-build-debug.yml`；CI 红时 `--log-failed` 区分 flake 与真实编译错误
- 设备级核验：ask_user 执行中（三个点）显示"正在询问用户..."、等待回答显示"询问 X 个问题"；聊天抽屉不再出现更新卡片；设置页无赞助弹窗/捐赠项/文档项；分享文案与 RikkaHub provider 描述无官网/赞助字样；时间提醒开关下出现"总是插入"选项且行为符合预期
