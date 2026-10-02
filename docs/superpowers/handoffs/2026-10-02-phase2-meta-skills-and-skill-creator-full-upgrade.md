# 第二阶段战役：内置 Meta-Skills 与工业级 Skill-Creator 完整工坊及模型回环桥接全量收官文档

> **归档与交接**：本文档记录第一阶段全量攻坚成果、第二阶段对标 Claude Code 官方 33KB 体系全面落地，以及**沙箱免密直通模型本地回环桥（Workspace LLM Loopback Proxy Bridge）**的平台级架构实施与真实真机大模型 Function Calling 全链路实测回执。

---

## 1. 战役全景与核心架构成果

| 模块 | 核心路径 | 核心能力与工程突破 |
|---|---|---|
| **Meta-Skill 1<br>`environment-setup`** | `app/src/main/assets/builtin_skills/environment-setup/` | **三步安全切换法**：HTTP 换源 ➔ 安装 `ca-certificates curl` ➔ 升级加密 HTTPS 清华源，彻底破除无根证书 TLS 死锁；arm64 `ubuntu-ports` 架构感知；Ubuntu 24.04 DEB822 适配；PEP 668 default 虚拟环境创建与 `~/.bashrc` 自动静默激活。 |
| **Markdown 复选框真机渲染** | `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt` | 彻底根除圆点冗余；AST 节点严格隔离防错位；`Row(Top) + Box(weight(1f))` 紧凑并排，首行绝对不换行，长文本整齐折行；MD3 主题自适应只读复选框（`Tick01`）。 |
| **Meta-Skill 系统保护** | `app/src/main/java/me/rerere/rikkahub/data/files/BuiltinSkills.kt`<br>`SkillManager.kt` | 系统元技能（`skill-creator`, `environment-setup`）受绝对保护，同名用户技能自动重映射为 `<name>-custom` 变体共存，物理路径精确寻址，杜绝官方基础设施被覆盖抹杀。 |
| **自愈优化引擎** | `skill-creator/scripts/improve_description.py` | 纯 Python 3 标准库实现。自动解析评测失败样本，聚类漏触发缺失词与误触发扩散风险；提供 **LLM 语义优化** 与 **启发式双语规则优化** 双轨引擎，严格受控于 1024 字符限制，支持 `--write` 安全写回 `SKILL.md`。 |
| **自动收敛循环** | `skill-creator/scripts/run_loop.py` | 自动化串联评测与自愈优化。**分层抽样切分 Train / Holdout 测试集（防过拟合）**，迭代监控通过率变化，直到达成目标通过率（如 100%）提前收敛并在 Holdout 集上完成终验，导出 `iteration_history.json` 与全彩交互式 HTML 看板。 |
| **打包分发工具** | `skill-creator/scripts/package_skill.py` | 纯标准库一键压制合规 `.zip`。前置静态 Lint 门禁拦截，智能剔除 `.git/`、`__pycache__/`、临时草稿与评测日志；输出文件清单、压缩比（>65%）与 RikkaHub **Settings → Agent Skills → Import from file** 导入指引。 |
| **评测引擎三大硬伤根治** | `skill-creator/scripts/run_eval.py`<br>`generate_report.py` | 1. **诚实透明**：无 API 离线模式明确标明 `model: offline-simulated-heuristics` 并弹出醒目警告横幅，绝不冒充 `gpt-4o-mini`；<br>2. **多语言双语分词**：纯标准库实现，采用停用词边界切分与自然短语提取，攻克中文用例全报 `Insufficient keyword overlap` 的顽疾；<br>3. **切断排除段污染与病句**：严格隔离负向排除段落（`不适用于...`），从正向职能段提取整句短语，杜绝“我想进行下时”等病句。 |
| **本地模型回环网关 (方案 B)** | `app/src/main/java/me/rerere/rikkahub/data/ai/bridge/`<br>`ProotShellRunner.kt` | 在 Android 宿主端监听 `127.0.0.1:28888`，暴露标准 OpenAI 兼容的 `POST /v1/chat/completions` 与 `GET /v1/models`；双向转换 OpenAI 与 RikkaHub 内部 `UIMessage` / `Tool`；启动沙箱时自动向环境变量注入 `LLM_API_BASE`、`LLM_MODEL` 和 `LLM_REASONING_EFFORT`；无需向沙箱泄露用户密钥即可直接调用当前会话真实模型！ |
| **系统级 Reasoning 缺陷修复** | `ai/src/main/java/me/rerere/ai/core/Reasoning.kt`<br>`ChatCompletionsAPI.kt` | 将 `ReasoningLevel.OFF.effort` 从非法的 `"none"` 规范化为标准的 `"off"`，在 `AUTO` 时避免发送无效字段，彻底根除所有 OpenAI 兼容反代（NewAPI、OneAPI 等）上报 `Validation error: Invalid option at params.reasoning_effort` 400 错误。 |

---

## 2. 真实设备核验回执（Android 14 模拟器 PRoot 沙箱端到端）

通过 `adb` 调度 Android 模拟器内的真实 PRoot Linux 容器，实测连接本地回环网关，使用用户当前配置的真实大模型（`deepseek/deepseek-v4.1-flash`）执行端到端评测：

### 2.1 探测响应回执
- `/health` ➔ `OK (active_model=deepseek/deepseek-v4.1-flash, effort=auto)`；
- `/v1/models` ➔ `{"object":"list","data":[{"id":"deepseek/deepseek-v4.1-flash","object":"model","created":1700000000,"owned_by":"rikkahub"}]}`。

