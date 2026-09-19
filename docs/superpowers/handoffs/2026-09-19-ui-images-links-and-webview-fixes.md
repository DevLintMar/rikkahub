# 交接文档：界面四项 + file:// 崩溃 + 网页视图图片链路

**最后核对：2026-09-19** —— 仓库 `HEAD = 09bb1970`（与 origin 同步）；`09bb1970` 的 CI
（run `35428592889`）在本文档写就时仍在跑，**结论见 §3**（判断只认 `--json`）。

---

## 0. 一句话概况

用户一次报了 4 件事（无工作区附件可贴性说明 / `search_web` 先验图再贴 / 网页视图图片加载 /
`file://` 超链接点击崩溃），随后分三批补了现场证据（崩溃栈、Console Logs、两张截图），
最终收敛成 **6 个独立根因**（其中 4 个是我或既有实现的真 bug，2 个是提示词缺失），
外加**我自己引入的 3 个编译错误**（三次 CI 红全在这儿，见 §2.6）。改动集中在「本地文件/图片在 WebView 与 Coil 两条链路上的行为」，
这是本仓第一次把这两条链路的差异摊开来看（见 §2.4、§2.5）。

---

## 1. 已完成（commit 链）

起点 `95f44008`（上一阶段交接文档自检），本轮 10 个提交：

| # | 提交 | 内容 |
|---|---|---|
| 1 | `8a5fec19` | 提示词：无工作区时告知附件可贴回；`search_web` 先验图再决定贴不贴 |
| 2 | `5a02c692` | 补回 huge-icons 1.3 缺了弧线的 10 个图标（网络项缺外圆等） |
| 3 | `7d4928ad` | huge-icons 字形门禁 + 同步清单第 13 类「依赖产物本身是坏的」 |
| 4 | `a8317c25` | `file://` 链接改走应用内预览，不再抛给系统（FileUriExposedException 崩溃） |
| 5 | `d55cc534` | 网页视图图片正则：尖括号 + 空格、圆括号写法也要改写 |
| 6 | `82ba1cc9` | **宽高比缓存方向写反**（竖图被锁成横框 → 大量留白） |
| 7 | `be776d39` | 修我自己引入的编译错误：KDoc 里 `workspaces/*/files` 提前闭合块注释 |
| 8 | `44aa5791` | 修我自己引入的编译错误：`RouteActivity` 漏 `import remember` |
| 9 | `9fb0740b` | 预览页外网图片改用 app 的 HTTP 客户端取（WebView 不走 app 代理） |
| 10 | `09bb1970` | 修我自己引入的编译错误：`getKoin` 是 `KoinComponent` 的成员，不能当扩展 import |

### 1.1 用户 4 件事 → 根因 → 落点

| # | 用户原话 | 根因 | 落点 |
|---|---|---|---|
| 1 | 无工作区加上 `/uploads` 里文件的可贴性说明 | 只有 `WorkspaceReminderTransformer` 讲 `file://` 用法；**没绑工作区的助手一个字都没有**，而它的附件 URL 早被 `ImageLazyLoadTransformer` 改写成 `file:///upload/<名>`（模型看得见、不知道能引用） | 新 `UploadReminderTransformer` |
| 2 | 用搜索工具时说明要先读图确认能加载且有用再贴 | 原提示词只有「图片有帮助就贴」 | `SearchTools.kt` 的 Images 段 |
| 3 | 网页视图里 url / file 图片都加载不出来 | **三个独立原因**（§2.4 / §2.5 / §2.6） | `MarkdownWeb.kt`、新 `WebViewRemoteImages.kt` |
| 4 | `file://` 引向的超链接点击直接崩溃 | 点击发生在**弹出层**（`ModalBottomSheet` 是独立组合子树），拿的是 Compose **默认** UriHandler | 新 `LocalFileOpener.kt` + `RouteActivity` 根部接线 + `Markdown.kt` |
| 补 A | 图片宽高占比异常、大量留白 | `ImageAspectRatioCache` 存「高/宽」，`Modifier.aspectRatio` 要「宽/高」 | `ImageAspectRatioCache.kt` + 单测 |
| 补 B | 外网图原生能渲染、网页里裂图 | WebView **不用** app 配的代理/UA | 新 `WebViewRemoteImages.kt` |

