# Kelivo LaTeX 渲染分析 & 接入评估

> 源项目：kelivo（Flutter/Dart）
> 目标项目：rikkahub（Android/Kotlin/Compose + JLatexMath）
> 日期：2026-07-12

---

## 一、Kelivo 方案结构

```
LLM 响应文本
    │
    ▼
_preprocessFences（三级流水线）
  ├─ STEP 1: 代码块掩码（围栏代码块 + 内联代码 → __CODE_MASK_n__）
  ├─ STEP 2a: $...$ 归一化 → \(...\)（字符级状态机，非简单正则）
  ├─ STEP 2b: $$...$$ display 块化（补空行，强转块级）
  ├─ STEP 2c: 流式稳定（未闭合 $ 自动闭合）
  ├─ STEP 2d: 表格尾行补全、双括号平铺、citation 转链接
  └─ STEP 3: 解掩码
    │
    ▼
GptMarkdown(widget) → 自定义 MarkdownComponent
  ├─ 块级: LatexBlockScrollableMd → _renderMath(displayMode=true)
  └─ 内联: InlineLatexDollarScrollableMd / InlineLatexParenScrollableMd
         → _renderMath(displayMode=false)
           → _normalizeMathTex(tex)
             ├─ # → \#（跳过 \color{#FF0000})
             ├─ {a,b,c} → \{a,b,c\}（启发式大括号转义）
             └─ \|...\| → \lVert ... \rVert
           → flutter_math_fork Math.tex()
           → onErrorFallback + try/catch 双重兜底
```

## 二、Kelivo 关键设计（值得借鉴的）

### 2.1 字符级 `$` 状态机 vs 正则

rikkahub 的 `preProcess` 只处理 `\(...\)` → `$...$`，依赖 IntelliJ Parser 自己去识别 `$...$`。Kelivo 则手写了一个逐字符扫描器：

| 能力 | rikkahub | Kelivo |
|------|----------|--------|
| `$5`（价格）误判为公式 | ❌ | ✅ `_canOpenDollarMath` 拒绝 |
| 中文标点边界 | ❌ | ✅ `_isDollarMathBoundary` |
| `\$` 转义感知 | ❌ | ✅ `_isEscaped` |
| 表格 `\|` 列分隔符冲突 | ❌ | ✅ `_isDollarMathOnMarkdownTableRow` |
| body 长度限制防回溯 | ❌ | ✅ `_maxInlineMathBodyLength = 512` |

### 2.2 TeX 源规整（`_normalizeMathTex`）

针对 LLM 输出的脏 TeX 做三层清洁，这是 rikkahub 完全缺失的：

1. **`#` → `\#`**：LLM 常直接输出 `#`，JLatexMath 会把 `#` 当参数符解析报错。跳过 `\color{#FF0000}` 等颜色的 hex 参数。
2. **`{a,b,c}` → `\{a,b,c\}`**：LLM 爱写 `{x \mid x>0}`，不转义则 flutter_math_fork/JLatexMath 把 `{}` 当命令组解析报错。启发式判断：内含 `,`、`:`、`\in` 之一且不是命令参数的 `{...}` 则转义。
3. **`\|...\|` → `\lVert ... \rVert`**：`\|` 渲染不稳定，替换为显式的范数命令。

### 2.3 内联公式基线对齐

Flutter 的 `WidgetSpan` + `PlaceholderAlignment.baseline` + 手写 `RenderObject` 转发 `computeDistanceToActualBaseline`，解决了"内联公式要 baseline 对齐又要横向滚动"的冲突。

### 2.4 架构设计：不信任上游

- 内置 LaTeX 全部禁用，全量自己接管
- 每个环节预设 LLM 会输出脏内容 → 防御式清理
- 双重兜底（`onErrorFallback` + `try/catch`）→ 绝不崩溃

---

## 三、接入可行性评估

### 3.1 ❌ 不可接入的

| 组件 | 原因 |
|------|------|
| `flutter_math_fork` 渲染引擎 | Dart 原生实现，Android/Kotlin 无法使用 |
| `gpt_markdown` 框架 | Flutter 专用 |
| 自定义 `RenderObject` | Flutter 渲染层 API，Compose 无对应物 |
| `WidgetSpan` / `PlaceholderAlignment.baseline` | Flutter Widget 体系 |

