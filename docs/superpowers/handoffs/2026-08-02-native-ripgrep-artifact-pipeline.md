# 交接文档：RikkaHub 原生 ripgrep 预编译 artifact 流水线

**日期**：2026-08-02
**目的**：为 RikkaHub 的 `workspace_grep` 提供线性时间的真 ripgrep 内核。你的任务是**新建一个独立的 Android JNI 库工程**（Rust 源码 + 交叉编译流水线），把编译好的 AAR 发布到 Maven 仓库，让 RikkaHub 通过 `implementation(...)` 依赖。这是"自维护预编译 artifact"路径（仿照 RikkaHub 现有的 ratex 库）。

---

## 0. 一句话概况

把仓库里现成的 Rust ripgrep JNI 参考（`references/Operit/tools/native_ripgrep/`）移植成独立的 Android AAR，通过 GitHub Actions 交叉编译 4 个 ABI 的 JNI `.so`，发布到 Maven（JitPack 优先），最终让 RikkaHub 的 `workspace_grep` 用上真 ripgrep 内核（线性时间正则，根治 JVM `Regex` 的灾难性回溯）。

---

## 1. 背景与为什么做

- RikkaHub 的 `workspace_grep` 工具（`app/.../data/ai/tools/WorkspaceTools.kt` 的 `createGrepTool`）当前底层是 JVM `Regex`（`workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt` 的 `grep`）。
- **问题**：JVM `Regex` 是回溯引擎，`(a+)+$` 这类 pattern 可指数级回溯 → 工作区搜索被卡死（DoS）。
- **决策（用户已定）**：用**真 ripgrep**（Rust `grep-regex` 线性内核），而非纯 Java 的 RE2J，也不搞 in-repo Rust 构建（RikkaHub 无此先例）。
- **路径**：自维护预编译 artifact——独立工程编译 .so，发布成 Maven 依赖，RikkaHub 侧零 Rust 构建负担。仿照 RikkaHub 对 `io.github.darriousliu:ratex`（Rust JNI LaTeX）的消费方式。

---

## 2. 参考源码（起点，完整可用）

```
references/Operit/tools/native_ripgrep/
├── Cargo.toml
├── Cargo.lock
└── src/lib.rs      ← 完整 JNI 实现（约 350 行，可直接移植）
```

**Cargo.toml 依赖**（全部是 ripgrep 内核/线性时间库）：
```toml
[dependencies]
globset = "0.4"        # glob 匹配
grep-matcher = "0.1"   # 匹配器抽象
grep-regex = "0.1"     # 线性时间正则（rg 内核）
ignore = "0.4"         # 目录遍历 + .gitignore/隐藏文件（rg 语义）
jni = "0.21"           # JNI
serde = { version = "1", features = ["derive"] }
serde_json = "1"
```

**lib.rs 已实现的能力**（直接复用）：
- 多 pattern 数组、case_insensitive、文件 glob 过滤、context 行、max_results 上限。
- `ignore::WalkBuilder`（git_ignore/git_global/git_exclude/parents 全开）——**rg 的 .gitignore/隐藏文件遍历语义**。
- NUL 字节启发式跳二进制（前 8KB 含 `\0` 即跳过）。
- 返回 serde_json（camelCase）。
- **线性时间正则**：`grep-regex::RegexMatcher`（Rust regex 自动机引擎，无回溯）。

---

## 3. 交付物

1. **独立 repo**（建议 `DevLintMar/rikkahub-ripgrep-jni`，你确认）：
   - `Cargo.toml` + `src/lib.rs`（移植自参考，改 JNI 包名）
   - `.cargo/config.toml`（4 个 Android target 的 NDK linker 配置）
   - `android/` 子工程或 Gradle：把 4 个 ABI 的 `.so` 打包进 AAR + Kotlin wrapper class
   - `.github/workflows/`：交叉编译 + 发布流水线
2. **AAR 发布到 Maven**（见 §5），版本化。
3. **RikkaHub 侧**加 `implementation(<artifact>)`，`workspace_grep` 换用 native（这部分属于 RikkaHub 的 Task 12，**不是你的职责**，但你交付的 API 要按 §4/§6 设计好）。