### 1.2 本轮新增的文件

| 文件 | 作用 |
|---|---|
| `data/ai/transformers/UploadReminderTransformer.kt` | 无工作区时的附件可贴性提示 |
| `ui/components/richtext/ForkIcons.kt` | 本地补的 10 个 huge-icons（几何取 1.4、描边取 1.3） |
| `docs/superpowers/scripts/hugeicons_glyph_audit.py` | 字形完整性门禁（离线，清单入库） |
| `docs/superpowers/data/hugeicons-1.3-suspect.json` | 347 个「1.3 里少画一段」的图标名 |
| `ui/components/richtext/LocalFileOpener.kt` | `LocalFileOpener` / `LocalLocalFileOpener` / `resolveLocalFile`（含按文件反查工作区） |
| `ui/components/webview/WebViewRemoteImages.kt` | 外网图片改用 app 的 OkHttp 代取 |
| `ui/components/webview/WebViewRemoteImagesTest.kt` | 图片分流条件的 JVM 单测 |

---

## 2. 关键决策与认知

### 2.1 崩溃栈怎么读出「弹出层」这条线索

```
FileUriExposedException: file:///workspace/九宫暗渡/规则书.md exposed beyond app through Intent.getData()
  at androidx.compose.ui.platform.AndroidUriHandler.openUri        ← Compose 的**默认**处理器
  at androidx.compose.material3.BottomSheetKt$$ExternalSyntheticLambda2.invoke
  at androidx.compose.foundation.CombinedClickableNode.handleUpEvent
```

上一轮（`2026-09-13` 的 D2）我以为「替换 markdown 子树的 `LocalUriHandler` 就拦得住所有链接」——
**对 markdown 正文成立，对弹出层不成立**：`ModalBottomSheet` 是独立组合子树，不在那个 provider 的作用域里。
所以这次改成**装在应用根部**（`RouteActivity` 的 `CompositionLocalProvider`），任何子树点本地文件都走
`openMarkdownLink`；markdown 子树里那份 provider 保留（它额外知道会话的 `workspaceId`）。

> 附带教训：要说「所有链接都走 X」时，先问一句「弹出层/对话框算不算同一棵子树」。

### 2.2 按类型分流的映射不是拍脑袋定的

用户的诉求是「在工作区管理界面点开不同文件都有预览方式，直接弹出那个预览即可」——
于是**照抄 `WorkspaceDetailPage.onOpen` 的既有行为**：

| 类型 | 目标 |
|---|---|
| 图片 | 应用内 `ImagePreviewDialog`（`ZoomableAsyncImage` 自带全屏看图） |
| 文本 / svg | `Screen.WorkspaceFileEditor`（工作区管理界面点文件就是进这个页） |
| 其余 | 安全兜底：FileProvider 选择器；没有处理者就什么都不做（**绝不崩**） |

两个边界要知道：

- **`/upload`、`/skills`、`/tool_outputs` 是 bind mount，不在任何工作区里** → 文件编辑器读不到 →
  非图片只能走兜底。要根治得给工作区管理器加一个 upload 区（本轮没做，用户也没选这条）。
- 弹出层/根部拿不到会话的 `workspaceId` → `resolveLocalFile` 会**按文件反查**：
  `/workspace/<rel>` 遍历 `workspaces/<id>/files/<rel>` 看哪个工作区里有这个文件。

### 2.3 宽高比：一个字符级的方向错误，代价是满屏留白

