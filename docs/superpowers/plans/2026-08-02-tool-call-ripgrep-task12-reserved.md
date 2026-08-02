# Task 12（预留）：`workspace_grep` 接入原生 ripgrep + Claude Code 参数集

**状态**：🔒 预留（RESERVED）——实现**待原生 ripgrep artifact 交付后**进行。当前不执行。
**日期**：2026-08-02
**前置依赖**：`docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`（新 AI 代理搭建预编译 artifact 流水线）。artifact 交付后本任务才可开工。

---

## 目标

1. RikkaHub 的 `workspace_grep` 底层从 JVM `Regex`（回溯引擎）换为**真 ripgrep**（Rust `grep-regex` 线性内核，经 JNI），根治灾难性回溯 DoS。
2. 复用 Claude Code Grep 工具的**参数列表**（`pattern/path/glob/output_mode/-A/-B/-C/context/-i/-n/-o/multiline/head_limit/offset/type`）。
3. `workspace_glob` 加 **mtime 排序**（最近修改在前）。

## 交接文档已定的契约（照此实现）

- **JNI 函数**：`NativeRipgrep.searchJson(path, patterns[], filePattern, caseInsensitive, literal, contextLines, maxResults): String`，返回 camelCase JSON `{success, error, filesSearched, blocks:[{filePath, firstMatchLine, lineContent, matchContext, matchCount}]}`（详见交接文档 §4）。
- **降级**：wrapper 提供 `isAvailable`；`.so` 缺失/加载失败时回退 RikkaHub 现有 JVM `WorkspaceFileSystem.grep`。
- **AAR 内置 Kotlin wrapper**：RikkaHub 只 `implementation(<artifact>)`，不碰 `System.loadLibrary`。

## 本任务内容（artifact 交付后按此做）

### 1. `workspace_grep` 换 native
`app/.../data/ai/tools/WorkspaceTools.kt` `createGrepTool.execute` 改为：
- 若 `NativeRipgrep.isAvailable` → 调 `searchJson(...)`，把 `blocks` 映射回现有信封 `{type:"workspace_grep", pattern, matches:[{path,line,text}]}`（`block.filePath`→path、`firstMatchLine`→line、`lineContent`→text）。
- 否则回退 JVM `workspaceRepository.grep(...)`（现有实现保留）。

### 2. 加 Claude Code Grep 参数集（schema + 映射）
参数对齐交接文档 §7 分工：
- **native**：`pattern`（必填）、`path`、`glob`（→ filePattern）、`-i`（caseInsensitive）、`regex`/`literal`、`multiline`、`-o`、`-A`/`-B`/`context`（→ contextLines）。
- **Kotlin 后处理**：`output_mode`（content/files/count，默认 files）、`head_limit`/`offset`（分页）、`type`（扩展名过滤）。
- 结果信封保持 camelCase（`{type, pattern, matches}`）；`-n` 行号恒有。

### 3. `workspace_glob` mtime 排序
`WorkspaceFileSystem.glob` 结果按 `File.lastModified()` 降序（最近在前），配现有 `maxListEntries` 上限取最近 N 个。

### 4. 验证
- CI 编译（nightly-build-debug）。
- 真机/模拟器：native 可用时搜中文/正则/大小写/context；`.so` 缺失场景走 JVM 回退不崩。
- 性能对比：大目录下 native vs JVM。

## 风险 / 注意
- JNI 函数名与 Kotlin wrapper 包名强绑定（`me.rerere.rikkahub.native.ripgrep`），artifact 交付后**先验证 loadLibrary 通**再接线。
- rg 语义（Unicode 默认开、gitignore/隐藏文件跳过）由 native `ignore` crate 提供；RE2J 方案已放弃。
- 此任务在 Plan 1 范围（workspace 工具），但与展示层（Plan 2）无依赖，可独立排期。

## 完成条件
- `workspace_grep` 默认走 native，`workspace_glob` 按 mtime 排序，Claude Code 参数集可用，JVM 回退兜底，CI 绿。
