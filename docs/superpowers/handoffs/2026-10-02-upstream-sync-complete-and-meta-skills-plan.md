# 上游同步战役胜利收官与环境/技能工坊 Meta-Skills 规划交接文档

> **接手必读**：本会话已圆满完成上游领先的全部 74 个 commit 的全量审查与第一波、第二波移植（共 57 个提交入库，坚决阻断 15 个破坏性提交），完成了本地全模块零错误编译并已推送到 。
> **下一阶段恢复后的首要任务是落地两大内置 Meta-Skill：① `environment-setup`（国内清华源安全三步法与 PEP 668 default 虚拟环境）；② `skill-creator` 工业级改造（沙箱仿真评测与自包含 HTML 报告）。**

---

## 1. 本会话已完成工作（上游 74 个 Commit 全量消化与入库）

### 1.1 同步战役总览
- 分支与合并：创建 `sync/upstream-20261002`，历经 57 个提交的移植、冲突解决与架构适配，已 fast-forward 合并回 `master` 并成功推送到 `origin/master`；
- 坚决阻断 15 个破坏性提交（Haze 2.0.0 升版、HugeIcons 1.5 升版、quickjs-kt 换原生高亮引擎、版本号回退 2.5 等），13 条承重约束 100% 完好。

### 1.2 核心特性与架构加固详情
1. **交互图表工具（`chart_display`）**：
   - 移植全套 Canvas 折线图、柱状图、散点图自绘组件（`ChartCard`, `ChartPlot`, `ChartScale`, `ChartSpec`, `ChartTable`）；
   - 接入 `LocalTools.kt`，不仅支持主会话调用，还自动被 `ChatToolFactory` 的 `createSubAgentContext` 继承，供子代理绘图使用；
   - 气泡消息（`ChatMessage.kt`）、思考链（`ChatMessageCot.kt`）及图片导出（`ConversationExport.kt`）全链路支持图表渲染。
2. **MCP 生态与安全加固**：
   - 移植 `cf79246b`：内联展开 inputSchema 的 `$ref`，彻底解决 `$defs` 丢失导致发给模型报 400 Bad Request；
   - 移植 OAuth 三大提交（`7a53065a`, `4ba5d79f`, `b8e0fec4`）：回调改用 localhost 避开 WAF 403、缺失元数据时回退 origin、支持 Client ID Metadata Document。
   - 依赖切换为 rikkahub fork MCP SDK 0.15.0-rikka.2。
3. **大文件防爆与事务超限防御**：
   - 移植 `ed3569c7`：工作区文件编辑器从源头移除 `rememberTextFieldState` 的 savedState，与我们先前的 200KB `onSaveInstanceState` 智能熔断构成切后台双保险；
   - 移植 `2d5c51bd`：请求日志复制过大请求体时捕获 Binder 事务异常，防止剪贴板闪退。
4. **模型支持与提供商高级选项**：
   - 提供商高级选项中增加 `customHeaders` 自定义请求头，请求时携带并可被助手覆盖；
   - 模型预置注册 Gemini 4 (thinkingLevel) 与 Claude 5.5 Opus/Sonnet；支持 Google Gemini Interactions API 协议；
   - 火山引擎 TTS 注册支持；Minimax 区域与默认值更新；
   - 快速模型缺失时主动报友好错误卡片。
5. **助手与工作区交互演进**：
   - 复制助手时支持勾选“同时复制记忆”，并针对我们 fork 的 13 参扩展记忆模型做到了全字段安全深拷贝；
   - 工作区长按多选批量导出（单次目录授权 + MIME 嗅探 + 异常中断自动回滚）；
   - 导出对话支持排除思维过程（同时完美兼容工作区图片预加载与沙箱路径逆解析）；
   - 快捷方式支持按 `${applicationId}` 隔离，新增图片生成桌面快捷方式；
   - 引入标准 `ItemActionMenu` 与 `longPressReorder` 交互基础设施。
6. **内置技能系统基础设施**：
   - 随包 assets 释放内置技能，安装/更新时间戳自动比对并原子解压；
   - 嗅探 `#!` shebang 自动恢复 Linux 执行位；
   - 统一收拢到 `FileFolders.ROOTFS_BIND_MOUNTS`，同时贯穿 PRoot `-b`、终端挂载与文件路径反查三处。