```kotlin
// ImageAspectRatioCache.put()
val ratio = height.toFloat() / width.toFloat()      // ← 存的是「高 / 宽」
// ZoomableAsyncImage
modifier.aspectRatio(cachedAspectRatio!!)            // ← 这里要的是「宽 / 高」
```

900×1200 的竖图 → 缓存 1.33 → 布局按「宽:高 = 1.33」定格成**横框** → 图片 `ContentScale.Fit` 缩进去
→ 四周大片留白。改成存「宽 / 高」，单测期望值同步改（`200×100 → 2` 而不是 `0.5`），
并把测试名改成点明方向的名字，避免再被写反。

### 2.4 WebView **不用** app 配的代理（这是本轮最有复用价值的一条）

`OkHttpClient`（Koin single，`di/DataSourceModule.kt:131`）挂着
`SettingsProxySelector` / `SettingsProxyAuthenticator` / 自定义 `User-Agent`，
**只服务 app 自己的请求**；WebView 走系统网络栈，既不走那个代理、UA 也是它自己的。

→ 结果：**同一张外网图，原生（Coil → OkHttp，经代理）能渲染，网页视图（WebView 直连）裂图**，
而且只有「要经代理才通」的那部分图如此（能直连的 CDN 两边都正常）—— 用户看到的「部分」正是这个指纹。

修法：`shouldInterceptRequest` 里把**非主文档的 http(s) 图片**先用 app 的客户端取一次。
两个关键取舍：

- **`.callTimeout(20s)`**：必须压，否则那 10 分钟 `readTimeout` 会把 WebView 的请求线程挂死；
  复用同一个连接池与代理设置，别新建 client。
- **取不到一律返回 `null`** → 交回 WebView 自己再试。**必须是纯增量**，不能把原本能显示的图弄坏。

### 2.5 网页视图里 `file://` 图片「空白」而不是「裂图」的两种原因

预览页 origin 是 `https://rikkahub.local`，`file://` 子资源必被 WebView 拦掉，所以本地图片必须
改写成 `https://rikkahub.local/local/<id>/<hex>` 让拦截器读文件。**没被改写**的 `file://` 就是空白。

原正则只认「不含空格与圆括号的裸地址」，于是这两类漏网：

- `![x](<file:///workspace/hsr-images/4.6 前哨特别节目主视觉（官方）.jpg>)` —— 尖括号里**带空格**
  （模型下载的长文件名常这样写）
- `![x](file:///upload/diagram(1).png)` —— 圆括号会被截断成 `diagram(1`

新正则：尖括号里允许任意字符、裸地址允许一层圆括号，补了 3 个 JVM 单测。

### 2.6 我自己踩的三个坑（**本轮三次 CI 红全在这儿**，都是编译期）

1. **Kotlin 块注释可以嵌套** —— KDoc 正文里写 `` `workspaces/*/files/<rel>` ``，那个 `*/`
   会把整段注释提前闭合 → `Syntax error: Unclosed comment`。改成 `workspaces/<id>/files/<rel>`。
2. **漏 `import androidx.compose.runtime.remember`** —— `RouteActivity.kt` 此前只用过
   `rememberNavBackStack`，加 `by remember { mutableStateOf }` 时只补了后两个 import。
3. **把 `getKoin` 当扩展 import** —— `getKoin()` 是 `KoinComponent` 的**成员**，不能 import
   （`Unresolved reference 'getKoin'`）。本仓两种写法都有：`ReadImageTools` 用
   `org.koin.java.KoinJavaComponent.getKoin`，`ImageLazyLoadTransformer` 用
   `KoinComponent` + `org.koin.core.component.get`。这次跟了后者。

> 自查手法（值得复用）：对改过的每个 `.kt`，把用到的 Compose API 名字列出来对 import 表求差
> （注意用 `\r?$` 匹配 CRLF 行尾，否则会全表误报）。

---

## 3. git / CI 状态

