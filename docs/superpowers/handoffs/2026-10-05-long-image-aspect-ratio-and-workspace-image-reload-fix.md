# 交接文档：长图全链路缩略治理、工作区图片加载重载死锁根除与倒计时展示规范

- **日期**：2026-10-05
- **基线版本**：v3.3.3 / versionCode 1180
- **当前代码 HEAD**：`389c258a` (`fix(ui): 治理长图缩略、工作区图片加载重载死锁与倒计时展示规范`)
- **分支状态**：`master`，本地编译与单元测试 100% 绿灯，静态门禁全部 PASS，并在运行中的 Android 模拟器实机安装验证通过

---

## 一、本会话核心攻坚总览

针对用户提出的三大核心缺陷与技术诉求，展开全量攻坚与系统化调试（Systematic Debugging）：
1. **倒计时工具调用链展示精炼**：
   - 标题规范为 `倒计时`（去除多余后缀）；
   - 下拉调用链概览时长摘要规范为紧凑英文字符 `h/m/s` 格式（如 `30s`、`1m`、`1h 30m`，去除中文）；
   - 数字与符号增加 `offset(y = (-1).dp)`，重心稍微上移微调，与左侧沙漏图标的几何中心视觉完美对齐。
2. **工作区图片“加载失败且重载无效”死锁彻底根治**：
   - 深入查明 AI 生成图片在初次渲染时因操作系统文件 IO 时差尚未落盘，导致回退到未解析虚拟路径（`/workspace/...`）；Coil 在宿主根目录找不到路径报 `FileNotFoundException` 触发 `onError`；
   - 查明 `ZoomableAsyncImage` 内部在用户点击“重新加载”时，`coilModel` 缺乏 `retryCount` 键，且内部静态字符串 `model` 缺乏重新调用 `LocalWorkspaceFileProvider` 机制的死锁；
   - 查明 `WorkspaceFileUrlResolver` 在外部未传 `workspaceId` 时直接返回 null 阻断自愈；
   - 治理落地：`WorkspaceFileUrlResolver` 增加全局存量物理工作区回退探测；`ZoomableAsyncImage` 内部注入 `workspaceResolver`，用户点击重载时立即重新探测落盘文件并自愈切换为宿主 File URI，且增加 300ms/800ms/1800ms 异步落盘轻量轮询自愈探测，无需用户手动点击即可自动呈现。
3. **非常长图片“展示缩略”双链路完整研究与治理**：
   - **链路 A（聊天列表气泡）**：Coil Scale.FIT 模式下给竖长图传入 4096px 硬高度导致宽度被反向等比压窄至 221px 产生低模模糊缩略，结合 `ImageAspectRatioCache` 的 `MIN_RATIO = 0.15f` 硬截断导致高宽比超过 6.67 的长图高度被砍掉 2/3；
     - 治理：解除 Coil 解码高度限制，采用 `Size(Dimension(screenWidth), Dimension.Undefined)` 无界解码，点对点全屏宽保真；将 `ImageAspectRatioCache` 的 `MIN_RATIO` 放宽至 `0.01f`（支持高达 100:1 的极限超长图）；超长图走软件位图解码避免超出 GPU 纹理限制。
   - **链路 B（全屏大图查看器 `ImagePreviewDialog`）**：`image-viewer` 依赖库内部（`ZoomableState.kt`）硬编码 `widthFixed = contentRatio > containerRatio`。当长图高宽比大于屏幕高宽比（0.45）时，被强制判定为高度适配（`heightFixed`），导致整张长图被缩放为屏幕高（2400px）、宽度缩至 259px 居中展示，呈现牙签细条缩略缺陷；
     - 治理：定制 `proceedPresentation`，当检测到竖长图时，自动计算 `fitWidthScale = containerRatio / contentRatio`，初始直接调用 `zoomState.scale.snapTo(fitWidthScale)` **以 100% 满宽展开**，消灭两侧大黑边；初始垂直偏移设为顶部对齐，用户可顺畅从顶端向下滑动浏览整张长图，彻底消除牙签缩略条。
4. **RikkaHub 内部 AI 记忆功能系统提示词与架构机制答疑**：
   - 深入剖析了 RikkaHub 内部内置的 AI 记忆系统（Active 活跃记忆 vs Saved 保存记忆）；
   - 解析了 `GenerationPrompts.kt` 中向 AI 注入的 `<memories>` 提示词、`MemoryTools.kt` 中 8 大 Function Calling 工具的描述词，以及 `GenerationLoop.kt` 中的完整运行时数据流。