---

## 2. 下一阶段：两大 Meta-Skills 实施计划

### 2.1 Meta-Skill 1：`environment-setup`（工作区环境分析与配置）
- **定位**：解决国内用户 Android PRoot 容器网络受限、CA 证书死锁及 Ubuntu 24.04 PEP 668 限制的自动化运维技能。
- **目录**：`app/src/main/assets/builtin_skills/environment-setup/SKILL.md`
- **核心逻辑**：
  1. **意图优先 + 锁定清华源（TUNA）**：
     - 用户提及“换源/加速/更新慢/装不上包”或系统时区为中国，直接锁定清华大学开源镜像站，**绝不做容易造成误判与卡顿的海外官方源探测**；
     - 全面覆盖：Ubuntu arm64/x86_64、Debian、Alpine 及 PyPI 清华源。
  2. **CA 证书三步安全切换法**：
     - Step 1: 写入 `http://mirrors.tuna.tsinghua.edu.cn`（明文 HTTP 避开无证书报错）；
     - Step 2: 执行包管理器更新并安装 `ca-certificates` 与 `curl`；
     - Step 3: 平滑改写为安全的 `https://mirrors.tuna.tsinghua.edu.cn`；
     - 适配兼顾传统 `/etc/apt/sources.list` 与 Ubuntu 24.04 DEB822 `/etc/apt/sources.list.d/ubuntu.sources`。
  3. **破解 PEP 668 限制（Default 虚拟环境机制）**：
     - 安装 `python3-venv`，在工作区创建 `/workspace/.venv/default`；
     - 在用户沙箱 `~/.bashrc` 中配置自动激活脚本；
     - 配置 pip 默认使用清华镜像源；
     - 实现用户或 AI 进入终端即可直接无痛运行 `pip install`。
  4. **环境自检与状态卡片**：
     - 输出当前 Python/Pip 版本、源地址及磁盘状态，供用户与 AI 随时审查。

### 2.2 Meta-Skill 2：`skill-creator`（工业级自测与评估工坊）
- **定位**：将 Claude Code 官方 33KB 的测试驱动与数据驱动方法论，深度适配为 RikkaHub 移动沙箱闭环。
- **目录**：`app/src/main/assets/builtin_skills/skill-creator/`
- **核心改造点**：
  1. **沙箱与真实上下文仿真（解决测试失真）**：
     - 本地环回接口注入当前 Assistant 真实 System Prompt、Transformers 管道及 `use_skill` 工具定义，保证评测命中率与真实会话 100% 对齐。
  2. **纯 Python 标准库评测脚本（零第三方依赖）**：
     - 彻底剔除 `pyyaml`，改用标准库正则与字符串解析提取 YAML Frontmatter；
     - 剔除 `requests`，改用 `urllib.request`；
     - 彻底解耦桌面端 `claude` CLI 依赖。
  3. **分级评测模式（Fast by Default）**：
     - 默认运行 3~5 个代表性正用例 + 2 个负样本用例，30 秒内收敛，严格控制端侧 API 开销与时间；
     - 仅在用户明确要求“深度基准测试”时才放开全量方差分析。
  4. **Staging 草稿隔离与自包含 HTML 报告直达**：
     - 中间评测与草稿限制在 `/workspace/skills-draft/<name>/`，校验通过才 Promote 到 `/skills/<name>/`；
     - 生成单文件 `/workspace/evals/viewer.html`，结合 RikkaHub 已有能力的 `file://` 链接，在 App 内实现全屏 WebView 一键弹窗查看交互评测看板。

---

## 3. 当前代码与环境状态
- **代码状态**：`HEAD == origin/master`，工作树完全干净；
- **编译状态**：本地 `./gradlew compileDebugKotlin` 全模块 0 错误通过；
- **下一步**：请在会话中输入 `/compact` 紧缩上下文；紧缩后直接开始实施第一部分 `environment-setup` 与第二部分 `skill-creator` 改造！