### 3.2 ✅ 可移植的思路

| Kelivo 做法 | rikkahub 等价实现 | 改动量 |
|---|---|---|
| `_normalizeMathTex` 规范化 | 在 `LatexText.kt` 扩展 `processLatex` 或在 `Markdown.kt` 中加入 | **小** |
| 代码块掩码保 `$` | `preProcess` 已有基础，改为用占位符（非空字符串）替换 | **小** |
| 内联公式基线对齐 | `PlaceholderVerticalAlign.TextBaseline` + 从 JLatexMath 提取 box depth | **中** |
| 字符级 `$` 状态机 | 替换 `preProcess` 的朴素正则（`preProcess` 当前只转 `\(\)`，不处理 `$`） | **中** |
| LLM 脏 TeX 防护 | 在 `processLatex` 中添加 `#` 转义、`{}` 歧义处理 | **小** |
| 双重兜底 | `LatexText` 的 `runCatching` + 绘制前校验 | 已基本实现 |

### 3.3 优先接入项（按 ROI 排序）

#### P0 — 立即修复当前渲染 bug（半天）
**`Markdown.kt:1006`** — `formula` 含 `$` 定界符导致 Placeholder 尺寸错位：
```kotlin
// 当前
val formula = node.getTextInNode(content)
// 修复
val rawFormula = node.getTextInNode(content)
val formula = rawFormula.removeSurrounding("$")
```
同时把 `processLatex` 从 `private` 提为 `internal`，让 `assumeLatexSize` 也走相同的去定界符逻辑，保证尺寸一致。

#### P1 — TeX 源规整（半天）
在 `LatexText.kt` 的 `processLatex`（或新加一个 `sanitizeLatex`）中添加：
- `#` → `\#`（跳过 `\color{#...}`）
- `{a,b,c}` 类字面大括号 → `\{...\}`（启发式检测）
- `\|...\|` → `\lVert ... \rVert`

这是 LLM 生成公式的常见脏输入，不规整会导致 JLatexMath 解析失败 → 退化到源码文本，用户体验差。

#### P2 — 基线对齐修复（1-2 天）
当前 `PlaceholderVerticalAlign.TextCenter` 导致公式基线错位。改为 Compose 1.5+ 的 `TextBaseline` + `assumeLatexSize` 返回 box depth 信息：

```kotlin
// 第一步：assumeLatexSize 同时返回 depth
data class LatexMetrics(val width: Float, val height: Float, val depth: Float)

// 第二步：Placeholder 使用 TextBaseline + baselineOffset
Placeholder(
    width = width, height = height,
    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBaseline,
)
```

需要 JLatexMath 的 depth 如何获取：JLatexMathDrawable 底层有 `TeXIcon.getIconDepth()`，但 builder API 可能不直接暴露。可以用反射或修改 jlatexmath-android 源码。

#### P3 — 鲁棒 `$` 定界符（2-3 天）
把 `Markdown.kt` 的 `preProcess` 从纯 `\(` → `$` 替换，升级为 `_replaceInlineDollarMath` 风格的字符级状态机。同时把原 `$` 的处理从依赖 IntelliJ Parser 识别改为手动归一化。这是影响面最大的改动，建议先修 P0-P2，观察效果后再决定。

---

## 四、结论

Kelivo 的 LaTeX 渲染**代码不可直接复用**（Flutter vs Android），但其**架构设计思路**有很高参考价值。最紧迫且可移植的三项是：

1. **P0**: 修 `Markdown.kt` 的 `$` 定界符泄漏 bug（当前渲染错误的直接原因）
2. **P1**: 添加 `_normalizeMathTex` 类 TeX 源规整（提升 LLM 脏输出的稳定性）
3. **P2**: 换用 Compose `TextBaseline` 对齐修复行内公式漂移

这三项完成后，rikkahub 的原生渲染路径在防御性和视觉效果上会接近 Kelivo 的成熟度，且每项改动量都在几十行 Kotlin 以内。