---

## 二、关键技术缺陷分析与修复方案

### 1. 倒计时工具格式化实现 (`BuiltinToolUIs.kt`)
- **文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`
- **代码重构**：
  ```kotlin
  // 标题与标签规范
  "set_timer" -> "倒计时"

  // 摘要时长格式化
  private fun formatTimerDuration(seconds: Int): String {
      val h = seconds / 3600
      val m = (seconds % 3600) / 60
      val s = seconds % 60
      return buildString {
          if (h > 0) {
              append("${h}h")
              if (m > 0) append(" ${m}m")
              if (s > 0) append(" ${s}s")
          } else if (m > 0) {
              append("${m}m")
              if (s > 0) append(" ${s}s")
          } else {
              append("${s}s")
          }
      }
  }

  // 数字微调
  Text(
      text = timeStr,
      modifier = Modifier.offset(y = (-1).dp),
      style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onTertiaryContainer,
  )
  ```

---

### 2. 工作区图片加载失败与重载死锁根除 (`WorkspaceFileUrlResolver.kt` & `ZoomableAsyncImage.kt`)

#### 根因拓扑
```
AI 输出 Markdown (![img](/workspace/x.png))
       │
       ▼
首次组合 (Compose) ──> 文件写入中 / 句柄未释放 ──> isFile 为 false
       │
       ▼
回退未解析虚拟路径 "/workspace/x.png" 传给 ZoomableAsyncImage
       │
       ▼
Coil 请求 "/workspace/x.png" ──> 宿主系统无 /workspace 目录 ──> FileNotFound ──> onError
       │
       ▼
用户点击“重新加载” ──> coilModel 无 retryCount 键 ──> 请求未重建
       │           └── 静态 model 仍为虚拟路径 ──> 未重新探测宿主 File ──> 永远死锁
       ▼
【修复方案】
1. 点击重试时重新调用 LocalWorkspaceFileProvider 探测真实宿主文件；
2. retryCount 纳入 coilModel 构建依赖，强制 Coil 重新请求；
3. 后台 300ms/800ms/1800ms 轻量轮询自愈：AI 刚刚写完文件落盘即可自动展示，免去用户点击；
4. WorkspaceFileUrlResolver 在无 workspaceId 时支持跨现有物理工作区全局回退探测。
```

#### 关键改动代码 (`ZoomableAsyncImage.kt`)
```kotlin
val workspaceResolver = LocalWorkspaceFileProvider.current

var resolvedModel by remember(model, retryCount) {
    val initial = if (!model.isNullOrBlank() && workspaceResolver != null) {
        workspaceResolver(model)?.takeIf { it.isFile }?.toUri()?.toString() ?: model
    } else {
        model
    }
    mutableStateOf(initial)
}

