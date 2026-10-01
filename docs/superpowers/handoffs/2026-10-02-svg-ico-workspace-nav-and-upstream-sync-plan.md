# SVG/ICO 图片支持、工作区目录直达、切屏防崩治理与上游同步交接文档

> **接手必读**：本会话已完整落地并验证了 SVG/ICO 原生展示、工作区目录超链接直达、切屏 `TransactionTooLargeException` 智能熔断防崩、图片识别提升至 32MB、提示词文件/目录区分，并将版本号升级至 `3.3.0 (1177)`。同时，我们对上游领先的 **74 个 commit** 进行了全量分类审查。**Compact 后的首要任务是按本文档 §3 推进上游第一波与第二波的合并与移植。**

---

## 1. 本会话已完成工作（Commit 链：`17792d3b..eca0be5d`）

### 1.1 SVG / ICO 全链路图片支持
- **轻量级 Coil 3 ICO 解码器**（`IcoDecoder.kt`）：
  - 针对 Android 系统底层和 Coil 官方组件均不支持 `.ico` 的问题，实现了自定义的 `coil3.decode.Decoder` 和 `Decoder.Factory` 并注册到全局 `ImageLoader`；
  - 自动解析 `ICONDIR` 头并挑选最高分辨率/色深的图像帧；
  - 支持解压现代内嵌 PNG 帧，以及自动修正双倍高度掩码、拼装标准位图头交由 `BitmapFactory`，并附带 32 位 ARGB 像素阵列手动解码兜底。
- **SVG 全链路支持**：
  - 将 `svg` 纳入 `RouteActivity.kt` 的 `PREVIEW_IMAGE_EXTENSIONS` 预览白名单，点击 SVG 链接原地唤起全屏画廊弹窗手势缩放，不再误跳代码编辑器；
  - **高度缓存（`ImageAspectRatioCache`）安全守卫**：在 `ZoomableAsyncImage.kt` 读取图片尺寸存入缓存前，增加 `if (image.width > 0 && image.height > 0)` 严格检查，杜绝无物理尺寸的 SVG 产生脏比例污染布局；
  - **大图预览弹窗（`ImagePreviewDialog.kt`）防崩保护**：针对 SVG 的 `painter.intrinsicSize` 可能会返回 `Size.Unspecified` 的情况，增加有限性检查与默认尺寸兜底，消除第三方 Pager 在读取非有限尺寸时的异常崩溃。
- **AI 读图工具扩展**（`ReadImageTools.kt`）：
  - `IMAGE_HEADER_BYTES` 调整为 256 字节；
  - 在 `sniffImageExtension` 中补充了对 ICO 魔数（`00 00 01 00`）及 SVG 标记（`<?xml ... <svg` 或 `<svg`）的嗅探支持，允许 AI 通过 `read_image` 回读 SVG/ICO 图标。

### 1.2 工作区超链接与目录直达
- **目录链接直达工作区对应文件夹**：
  - `LocalFileOpener.kt` 放宽 `takeIf { it.isFile }` 为 `takeIf { it.exists() }`，支持目录解析与反查；
  - `WorkspaceFileUrlResolver.kt` 的 `toSandboxPath` 支持将目录路径逆向映射回 `/workspace/...` 或 `/...`（Linux 区）；
  - `Screen.WorkspaceDetail` 路由扩展 `initialPage`、`initialArea`、`initialPath` 参数；
  - `WorkspaceDetailPage.kt` 与 `WorkspaceDetailVM.kt` 接收参数并联动 `navigateTo(...)`，点击目录超链接直接切换至文件列表 Tab 并自动打开目标子目录。
- **对齐工作区文件分类与 MIME 补全**：
  - `RouteActivity.kt` 的 `localFileOpener` 重构：
    - 代码与文本（`.py`, `.ts`, `.md` 及常见的 `Makefile`、`Dockerfile`、无后缀文本等）进入工作区代码编辑器；
    - 未知二进制文件（`.db`, `.zip`, `.pdf`, `.apk` 等）不接管，安全落入系统的外部应用打开。
  - `openMarkdownLink` 中通过 `MimeTypeMap` 计算出具体的 MIME 类型，使用 `setDataAndType(contentUri, mime)` 唤起外部应用。
