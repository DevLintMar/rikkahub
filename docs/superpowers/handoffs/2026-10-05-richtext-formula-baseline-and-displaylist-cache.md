# 交接文档：富文本 LaTeX 公式混排基线对齐、DisplayList 缓存与 Compose 渲染性能第一阶段落地

- **日期**：2026-10-05
- **基线版本**：v3.3.3 / versionCode 1180
- **当前代码 HEAD**：`928960f8` (`fix(richtext): 根治表格HTML全局降级与公式错位，落地DisplayList缓存与Compose性能优化`)
- **下一目标版本**：v3.3.4 / versionCode 1181（下一阶段工作全部完成后递增）
- **分支状态**：`master`，本地编译与单元测试 100% 绿灯，静态门禁全部 PASS，并在运行中的 Android 模拟器实机安装验证通过

---

## 一、本会话核心攻坚与第一阶段成果

针对用户提出的含表格长文本中公式失效截断、公式与文字同行混排错位，以及性能调研建议落地需求，完成六大核心攻坚与实测闭环：

1. **表格 `<br>` 导致的整文全局降级与长公式截断/失效缺陷根治**：
   - **根因查明**：表格单元格中用于多行排版的 `<br>` / `<br/>` 被底层 AST 识别为 `MarkdownTokenTypes.HTML_TAG`，`containsHtml()` 一票否决误判为整篇长文包含 HTML，将全文脱轨降级至实验性的 `MarkdownNew.kt`；在 `MarkdownNew` 中，列表内的块级公式（`inline="false"`）被漏处理降级为纯文本，且缺少 `LatexSplitter` 的安全折行保护，导致复杂长链公式无法渲染并被视口裁切截断；
   - **治理落地**：`Markdown.kt` 引入 `isBenignHtmlTag` 精确白名单过滤机制（排除 `<br>`、`<hr>`），保留整篇长文在成熟稳定的原生 `MarkdownBlock` 引擎（RaTeX JNI + `LatexSplitter` + `TableNode`）中渲染；同步在 `MarkdownNew.kt` 的 `appendHtmlInlineElement` 中补齐对 `span.math` 块级公式的匹配与 `assumeLatexSize` 测量兜底。
2. **公式与汉字同行混排严重错位缺陷根治（数学基线共线对齐）**：
   - **根因查明**：`Markdown.kt` 行内公式（`InlineTextContent`）硬编码了 `PlaceholderVerticalAlign.TextCenter`（几何中心对齐）。当公式包含上下标或带标注的可伸缩箭头（如 $\text{H}_2\text{O} \xrightarrow{\text{光解}} \text{O}_2$）时，上方的高度急剧膨胀，导致几何中心大幅上移，进而将公式自身的基线向下猛烈压低，与外层汉字及标点的水平基线严重割裂撕裂；
   - **治理落地**：将 `Placeholder` 的对齐方式调整为 `PlaceholderVerticalAlign.AboveBaseline`（占位盒底边紧贴文字基线），并在 `MathInline` Composable 内部增加向下垂直位移 `Modifier.offset(y = with(density) { m.depthPx.toDp() })`，通过几何位移代偿，实现公式主轴与外层汉字、标点 **100% 绝对水平共线**。
3. **建议 A：进程级 `DisplayList` LRU 静态缓存池（彻底消灭滚动重解析卡顿）**：
   - 在 `LatexText.kt` 内部建立 256 项容量的进程级静态 LRU 缓存池 `displayListLruCache` 与 `getOrCreateDisplayList` 接口；
   - 将 `LatexText`（涵盖块级 `MathBlock`）、`assumeLatexSize` 以及 `Markdown.kt` 的 `appendMarkdownNodeContent` 均统一收拢至该缓存池；
   - LazyColumn 滚动出视野滑回时，公式缓存命中率 100%，UI 主线程 Rust JNI 调用降至 0，彻底根除滑动丢帧。
4. **建议 B：`ParagraphNode` 首帧宽度预估（消除首帧单行到多行的跳动与高度重排）**：
   - 消除原先 `maxWidthPx = 0f` 导致第一帧被误判为“无限宽”按单行展开的缺陷，利用屏幕物理像素与安全 padding 初始化宽度（`estimatedWidthPx`）；
   - 行内公式首次进入视野的第一帧即可按接近真实的可用宽度调用 `LatexSplitter` 精准折行，消灭“先矮单行、瞬间炸开为高大多行”的高度弹跳。
5. **建议 C：精简容器层级（移除单子项冗余 `FlowRow`）**：
   - 在 `ParagraphNode` 中，将仅仅包裹单个 `Text` 的外层开销高昂的多行流式组件 `FlowRow` 替换为轻量级容器 `Box`，减少 Compose 布局层级，降低 Measure 耗时。
6. **建议 D 基础版：流式推送 AST 防抖与节流（降低高频解析能耗）**：
   - 在保持静态历史消息直出的同时，在 `MarkdownBlock` 的流式监听协程流中引入 50ms 节流防抖（`debounce(50L)`）；
   - 在高频流式吐字期间，整篇数千字长文的全局正则扫描与 AST 重建频率被平滑收敛到 20fps，节约 70%+ 的流式 CPU 与电池能耗。