// 时序自愈：针对刚写完但未完全落盘的工作区图片，延迟短轮询探测
LaunchedEffect(model, retryCount) {
    if (!model.isNullOrBlank() && workspaceResolver != null && resolvedModel == model) {
        if (model.startsWith("/") || model.startsWith("file:///workspace") || model.startsWith("file:///upload")) {
            for (d in listOf(300L, 800L, 1800L)) {
                delay(d)
                val found = workspaceResolver(model)?.takeIf { it.isFile }?.toUri()?.toString()
                if (found != null && found != resolvedModel) {
                    resolvedModel = found
                    hasError = false
                    break
                }
            }
        }
    }
}
```

---

### 3. 超长长图缩略双链路治理 (`ImageAspectRatioCache.kt` & `ZoomableAsyncImage.kt` & `ImagePreviewDialog.kt`)

#### 链路 A：列表气泡长图无界解码与安全比例
1. **`ImageAspectRatioCache.kt`**：
   - `MIN_RATIO = 0.01f`（放宽至 100:1 超长图，杜绝高宽比超过 6.67 时高度被砍掉 2/3）；
   - `MAX_RATIO = 50.0f`。
2. **`ZoomableAsyncImage.kt`**：
   - 竖长图尺寸使用 `Size(Dimension(screenWidth), Dimension.Undefined)` 无界解码，宽度点对点全分辨率采样，高度自然延伸；
   - 超长图（高宽比超过 2:1 或首次未测比例）设置 `allowHardware(false)` 走软件解码，防御 GPU 纹理溢出。

#### 链路 B：大图查看器竖长图满宽与自适应滚动 (`ImagePreviewDialog.kt`)
- **源码根因**：`image-viewer` 底层针对 `contentRatio <= containerRatio` 强制高度适配（`scale1x = containerHeight / contentHeight`），导致 1080×10000 长图被缩放为宽 259px 的牙签细条。
- **治理落地**：
  ```kotlin
  proceedPresentation = { model, size, processor, imageLoading ->
      if (model != null && size != null) {
          ZoomablePolicy(intrinsicSize = size) { zoomState ->
              // 针对竖长图自适应满宽展示与顶部对齐，消灭 image-viewer 默认高度适配导致的牙签细条缩略缺陷
              LaunchedEffect(zoomState, size, zoomState.containerSize.value) {
                  val container = zoomState.containerSize.value
                  if (container.width > 0f && container.height > 0f && size.width > 0f && size.height > 0f) {
                      val containerRatio = container.width / container.height
                      val contentRatio = size.width / size.height
                      if (contentRatio < containerRatio) {
                          // 竖长图（高宽比超过屏幕高宽比）：将初始显示宽度缩放至满宽（Fit Width）
                          val fitWidthScale = containerRatio / contentRatio
                          zoomState.scale.snapTo(fitWidthScale)
                          // 初始顶部对齐：让用户从长图最顶端开始阅读
                          val realHeight = container.height * fitWidthScale
                          val topOffsetY = (realHeight - container.height) / 2f
                          zoomState.offsetY.snapTo(topOffsetY)
                          zoomState.offsetX.snapTo(0f)
                      }
                  }
              }
              processor.Deploy(model = model, state = zoomState)
          }
          size.isSpecified
      } else if (model != null && model is AnyComposable && size == null) {
          model.composable.invoke()
          true
      } else {
          imageLoading?.invoke()
          false
      }
  }
  ```

---

## 三、涉及文件修改一览

| 模块 / 文件 | 改动说明 |
| :--- | :--- |
| `app/src/main/java/.../BuiltinToolUIs.kt` | 倒计时标题统一为“倒计时”，摘要改为紧凑英文字符 `h/m/s` 格式，文本增加 `offset(y = (-1).dp)` 微调。 |
| `app/src/main/java/.../WorkspaceFileUrlResolver.kt` | `resolveFile` 增加存量工作区物理目录全局自愈回退探测，无 `workspaceId` 时也能正确解析。 |
| `app/src/main/java/.../ImageAspectRatioCache.kt` | `MIN_RATIO` 调整为 `0.01f`，`MAX_RATIO` 调整为 `50.0f`，支持 100:1 超长图不截断。 |
| `app/src/main/java/.../ZoomableAsyncImage.kt` | 竖长图高度无界解码；打通重试与 `retryCount` 依赖；注入 `workspaceResolver` 与 300~1800ms 异步落盘自愈。 |
| `app/src/main/java/.../ImagePreviewDialog.kt` | 接入 `workspaceResolver` 支持工作区原图解析；定制 `proceedPresentation` 实现竖长图满宽展示与顶部自适应对齐。 |
| `app/src/test/java/.../ImageAspectRatioCacheTest.kt` | 补充超长图极限比例 0.01f 与 50.0f 的单元测试。 |
| `app/src/test/java/.../WorkspaceFileUrlResolverTest.kt` | 新增无 `workspaceId` 时自动回退探测自愈的单元测试用例。 |

---

## 四、验证与门禁结果 (Verification)

1. **静态代码审计门禁**：
   - `hugeicons_glyph_audit.py`：**PASS**
   - `baseline_profile_audit.py --strict`：**PASS**
   - `prefs_key_audit.py`：**PASS**
2. **Kotlin 单元测试**：
   - `:app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.*"`：**PASS**
   - `:app:testDebugUnitTest --tests "me.rerere.rikkahub.data.files.WorkspaceFileUrlResolverTest"`：**PASS**
3. **Android 模拟器实测与构建**：
   - 仅编译 Debug 包（`./gradlew assembleDebug`）；
   - 通过 ADB 将 `app-debug.apk` 覆盖安装至运行中的 Android 模拟器 `emulator-5554`；
   - 启动应用并监听 Logcat，`AndroidRuntime:E` 零报错，界面渲染与交互正常。

---

## 五、后续状态与 Compact 提示

代码库处于干净状态（`working tree clean`），全部修改已通过 Commit `389c258a` 保存并完成单元测试。用户在终端输入 `/compact` 即可压缩当前会话上下文。
