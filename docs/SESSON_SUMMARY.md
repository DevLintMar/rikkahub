# RikkaHub LaTeX 渲染改造 — 交接文档

> 日期: 2026-07-12
> 分支: `feat/ratex-v2`（主力）、`feat/ratex-release`（无 CI 工作流版本）
> Fork: `DevLintMar/rikkahub`

---

## 已完成的工作

### 1. 渲染引擎替换: JLatexMath → RaTeX-CMP

**依赖**: `io.github.darriousliu:ratex:0.1.12-1`（Maven Central）
**删除**: jlatexmath 及其 Greek/Cyrillic 字体包

**改动文件**:

| 文件 | 说明 |
|------|------|
| `LatexText.kt` | 完全重写，JLatexMathDrawable → RaTeXEngine + RaTeX composable |
| `MathBlock.kt` | 新增 `displayMode` 参数（inline=false, block=true） |
| `Markdown.kt` | 剥 `$`/`$$` 定界符；Placeholder 改用 `LatexMetrics(widthPx, heightPx, depthPx)` |
| `MarkdownNew.kt` | 同步修复 Placeholder 尺寸计算 |
| `app/build.gradle.kts` | 依赖替换 + debug 启用 R8 优化 |
| `gradle/libs.versions.toml` | 版本目录替换 |
| `app/proguard-rules.pro` | 删除 jlatexmath 混淆规则 |
| `RikkaHubApp.kt` | 新增 `registerSerifCjkFallback` 调用 |

### 2. 已修 Bug

| Bug | 根因 | 修复 |
|-----|------|------|
| `$1+1=2$` 中 `1` 上漂 | `Markdown.kt:1006` formula 含 `$` 定界符，`assumeLatexSize` 尺寸错位 | 加 `.trimStart('$').trimEnd('$').trim()` |
| 块公式后行内公式上漂 | `Markdown.kt:771` FlowRow 默认 `Alignment.Top` | 加 `itemVerticalAlignment = Alignment.CenterVertically` |
| WebView 预览不渲染 | 同 https://github.com/DevLintMar/rikkahub/actions 查看 | |

### 3. CI 工作流

| 工作流 | 触发方式 | 说明 |
|--------|----------|------|
| `verify-compile.yml` | push 所有分支 | `assembleDebug` 验证编译，输出 APK artifact |
| `nightly-build.yml` | 定时/手动 | `assembleRelease`，临时密钥签名，发布到 Release tag `nightly` |

`nightly-build.yml` 在 `master` 上（才能在 Actions UI 显示 + workflow_dispatch）。
`verify-compile.yml` 在 `feat/ratex-v2` 上。

### 4. CJK 衬线字体方案（当前状态）

**方案**: 打包 Noto Serif CJK SC Regular (24MB OTF) + 通过反射替换 FontCache 中每个 KaTeX Typeface 为包含 CJK fallback 的复合字体

**关键代码**: `SerifCjkFallback.kt`

```kotlin
suspend fun registerSerifCjkFallback(context: Context) {
    RaTeXFontLoader.ensureLoaded()  // 先加载 KaTeX 字体
    val cjkBytes = context.assets.open("fonts/NotoSerifCJKsc-Regular.otf").readBytes()
    RaTeXFontLoader.registerCjkFallbackFont(cjkBytes)
    // 反射: 遍历 FontCache，用 Typeface.Builder(tempCjkFile).setFallback(originalKaTeX) 替换
    // 编译时通过反射绕过 API 检查（setFallback 参数为 Typeface，但编译期只认 String）
}
```

**在 `RikkaHubApp.onCreate()` 中通过 `get<AppScope>().launch(Dispatchers.IO)` 调用。**

---

## 待办 / 已知问题

### P0 - 字体方案可能仍无效
拉取 APK 测试公式中中文是否显示为衬线体。如果无效，原因可能是 `Typeface.Builder.setFallback()` 的反射调用在运行时也接收 `Typeface` 参数但内部不识别。**备选**：直接修改 RaTeX-CMP 库源码的 `drawPlatformGlyph` 函数，检测 CJK codepoint 时切到我们的字体。

### P1 - `assumeLatexSize` 阻塞 UI 线程
`Markdown.kt` 的 AnnotatedString 构建中调用了 `assumeLatexSize` → `RaTeXEngine.parseBlocking()`（JNI 同步调用）。如果公式复杂或数量多，Compose 的 `remember` 阶段会卡帧。目前没出现明显卡顿，但如果以后报告卡顿可以考虑把 Placeholder 尺寸提取到后台。

### P2 - 24MB 字体增大 APK
`NotoSerifCJKsc-Regular.otf` 24MB。如果 APK 体积敏感，可以：
- 改用 `font/` 资源目录（自动压缩）
- 或使用 subset（只包含常用汉字）
- 或改用系统衬线体方案（之前尝试过 `setFallback("serif")` 但 `Typeface.Builder(File/String)` 编译失败，已换成反射）

### P3 - 超长行内公式无法自动换行（splitLatex 丢失）
master 2.4.1 原有 `splitLatex` 功能：过长行内公式在顶层运算符（`+`、`=`、`,` 等）处拆为多段，段间插零宽空格让 Compose 文本流可自动换行。该功能依赖 JLatexMath 的 drawable 分段能力，RaTeX 迁移时被移除。

**备选方案**：
1. **启发式正则拆分**：传入 RaTeX 前在运算符处拆成多个小公式，分别渲染后拼接
2. **`horizontalScroll` 包裹**：超长行内公式自动包横向滚动，与块公式行为一致

### P4 - master 没有我们的 CI 文件
`feat/ratex-release` 分支不包含 CI workflow，`master` 一样也没有。如果需要在 master 上构建，可以用 `nightly-build.yml` 的 trigger。

---

## 分支关系

```
master (原始 2.4.1) ← 干净的官方代码
feat/ratex-v2        ← 主力分支，含所有改动 + CI workflow
feat/ratex-release   ← 同 feat/ratex-v2 的代码，不含 CI workflow，恢复原始 splits/ndk 配置
feat/ratex-integration ← 废弃，基于 2.2.1
```

---

## 复现编译

```bash
# 本地
./gradlew app:assembleDebug -x :web:buildWebUi

# CI
# 推送到 feat/ratex-v2 自动触发 verify-compile.yml
# master 上手动触发 nightly-build.yml
```

APK 下载: Actions 页面 Artifacts → `rikkahub-debug-apk.zip`