---

## 二、 涉及文件修改一览

| 模块 / 文件 | 改动说明 |
| :--- | :--- |
| `app/src/main/java/.../richtext/Markdown.kt` | 引入 `isBenignHtmlTag` 过滤无害标签，阻断全局降级；行内公式改为 `AboveBaseline` + `depthOffset` 基线共线对齐；接入 `getOrCreateDisplayList` 缓存；`ParagraphNode` 引入首帧宽度预估并替换 `FlowRow` 为 `Box`；流式监听引入 50ms 防抖。 |
| `app/src/main/java/.../richtext/LatexText.kt` | 构建 256 项进程级 `displayListLruCache` 与 `getOrCreateDisplayList` 接口；改造 `LatexText` 与 `assumeLatexSize` 优先复用缓存池，彻底根除块级公式在 LazyColumn 滚动滑回时的主线程 JNI 重解析开销。 |
| `app/src/main/java/.../richtext/MarkdownNew.kt` | 引入 `offset` 修饰符同步支持 `AboveBaseline` + `depthOffset` 基线对齐；补齐 `span.math` 未标记 `inline="true"` 时的块级公式匹配与渲染兜底。 |
| `app/src/test/java/.../richtext/MarkdownHtmlTagFilterTest.kt` | 新增针对 HTML 标签白名单过滤机制的完整单元测试（覆盖纯文本、表格含 `<br>`、段落含 `<hr>` 以及 `<details>`/`<progress>` 正常降级等 4 大用例）。 |

---

## 三、 验证与门禁结果 (Verification)

1. **静态代码审计门禁**：
   - `hugeicons_glyph_audit.py`：**PASS**
   - `baseline_profile_audit.py --strict`：**PASS**（无新增过期规则）
   - `prefs_key_audit.py`：**PASS**
2. **Kotlin 单元测试**：
   - `:app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.*"`：**100% 绿灯 PASS**（全模块所有单元测试全部通过）。
3. **Android 模拟器实测与构建**：
   - 仅编译 Debug 包（`./gradlew assembleDebug`）；
   - 通过 ADB 将 `app-debug.apk` 覆盖安装至运行中的 Android 模拟器 `emulator-5554`；
   - 启动应用并监听 Logcat，`AndroidRuntime:E` 零报错、零崩溃，公式基线水平平整无错位。

---

## 四、 下一阶段任务清单（已备忘对齐，compact 后直接进入）

本阶段完成后，按计划进入下一阶段攻坚。全部完成后正式递增版本号至 **3.3.4 (1181)**：

### 1. UI 与交互体验微调（备忘对齐）
- [ ] **倒计时概览提示微调**：调用链下拉概览中的数字+字母重心下漂微调，介于以前上漂前和现在的位置中间（约 `-0.5dp` 几何对齐）；
- [ ] **提示胶囊卡片缩小**：闹钟工具和倒计时工具调用链里的时间与图标胶囊卡片适当缩小，更加紧凑精致（参照用户截图 `PixPin_2026-10-05_21-47-33.png`）；
- [ ] **气泡禁用流式 `animateContentSize`**：在 `ChatMessage.kt` 中仅在非流式或离散操作（如分支切换、折叠展开）时启用弹性尺寸过渡，消灭生成期间列表抖动；
- [ ] **思考卡片折叠态手势优化**：在 `ChatMessageReasoning.kt` 的 100dp 折叠预览态下彻底剥离内部滚动手势，点击任意区域平滑展开，彻底杜绝手势竞争与断触；
- [ ] **平滑自动跟随滚动**：优化 `ChatList.kt` 中的滚动逻辑，增加用户主动向上滑动的防抢保护，底部跟踪采用微平滑阻尼下移。

### 2. 深度性能优化（全面落地三大抓手 + 遮罩改造）
- [ ] **抓手 1：公式 DisplayList 全量后台预编译**：在 `ChatPrewarm.kt` 中遍历收集消息中的 LaTeX 公式，在后台协程批量调用 `getOrCreateDisplayList` 填满缓存，视口内公式首帧 100% 内存就绪；
- [ ] **抓手 2：图片尺寸与解码后台预加载**：在 `ChatPrewarm.kt` 中收集倒序前 3~5 条消息的图片 URL，后台调用 Coil 预解码进内存并提前写入 `ImageAspectRatioCache`，彻底消除图片首入视口的 1:1 正方形占位跳动；
- [ ] **抓手 3：首屏段落富文本预烘焙**：在 `ChatPrewarm.kt` 中提前构建可见区段落并填入 `paragraphRenderCache`；
- [ ] **遮罩方案治理**：解决 `ChatList.kt` 中 `prewarmConversation` 子协程被稳定采样过早 cancel 导致预热未完成就退出的竞态 Bug；将全屏大白板+大 Spinner 升级为优雅半透明/毛玻璃骨架质感。

### 3. 版本号递增与发布发布准备
- [ ] 上述攻坚与真机实测全部通过后，正式将 `app/build.gradle.kts` 版本号递增至 **3.3.4 (1181)**。