- 代码 HEAD = `09bb1970`（本文档自身是其后一个提交），与 origin 同步。
- **`44aa5791` 已真绿**：run `35426742745`，`conclusion=success`，`headSha` 对得上，
  `build` 作业 **21 步全 success、0 skipped**（不是「24h 无提交」的假绿），
  全模块 `testDebugUnitTest` 跑过、`BUILD SUCCESSFUL in 3m 52s`。
- `9fb0740b`：run `35428343921` = **failure**（就是 §2.6-3 那个 `getKoin`，`:app:compileDebugKotlin` 挂，
  后面 7 步 skipped）→ 修在 `09bb1970`。
- `09bb1970`：run `35428592889`，本文档写就时在跑。**恢复后第一件事：用 `--json` 判它**（见 §3.1），
  绿了才谈设备核验。

### 3.1 `gh run watch --exit-status` 的退出码**不可信**（本轮实测两次）

那个 run 的 `conclusion` 是 `failure`，watch **返回 exit=0**；被 cancel 的 run 也返回 0。
**结论只认**：

```bash
gh run view <id> --json conclusion,headSha          # 核对 headSha 是本次提交
gh run view <id> --json jobs                        # 确认 build 没被 skip 成假绿
```

（已写进 memory `gh-run-watch-monitoring`。）

### 3.2 本轮 CI 共红 3 次，**全是编译错误，全是我自己引入的**

| run | 提交 | 错误 |
|---|---|---|
| `35426285713` | `d55cc534` | KDoc 里的 `*/` 提前闭合注释（§2.6-1） |
| `35426516124` | `be776d39` | 漏 import `remember`（§2.6-2） |
| `35428343921` | `9fb0740b` | `getKoin` 当扩展 import（§2.6-3） |

三次都是「`:app:compileDebugKotlin` 失败 → 后面 7 步 skipped」，**前面的改动一次都没被验证过**，
直到 `44aa5791`（run `35426742745`）才第一次跑通全模块单测。
这也是为什么本轮「谁验证过了」要分开记账（§5.1）。

> 教训：本机没有编译器时，新写的 Kotlin 文件落盘后至少过一遍
> ① import 表对照（用了哪些 API、有没有对应 import）、② 括号/注释闭合、
> ③ 第三方框架的「成员 vs 扩展」写法是否照抄了仓库既有用法。这三次红都栽在这三条上。

---

## 4. 恢复地图

```bash
git log --oneline 95f44008..HEAD          # 本轮 9 个提交
git show 9fb0740b --stat                  # 最后一个提交
gh run view 35428343921 --json conclusion # 先确认它绿没绿
```

- **提示词**：`data/ai/transformers/UploadReminderTransformer.kt`（无工作区）、
  `data/ai/tools/SearchTools.kt` 的 Images 段；两者的注册点在 `service/ChatService.kt` 的 transformer 列表。
- **本地文件打开**：`ui/components/richtext/LocalFileOpener.kt`（接口 + 解析）、
  `Markdown.kt` 的 `openMarkdownLink` / `MarkdownBlock`、`RouteActivity.kt` 的根部 opener 与
  `ImagePreviewDialog`、`workspaceEditorTarget` 两个顶层私有函数。
- **网页视图图片**：`ui/components/richtext/MarkdownWeb.kt`（改写规则）、
  `ui/components/webview/{WebViewLocalAssets,WebViewRemoteImages,WebView}.kt`（两条取图链路）。
- **图标**：`ui/components/richtext/ForkIcons.kt` + 门禁脚本 + `docs/superpowers/data/*.json`。

---

## 5. 待办

### 5.1 设备核验（**CI 全绿 ≠ 功能对**）