### 2.2 真实模型评测执行输出（`eval_results.json` 真实提取）
```json
{
  "skill_name": "daily-schedule",
  "description": "管理用户的每日日程安排、会议提醒和待办任务。不适用于算法分析。",
  "model": "deepseek/deepseek-v4.1-flash",
  "evaluation_mode": "Live LLM API",
  "is_simulated": false,
  "api_error_count": 0,
  "warning": null,
  "summary": {
    "total": 2,
    "passed": 2,
    "failed": 0,
    "pass_rate": 100.0,
    "is_simulated": false
  },
  "results": [
    {
      "query": "帮我看一下今天下午有什么日程安排",
      "should_trigger": true,
      "trigger_rate": 1.0,
      "triggers": 1,
      "runs": 1,
      "pass": true,
      "passed": true,
      "reason": "use_skill invoked for daily-schedule",
      "mode": "live-llm"
    },
    {
      "query": "法国的首都是巴黎吗？",
      "should_trigger": false,
      "trigger_rate": 0.0,
      "triggers": 0,
      "runs": 1,
      "pass": true,
      "passed": true,
      "reason": "Model responded without invoking use_skill",
      "mode": "live-llm"
    }
  ]
}
```
- **实测验证**：
  1. 真实大模型（`deepseek/deepseek-v4.1-flash`）被沙箱 Python 脚本免密调用；
  2. 正例真实触发大模型在服务端下发 `use_skill` 工具调用并被准确捕获；
  3. 负例大模型自然语言回复不调工具；
  4. 400 / 500 报错彻底根除，评测模式实打实标明为 `Live LLM API`，`is_simulated: false`。

---

## 3. 全仓门禁与承重锁死验证

1. **`WorkspaceShellContextTest`（字段锁死断言）**：
   - 严格 **PASS**（未改动任何 data class 字段，字段集合 100% 保持锁死一致）；
2. **Kotlin 单元测试**：
   - `:workspace:testDebugUnitTest` ➔ **BUILD SUCCESSFUL**；
   - `:app:testDebugUnitTest`（含 `BuiltinSkillMergeTest`, `MarkdownTaskListTest`） ➔ **BUILD SUCCESSFUL**；
3. **仓库静态安全审计**：
   - `hugeicons_glyph_audit.py` ➔ **PASS（149 图标无缺陷）**；
   - `baseline_profile_audit.py --strict` ➔ **PASS（无新增过期项）**；
   - `prefs_key_audit.py` ➔ **PASS**；
   - `run_eval.py --mode lint` ➔ **PASS（0 错误）**；
4. **编译与打包**：
   - `./gradlew assembleDebug -PwithX86_64` 编译通过，安装至模拟器运行完全稳定。

---

## 4. 恢复指示与下一步建议

- **当前分支状态**：所有代码修改与新特性已在本地工作区完成调试与全量真机验证；
- **Compact 恢复指示**：
  - 恢复后可直接输入 `git add` 并提交本地 commit，标记第二阶段战役圆满闭环；
  - 后续可开启下一阶段规划（如：其他扩展技能制作、UI 细节打磨或上游最新变更跟踪）。

---

## 5. 还原工作区链接点击报“工作区不存在”黄色错误根治记录

### 5.1 缺陷现象
用户从 zip 备份导入/还原了一个工作区并绑定到助手使用后，AI 在对话中发送指向该工作区文件的链接（`file:///workspace/...`），点击该文件或目录链接时，弹出黄色警告 Toast：
> **“工作区不存在或已被解绑”**

### 5.2 深入排查与根本原因
1. **新建 vs 还原工作区的实体差异**：
   - 普通新建工作区（`createWorkspace`）：`id = Uuid.random().toString()`, `root = id`。物理磁盘目录名（`root`）与逻辑主键（`id`）完全相同；
   - 导入工作区（`importWorkspace`）：历史代码中写为 `id = Uuid.random().toString()`, `root = Uuid.random().toString()`，分别生成了两个不同的随机 UUID（`id = AAAA`, `root = BBBB`）！解压目录位于 `files/workspaces/BBBB/`，而数据库记录的 id 是 `AAAA`；
2. **反查逻辑与有效性校验脱节**：
   - 当点击 Markdown 行内文件链接时，`LocalFileOpener.resolveLocalFile` 遍历磁盘目录反查，在 `files/workspaces/BBBB` 下找到了文件，于是把磁盘目录名 `BBBB` 作为工作区 ID 返回给 `RouteActivity`；
   - `RouteActivity` 校验 `workspaceId !in validWorkspaceIds`（只收集了 `workspaces.map { it.id }`，即 `AAAA`）；
   - 由于 `"BBBB" !in {"AAAA"}`，判定失效，直接弹出黄色 Toast `toastState.show("工作区不存在或已被解绑", type = ToastType.Warning)`！

### 5.3 彻底修复方案
1. **`RouteActivity.kt` 引入双向寻址映射**：
   - 构建 `workspaceRootToId`（同时收录 `it.id -> it.id` 与 `it.root -> it.id`）；
   - 无论反查返回的是物理磁盘目录名 `root` 还是逻辑 `id`，均能精准映射回正确的有效逻辑 `workspace.id`，彻底修复历史已还原工作区的点击报错；
2. **`WorkspaceRepository.importWorkspace` 规范对齐**：
   - 导入新工作区时保证 `root = id`，从源头杜绝新的 `root` 与 `id` 割裂；
3. **`Markdown.kt` 会话上下文直通**：
   - `MarkdownBlock` 通过 CompositionLocal 透传当前会话的 `workspaceId`，优先直达对应工作区，失败自动降级磁盘反查；
4. **单测保障**：
   - 新增 `WorkspaceRestoreLinkTest`，全量验证双向映射解析。