---

## 4. JNI 接口契约（必须对齐）

参考函数（`lib.rs:49`）：
```rust
#[no_mangle]
pub extern "system" fn Java_com_ai_assistance_operit_util_ripgrep_NativeRipgrep_searchJson(
    env: JNIEnv, _class: JClass,
    path: JString,              // 搜索根目录
    patterns: JObjectArray,     // pattern 数组
    file_pattern: JString,      // 文件 glob 过滤（空串=不过滤）
    case_insensitive: jboolean,
    _literal: jboolean,         // ⚠️ 参考里未使用，保留占位
    context_lines: jint,
    max_results: jint,
) -> jstring                   // 返回 serde_json 字符串
```

**必须做的改动**：JNI 函数名嵌入包名。RikkaHub 侧 wrapper 建议包 `me.rerere.rikkahub.native.ripgrep` → JNI 名改为 `Java_me_rerere_rikkahub_native_ripgrep_NativeRipgrep_searchJson`。**包名与 Kotlin wrapper 的 package 必须一致**，否则 `UnsatisfiedLinkError`。

**返回 JSON**（serde camelCase，`lib.rs:15-32`）：
```json
{
  "success": true,
  "error": "",
  "filesSearched": 12,
  "blocks": [
    { "filePath": "src/main.kt", "firstMatchLine": 3, "lineContent": "...",
      "matchContext": "...", "matchCount": 2 }
  ]
}
```
- `success=false` 时 `error` 带原因（如 `invalid regex ...`、`path is required`）。
- `blocks` 按文件聚合；`lineContent` 单匹配截 300 字符 / 多匹配摘要；`matchContext` 含 context 行、截 4000 字符。

---

## 5. 构建与发布（你自选，给出权衡）

**构建**：GitHub Actions 流水线——
1. 安装 Rust + `rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`
2. 安装 Android NDK（workflow 里 `android-actions/setup-android` 或手动下载，设 `ANDROID_NDK_HOME`）
3. `.cargo/config.toml` 写 4 个 target 的 linker（NDK 的 `*-clang`）
4. `cargo build --release --target <abi>` × 4
5. `llvm-strip` 每个 `.so`（去 debug symbols，约 2-4MB/ABI → strip 后更小）
6. 打包 AAR：`.so` 进 `jniLibs/<abi>/` + Kotlin wrapper 源码 + 必要的 `System.loadLibrary`
7. 发布到 Maven

**发布目标**（三选一，权衡）：
| 方案 | 优点 | 缺点 |
|---|---|---|
| **JitPack（推荐）** | push 即自动构建、按 tag/commit 自动版本、RikkaHub 加 `jitpack` repo 一行依赖 | JitPack 沙箱需自装 NDK+cargo targets（`jitpack.yml` 的 `before_install` 里装）；首次构建慢 |
| **GitHub Packages** | 自己有完整 workflow 控制 | RikkaHub 要配 GitHub Packages 的 Maven repo + token（public repo 免 token）；多一步发布 |
| **Maven Central** | 最标准 | 要 Sonatype 账号 + GPG 签名，流程最重 |

---

## 6. RikkaHub 侧消费方式（你的 API 要支持这样）

RikkaHub 希望像用 ratex 一样**零 JNI 接触**——AAR 里带上 Kotlin wrapper：

```kotlin
// AAR 内置（你写进 artifact 的 Kotlin 类，包 me.rerere.rikkahub.native.ripgrep）
object NativeRipgrep {
    init { System.loadLibrary("rikkahub_ripgrep") }   // .so 名与 Rust lib name 一致

    external fun searchJson(
        path: String,
        patterns: Array<String>,
        filePattern: String,
        caseInsensitive: Boolean,
        literal: Boolean,
        contextLines: Int,
        maxResults: Int,
    ): String
}
```

- **必须提供优雅降级**：`System.loadLibrary` 失败（.so 缺失/ABI 不匹配）时抛出的 `UnsatisfiedLinkError` 要能被 RikkaHub 捕获并回退到 JVM 实现（RikkaHub 现有 `WorkspaceFileSystem.grep` 保留作兜底）。wrapper 可用 `runCatching { loadLibrary }` 包住 init，暴露 `val isAvailable: Boolean`。
- Kotlin wrapper 的**签名即契约**，RikkaHub Task 12 会按它接线。