- **工作区有效性前置防御（彻底杜绝红字）**：
  - 在 `localFileOpener` 中接入 `WorkspaceDAO.listFlow()` 的实时 ID 集合校验；
  - 若遇到还原漂移或已被解绑/删除的旧工作区 ID，绝不跳入页面抛出未捕获的 `Workspace not found` 红字异常，而是原地弹窗友好 Toast 提示（`工作区不存在或已被解绑`）并安全兜底。

### 1.3 切屏防崩溃治理（`TransactionTooLargeException` 智能熔断）
- **根因**：Activity 进入后台 (`activityStopped`) 时，系统会将包含 Navigation 3 历史条目与 Compose 子树的 `savedInstanceState` Bundle 跨进程传递给 AMS。长会话（如 Sydney）中状态累积超过 500KB，突破 Binder 单次事务限制，直接引发系统强杀崩溃。
- **落地**：在 `RouteActivity.onSaveInstanceState` 中实时检测待传 Parcel 大小；若超过 **200KB** 危险阈值则主动调用 `outState.clear()` 并输出警告日志。
- **效果**：日常切屏零感知（保留输入法与临时状态），极端超长会话切屏清空瞬态快照保住进程不闪退，聊天数据与助手配置因本身已在 Room/DataStore 中而 100% 完好。

### 1.4 图片识别上限提升
- 将 `ReadImageTools.kt` 中的 `READ_IMAGE_MAX_DOWNLOAD_BYTES` 从 **8MB 调整至 32MB**，满足高清大图与相机照片的识别需求。

### 1.5 AI 提示词规范化
- 在 `WorkspaceReminderTransformer.kt` 和 `UploadReminderTransformer.kt` 中消除了误导 AI 给非图片文件套用 `![]` 的语法歧义：
  - 图片明确指示使用 `![alt](file://...)`；
  - 普通文件明确指示使用标准超链接 `[filename](file://...)`；
  - 明确加入目录超链接示例：`[dirname](file:///workspace/src)`，告知 AI 点击可直接跳入工作区文件管理器。

### 1.6 版本号升级
- `app/build.gradle.kts`：
  - `versionCode = 1177`
  - `versionName = "3.3.0"`

---

## 2. 上游领先的 74 个 Commit 全景分类审查

上游（`rikkahub/rikkahub`）自分叉点 `2689e753` 以来领先 74 个 commit。经审查分为三类：

### 2.1 坚决阻断 / 与 Fork 严重冲突（15 个，严禁盲目合入）
1. `c8853531` (`fix(quickjs): 迁移到 quickjs-kt`) —— 冲突我们的 Prism.js+QuickJS 高亮引擎。
2. `9a35e3f2`, `51a33198`, `44236d7b` (Haze 升至 2.0.0 正式版) —— 会破坏输入框毛玻璃效果变全透明（需锁死 `alpha03`）。
3. `7508eee9` (HugeIcons 升至 1.5) —— 会导致字形大面积漂移并丢弧线（需锁死 `1.3` + 本地 `ForkIcons`）。
4. `288a034c`, `bc9d2582`, `d6ba728e`, `426ede84` (上游依赖误删与依赖大换血) —— 避免引入上游未经验证的依赖漂移。
5. `3428c60b`, `6adc0cf1`, `7263dd36`, `6c903feb`, `447bb7e8` (上游版本 bump 至 2.5.x) —— 我们已独立演进至 `3.3.0`。
6. `42517943` (`docs: 移除PR相关内容，不再接受PR`) —— 官方策略调整。

