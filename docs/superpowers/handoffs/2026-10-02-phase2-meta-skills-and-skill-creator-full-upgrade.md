# 第二阶段战役：内置 Meta-Skills 与工业级 Skill-Creator 完整工坊交接文档

> **归档与交接**：本文档记录第一阶段全量攻坚落地成果（`environment-setup` 全套流水线、Markdown 复选框真机级渲染重构、内置 Meta-Skill 防覆盖安全机制），并制定**第二阶段全面补齐 Claude Code 官方 33KB+ `skill-creator` 工业级工坊**的详细落地技术规约。

---

## 1. 第一阶段完工成果与验证记录

### 1.1 `environment-setup` Meta-Skill（工作区环境分析与配置）
- **路径**：`app/src/main/assets/builtin_skills/environment-setup/`
- **核心能力**：
  - **三步安全切换法（破除无 CA 证书死锁）**：
    1. HTTP 清华源配置（绕过 TLS 校验）；
    2. 更新并安装 `ca-certificates curl`，建立受信根证书库；
    3. 平滑升级为加密 HTTPS 清华源；
  - **架构与格式感知**：
    - 自动识别 arm64 手机端，锁定 `ubuntu-ports` 路径（彻底避免 404）；
    - 兼容 Ubuntu 24.04 (Noble) DEB822 格式（`/etc/apt/sources.list.d/ubuntu.sources`）与传统 `sources.list`，兼容 Alpine 和 Debian；
  - **Ubuntu 24.04 PEP 668 破解（Default Venv 机制）**：
    - 创建 `/workspace/.venv/default`；
    - 配置 pip 清华源；
    - 在 `~/.bashrc` 和 `/etc/profile.d/` 注入幂等静默激活，使终端与 AI `workspace_shell` 均可**无痛直接运行 `pip install`**；
  - 包含一键脚本 `setup_tuna.sh`、`setup_venv.sh`、`diagnose.sh` 及深度原理文档。

### 1.2 Markdown 复选框真机级渲染重构（`- [ ]` / `- [x]`）
- **路径**：`app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- **重构要点**：
  - **根除圆点冗余**：Task List 项不再渲染 `Text(bulletText)`，由复选框直接承担列表符号；
  - **节点过滤与隔离**：`separateContentAndLists` 彻底剔除 `LIST_BULLET` 与 `CHECK_BOX`，防止其混入正文内容流产生二次渲染和空格错位；
  - **紧凑并排与长文本悬挂缩进**：采用 `Row(Alignment.Top)` + `Box(Modifier.weight(1f))`，左侧为复选框（带 3dp 微调居中），右侧正文紧跟其后，**首行绝不换行**，长文本整齐折行；
  - **优雅视觉规范（与用户参考图一致）**：
    - 未选中 `[ ]`：`16.dp` 见方，`RoundedCornerShape(4.dp)`，`1.5.dp` 的 `outline` 细线框，内部透明；
    - 选中 `[x]` / `[X]`：品牌主色 `primary` 实体填充，居中显示白色（`onPrimary`）对勾（`HugeIcons.Tick01`）；
    - 只读性：去除任何点击交互，严格遵循只读呈现规范；
  - **真机实测**：安装至 Android 14 模拟器，用户消息与 AI 回复消息双向实测呈现完美排版。

### 1.3 内置 Meta-Skill 防覆盖安全机制
- **路径**：
  - `app/src/main/java/me/rerere/rikkahub/data/files/BuiltinSkills.kt`
  - `app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt`
  - `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt`
- **问题根因**：原上游 `mergeWithBuiltinSkills` 采取“同名时直接隐藏内置技能”策略，导致用户本地若有同名技能，系统 Meta-Skill 被彻底抹杀，造成基础设施瘫痪；
- **解决机制**：
  - 定义 `PROTECTED_META_SKILLS = setOf("skill-creator", "environment-setup")`；
  - 内置 Meta-Skill 享有最高优先级，**永远作为权威官方版本保留**；
  - 用户本地同名技能自动重映射为 `<name>-custom` 变体，两者在列表共存，互不干扰；
  - `SkillManager.resolveSkillDir` 优先从 `findSkill(name)?.skillDir` 取物理路径，确保重命名后的变体能正确读写本地文件；
  - `deleteSkill` 增加内置技能只读保护。

### 1.4 全仓门禁与测试
- `hugeicons_glyph_audit.py`：PASS；
- `baseline_profile_audit.py --strict`：PASS（新增规则已入库白名单）；
- `prefs_key_audit.py`：PASS；
- `run_eval.py --mode lint`：`environment-setup` 与 `skill-creator` 均通过静态合规门禁；
- `BuiltinSkillMergeTest`：单元测试绿灯（覆盖 Meta-Skill 保护、-custom 变体生成、普通技能覆盖三条分支）；
- `MarkdownTaskListTest`：单测绿灯（覆盖 AST 状态提取与正则解析）；
- `./gradlew compileDebugKotlin`：全模块 0 错误编译通过；
- 真机安装：`assembleDebug -PwithX86_64` 成功安装并运行于 Android 14 模拟器。

---

## 2. 第二阶段规划：`skill-creator` 完整工业级工坊重构

### 2.1 与 Claude Code 官方的代际差及补齐策略
官方 33KB 插件核心由 8 个脚本与 3 个评测规约构成，我们通过**“去 CLI 化、纯标准库重构、高保真适配 RikkaHub 运行环境”**原则全面补齐：

1. **自愈优化引擎：`scripts/improve_description.py`**
   - **机制**：当 `run_eval.py` 测出未触发或误触发的 query 样本时，自动聚类失败原因；
   - **算法**：构建优化 Prompt，带入当前 description、正用例漏触发列表、负用例误触发列表，调用本地 API/回环端点生成高精度、关键词丰富且严格 <= 1024 字符的新版 description。
2. **自动化评估迭代循环：`scripts/run_loop.py`**
   - **机制**：串联 `run_eval.py` 与 `improve_description.py`；
   - **防过拟合**：实现 Train / Test 划分与 Holdout 机制，对测试集进行分层打散，防止 description 过拟合到特定 query；
   - **收敛控制**：支持设置最大迭代轮数（如 3 轮）与触发阈值（如 80%），收敛后自动将最优 description 写回 `SKILL.md`。
3. **打包分发工具：`scripts/package_skill.py`**
   - **机制**：纯 Python 标准库一键将当前技能打包为合规的 `.zip` 文件，自动剔除 staging 临时文件与无用目录，支持导出到 `/workspace/exports/` 方便用户分享或跨设备导入。
4. **方法论全面升级：`SKILL.md`（33KB 对标版）**
   - 引入“意图访谈框架（Capture Intent）”；
   - 引入“渐进式三层披露模型（Progressive Disclosure）”；
   - 引入“盲测对比（A/B Testing）与质量打分（Grader）”指引；
   - 详细说明 `run_loop.py`、`improve_description.py`、`package_skill.py` 的用法。

---

## 3. 当前代码树与恢复指示

- **当前分支**：`master`（代码状态干净，准备提交）；
- **恢复后第一步**：执行第二阶段实施，依次编写 `improve_description.py`、`run_loop.py`、`package_skill.py` 并升级 `skill-creator/SKILL.md`！