---

## 7. 参数集扩展（Claude Code Grep 参数，决策点）

RikkaHub 想复用 Claude Code 的 Grep 工具参数集：`pattern/path/glob/output_mode/-A/-B/-C/context/-i/-n/-o/multiline/head_limit/offset/type`。

**建议分工**（native 保持最小，Kotlin wrapper 后处理）：
- **native 管**（改 lib.rs）：`pattern`、`path`、`filePattern`(glob)、`caseInsensitive`(-i)、`regex`/`literal`、`multiline`（`RegexMatcherBuilder.multi_line`）、`-o`（只匹配片段，grep-regex 的 `find_iter`）、`context`(-A/-B/-C)。
- **Kotlin wrapper 管**（后处理 blocks）：`output_mode`（content/files/count）、`head_limit`/`offset`（分页）、`type`（扩展名过滤）、`-n`（行号恒有）。
- 你可在 lib.rs 扩展 JNI 签名加 `multiline`/`only_matching`/`context_before`/`context_after`，或保持参考签名 + 在 wrapper 层模拟。**倾向：扩展签名，让 native 一次算对**。

---

## 8. 约束与验证

- **Android ABI**：必须 `arm64-v8a` + `armeabi-v7a` + `x86` + `x86_64` 四个（RikkaHub 目标设备覆盖）。
- **无本地编译环境**：RikkaHub 主工程本机无编译器；你的流水线构建必须在 GitHub Actions 上自验证。**发布前先跑一次完整 workflow**，确认 4 个 `.so` 都产出且 AAR 能被打包。
- **加载验证**：交付前用一个最小 Android 工程（或 RikkaHub 装 debug APK）确认 `NativeRipgrep.searchJson(...)` 实际能调通（无 UnsatisfiedLinkError、返回合法 JSON、搜到预期文件）。
- **版本管理**：artifact 用语义化版本；RikkaHub 依赖固定版本（如 `0.1.0`）。

---

## 9. 开放决策清单（你拍板并记录）

1. **repo 位置/命名**：建议 `DevLintMar/rikkahub-ripgrep-jni`。
2. **发布目标**：JitPack（推荐）/ GitHub Packages / Maven Central。
3. **JNI wrapper 包名**：建议 `me.rerere.rikkahub.native.ripgrep`（与 JNI 函数名强绑定）。
4. **是否扩展 JNI 签名**加 `multiline`/`only_matching`/context_before/after（§7）。
5. **Rust 库 `lib` name**：`cdylib` 的 name（`Cargo.toml [lib] name`）决定 `.so` 文件名，需与 `System.loadLibrary` 一致（如 `rikkahub_ripgrep`）。

---

## 10. 参考文件索引

| 文件 | 用途 |
|---|---|
| `references/Operit/tools/native_ripgrep/Cargo.toml` | 依赖清单（照抄） |
| `references/Operit/tools/native_ripgrep/src/lib.rs` | 完整 JNI 实现（移植起点） |
| `references/Operit/tools/native_ripgrep/Cargo.lock` | 锁定版本（建议保留） |
| RikkaHub `app/build.gradle.kts:322` | ratex 依赖方式（`implementation(libs.ratex)`）参考 |
| RikkaHub `gradle/libs.versions.toml` | 版本目录模式参考 |

---

## 11. 后续衔接（RikkaHub 侧 Task 12，你交付后由另一个代理做）

- RikkaHub `workspace_grep`（`WorkspaceTools.kt` `createGrepTool`）的 `execute` 改为：优先 `NativeRipgrep.searchJson(...)`（若 `isAvailable`），结果映射回现有 `{type:"workspace_grep", pattern, matches:[{path,line,text}]}` 信封；否则回退 JVM 实现。
- 加 Claude Code Grep 参数集（output_mode/head_limit/offset/type 等）到工具 schema。
- `workspace_glob` 加 mtime 排序（顺带）。
- 走 CI 验证 + 展示层（Plan 2）适配。