| 项 | 怎么验 | 状态 |
|---|---|---|
| 网页视图里的 `file://` 图片 | 带工作区图片的消息 → 网页视图 | ✅ **用户 2026-09-19 已验证通过** |
| 网页视图里的外网图（要经代理才通的） | 搜索结果里的图 → 网页视图 | ⏳ 待验证（`9fb0740b`，CI 绿之后） |
| 聊天里图片的宽高比 | 竖图/横图各一张，看留白 | ⏳ 待验证（`82ba1cc9`） |
| 点 `file://` 链接不崩 + 弹应用内预览 | 聊天正文与弹出层各点一次（弹出层那次才是原崩溃点） | ⏳ 待验证（`a8317c25`） |
| 图标：网络项应是完整地球 | 偏好设置 → 网络 | ⏳ 待验证（`5a02c692`） |
| 提示词两条 | 无工作区助手发图后看它会不会引用 `file:///upload/…`；搜索后看它是否先 `read_image` | ⏳ 模型行为，需多用几次 |

验不动时手上的两个抓手（本轮都派上过用场）：

1. **崩溃报告**：崩溃后重开 App 会进**安全模式页**，上面有栈、一个按钮能复制到剪贴板
   （`CrashHandler` + `SafeModeActivity`）。
2. **Console Logs**：网页视图右上角 ⋮ → Console Logs —— 404（改写/拦截错了）vs
   `net::ERR_…`（网络不通）能直接分叉。

### 5.2 悬着的（本轮已知、未做）

- **`/upload` 里的非图片文件**没有应用内预览（编辑器读不到 bind mount）—— 要做就得给工作区管理器
  加 upload 区。
- **仍是裸 `startActivity` 的地方**（同类崩溃风险，本轮没动）：
  `ChatMessage.kt:543/566/604`（视频/音频/文档附着 chip，拿到沙箱路径就会崩）、
  `WorkspaceTerminalSession.kt:253`（终端里的链接）。
- **`ChatMessage.kt:704`** 的引用链接仍是裸 `LinkAnnotation.Url`（在 markdown 子树之外，
  现在有根部兜底所以不会崩，但走的是系统浏览器而不是应用内预览）。
- **网页视图外网图若仍有个别裂**：剩下的可能性是站点侧要求（如强制校验 `Referer` 为自家域名）。
  那种要按域名单独加 Referer —— 需要用户提供具体 URL。
- **导出为图片**那条链路本轮没动：用户截图里出现过「图片位置留白」，`82ba1cc9` 修的是布局比例，
  但导出前的图片预加载（`BitmapComposer`）是否覆盖工作区图片**未验证**。
- `SettingWebPage.kt` 有一条早就没用的 `import ...stroke.StopCircle`（上一个功能移除时漏的，与本轮无关）。

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无 Android 编译器**：编译/单测结论只从 GitHub Actions 取；先 `git push` 再
  `gh workflow run nightly-build-debug.yml --ref master`；**只用 `--json` 判结论**（§3.1）。
- **不再手动触发 pre / release 工作流**（用户 2026-09-13 指令），只管住自己；两个 cron 保留。
- **huge-icons 钉 1.3**，且 1.3 这个构建**本身缺弧**（347 个图标）—— 别把 `ForkIcons.kt` 当重复定义清掉，
  别把调用点改回 `HugeIcons.*`；加新图标先跑 `hugeicons_glyph_audit.py`。
- 文件删除一律走 `~/.claude/scripts/trash.sh`，禁止 `rm`；禁止命令行清空回收站。
- 提交信息用中文、说清「为什么」；`docs/` 下的 `.md`/`.py` 是 LF，`.kt` 是 CRLF（写新文件后自查）。

---

## 7. 停靠点

**先验证 `09bb1970` 的 CI 结论**（`gh run view 35428592889 --json conclusion`）→ 绿了就让用户装包过 §5.1 的表。
其中**最有价值的一条是宽高比**（纯逻辑 bug，已修，一眼能看出对错）。

若网页视图的外网图仍裂：要用户给一个具体 URL（Console Logs 里能拿到），
按 §5.2 最后两条分叉（站点侧 Referer 要求 vs 其它）。

本轮改动面比往常大（9 个提交、10 个文件），但**没有动数据/DB/构建配置**，
回滚锚点是 `95f44008`。