### 2.2 高价值 / 需优化移植（16 个，需结合 Fork 架构精细化引入）
1. `ed3569c7`：**工作区文件编辑器不再将全文存入 saved state**（源头防爆，与我们的 200KB 熔断构成双保险）。
2. `2d5c51bd`：**日志页修复复制过大请求体导致剪贴板事务过大闪退**。
3. `00c8d53a`：**新增 `chart_display` 本地工具**（支持在气泡中画折线图、柱状图、散点图，需接入 `ChatToolFactory` 并供子代理继承）。
4. `cf79246b`：**内联展开工具 inputSchema 的 `$ref`**（彻底解决 `$defs` 丢失导致发给模型的 Tool 定义报 400）。
5. `7a53065a`, `4ba5d79f`, `b8e0fec4`：**MCP OAuth 大幅加固**（防 WAF 拦截、支持 Client ID Metadata Document 等）。
6. `282b85f2`：**复制助手时支持同时复制记忆**（需适配我们 13 参扩展记忆模型）。
7. `bd936caa`, `458c16df`, `95fed05e`, `d5f0039b`：**ChatService 标题与序号细节补丁**（必须手工比对移植，严禁破坏异步子代理投递队列）。
8. `2cd62ad2`：**支持 Gemini Interactions API (Beta)**。
9. `654a5e1f`：**提供商新增高级设置 tab，支持自定义请求头**。
10. `eaa003ad`：**新增图片生成桌面快捷方式**。
11. `4a3eefc1`：**跨 session 切换生命周期状态管理**。

### 2.3 干净无损 / 可直接引入（43 个，Zero Overlap 或纯新增）
- **模型与提供商**：`4391d5a5` (Google 过滤 propertyNames), `40426e93`/`94504b5c` (百炼/思考参数修正), `6e98691c` (移除 tool 多余 name), `5ac149b9` (Responses API 容错), `4e4bfa62` (OpenAI 图片尺寸), `3a9ae690` (Gemini 4), `3443dd45` (Claude 5.5), `8e304bb1` (火山引擎 TTS), `10`/`13`/`20`/`27`/`40` (模型预置更新)。
- **工作区与技能**：`21448350` (工作区长按多选批量导出), `f099cd40` (技能页搜索), `3b346748`/`7f17dcc8` (内置预置技能及 skill-creator), `9f02586d` (技能文件防损坏), `41` (MCP 忽略空 header)。
- **交互与功能**：`b2d73a65` (导出对话支持排除思考过程), `b2525e7b` (模型列表按厂商折叠), `7ee13f2a` (输入栏 blur/glass 切换), `b9c0d3b7` (从聊天记录反向恢复助手), `70b382f5` (设置读取失败防默认值覆盖), `324b337b` (复制代码块防行号复制), `14` (裁剪提速), `17`/`32` (收藏夹侧滑与撤销修复), `82c339c1` (ItemActionMenu), `620e38cc` (日志 JSON 选中防崩), `53f224b1` (ExpressivePopup), `db1ce811` (搜索分段列表)。
- **国际化**：`6`, `36`, `38`, `39` 多语言及阿拉伯语支持。

---

## 3. Compact 后的执行路线图

在执行 `/compact` 紧缩上下文后，直接按照以下顺序推进：

1. **第一波：无损直接引入（Cherry-pick 43 个纯净提交）**
   - 新建特性分支 `feat/upstream-sync-batch1`；
   - 批量 cherry-pick 2.3 节中的 43 个独立提交；
   - 本地构建与单测快速验证，确认零破坏。
2. **第二波：优化移植高价值特性（16 个提交）**
   - 重点移植：
     - `chart_display` 图表工具注册进 `ChatToolFactory`；
     - MCP `$ref` 递归展开修复；
     - `WorkspaceFileEditorPage` 的 `rememberTextFieldState` 移除 SavedState；
     - `ChatService` 序号与错误提示微调（保护子代理不变）；
     - 提供商自定义请求头（`customHeaders`）；
     - 复制助手连带记忆克隆。
3. **验证与打包**：
   - 运行本地全量测试：`./gradlew testDebugUnitTest`；
   - 验证无误后合回 `master` 并触发 CI，生成包含最新上游特性的 `3.3.0` 发布包。
