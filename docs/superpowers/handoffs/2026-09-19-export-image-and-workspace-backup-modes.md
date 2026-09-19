# 交接文档：导出为图片的工作区图片 + 工作区备份/导入的权限位

**最后核对：2026-09-19** —— 仓库 `HEAD = 7e86ce18`（与 origin 同步）。`e02edafd` 的 CI 已绿；
`7e86ce18` 的 CI 在本文档写就时在跑，**结论只认 `--json`**（判定方式见 §3.1）。

---

## 0. 一句话概况

用户报了 2 件事，各自**一个**独立根因，都是「某条链路上少做了一步」：

1. **导出为图片时，工作区内的图片不显示** —— 导出是独立组合子树，没把会话的 `workspaceId`
   传下去，`file:///workspace/…` 解析不出真实文件（§2.1）。
2. **工作区导出再导入后报错且无法使用** —— zip **不带** Unix 权限位，导入出来的 rootfs
   每个文件都没有可执行位，proot 起壳第一步就失败（§2.2）。

第 2 条的现场报错是用户给的：`proot error: '/usr/bin/env' is not executable`。

---

## 1. 提交链

| # | 提交 | 内容 |
|---|---|---|
| 1 | `e02edafd` | 导出为图片补 `workspaceId`（工作区图片不再空白） |
| 2 | `7e86ce18` | 工作区备份保留权限位（新增 `FilePermissions.kt`、`WorkspaceBackupTest`） |

起点是上一份交接 `2026-09-19-ui-images-links-and-webview-fixes.md` 的 `23ca96c0`。

### 1.1 用户原话 → 根因 → 落点

| # | 用户原话 | 根因 | 落点 |
|---|---|---|---|
| 1 | 「导出功能仍然不显示工作区内图片」 | 导出组合（`ChatExportSheet → exportToImage → ExportedChatImage → ExportedChatMessage`）给 `MarkdownBlock` 传的是默认 `workspaceId = null`；`WorkspaceFileUrlResolver.resolveFile` 在**需要工作区却没给 id** 时返回 null（`WorkspaceFileUrlResolver.kt:60-61`），于是 Coil 拿到解析不出的沙箱 URL | `Export.kt` |
| 2 | 「导出工作区再导入之后会报错并无法使用」 | `ZipEntry` 没有 Unix 权限位（tar 才有）：装 rootfs 的 tar 路径有 `applyMode(header.mode)`，zip 路径 `WorkspaceBackup.extractTo` 从头到尾没设过任何权限 → 导入的 rootfs 全是 0644 | `WorkspaceBackup.kt`、新 `workspace/.../FilePermissions.kt`、`RootfsInstaller.kt` |

---

## 2. 关键决策与认知

### 2.1 「只有工作区图片丢」这个指纹直接指出了 workspaceId

导出渲染 markdown 走的是 `MarkdownBlock(workspaceId = …)`，里面 `LocalWorkspaceFileProvider`
= `resolveFile(filesDir, workspaceId, href)`。而 `resolveFile` 的分支顺序是：

- **bind mount 分支在前**（`/upload`、`/skills`、`/tool_outputs`）→ **不需要 workspaceId**；
- 之后才是 `/workspace/<rel>` 分支，`workspaceId` 为 null 时**直接 return null**（`:60-61`）。

所以缺 `workspaceId` 只会让**工作区**里的图片失效，`/upload` 的照常显示 —— 与用户看到的
「不显示**工作区内**图片」完全一致。这条指纹以后同样可以用来分叉（见 memory
`local-file-link-opener`）。

修法：导出前用 `settings.getAssistantById(conversation.assistantId)?.workspaceId` 取 id
（与聊天页同一来源），逐层传到 4 个 `MarkdownBlock` 调用点。

> 注意：`UIMessagePart.Image` 那条路**不受影响** —— 附件/工具产出的图都被
> `FilesManager.createChatFilesByContents` 物化成宿主文件了（`ChatInputState.addImages` /
> `ReadImageTools` / `WorkspaceTools.readImageInRootfs` 都是），`preloadExportImages` 用宿主
> 路径加载没问题。

### 2.2 zip 不带权限位 —— 这是「导入后无法使用」的全部

权限位是文件系统元数据：**tar 带、zip 不带**（`java.util.zip.ZipEntry` 根本没有这个字段）。

- 装 rootfs 走 tar：`RootfsInstaller` 对每个 FILE 条目 `applyMode(header.mode)`；
- 备份走 zip：`extractTo` 只 `target.outputStream()` 写内容 —— 宿主的 `FileOutputStream`
  只会给出 0644 等价物，**一个可执行文件都没有**。

proot 起壳时 exec 的是 `/usr/bin/env`（`ProotShellRunner.buildCommand` 里显式写的），于是：

```
proot error: '/usr/bin/env' is not executable
fatal error: see `libproot_exec.so --help`.
```

**这句报错本身就是分叉点**：proot 对「文件不存在」说的是 `No such file or directory`，
「文件在但不可执行」才是这句。所以不用上设备也能确定是权限位、不是路径错。

修法：

- 导出侧 `modes.json` 条目（`条目路径 -> 属主 rwx 三位`），与文件内容一起打包；
- 导入侧按清单 `applyOwnerMode` 设回去；
- 权限位读写抽到 `workspace/src/main/java/me/rerere/workspace/FilePermissions.kt`
  （`readOwnerMode` / `applyOwnerMode` / `ownerModeOf`），**解 tar 那条路径也改用它**
  （原来 `RootfsInstaller` 里的私有 `applyMode` 已删，行为逐位等价）；
- 老 zip（没有清单）按「`linux/` 给 `rwx`、其余给 `rw`」兜底 —— 否则用户手上已经导出的
  那份包导入后依然是起不了壳的废 rootfs（重导一次也行，兜底成本更低）。

只取**属主**三位并作用到全部用户类别：应用私有目录里组/其它位没有实际语义，proot 又是
`--root-id` 跑的，guest 侧权限本来就是假装的。与解 tar 那条路径保持一致。

### 2.3 三条 JVM 单测（`app/src/test/.../data/sync/WorkspaceBackupTest.kt`）

| 用例 | 钉住什么 |
|---|---|
| 导出导入后 rootfs 文件仍带可执行位 | 本次的 bug（`linux/usr/bin/env` 的 +x） |
| 保留可读写位与非可执行位 | 别为了修 +x 把普通文件全给成可执行 |
| 保留符号链接语义 | `symlinks/` 那条往返（本轮顺带第一次被自动化覆盖） |

POSIX 权限位 / 符号链接只有 Linux 有意义，用 `assumeTrue` 在不支持时跳过，**避免在 Windows 上假绿**；
CI 是 ubuntu-latest，所以它们是**真跑**的。

---

## 3. git / CI 状态

- 代码 HEAD = `7e86ce18`，与 origin 同步。
- `e02edafd`：run `35430010140`，`conclusion=success`，`build` 作业 **21 步 0 skipped**。
- `7e86ce18`：run `35431237348`，本文档写就时在跑。**恢复后第一件事**：按 §3.1 判它。
  这次多了一个看点：**新增的 `WorkspaceBackupTest` 三条用例在 Linux 上是否真绿**
  （它们是本次修复的证明，不是装饰）。

### 3.1 结论只认 `--json`（`gh run watch` 的退出码不可信）

```bash
gh run view <id> --json conclusion,headSha          # 核对 headSha 是本次提交
gh run view <id> --json jobs                        # 确认 build 没被 skip 成假绿
```

（本轮实测过两次：`failure` 的 run 也返回 exit=0。见 memory `gh-run-watch-monitoring`。）

---

## 4. 恢复地图

```bash
git log --oneline 23ca96c0..HEAD
git show e02edafd --stat      # 导出修复
git show 7e86ce18 --stat      # 备份权限位修复
gh run view 35431237348 --json conclusion
```

- **导出为图片**：`ui/pages/chat/Export.kt`（`ChatExportSheet` 取 workspaceId → `exportToImage`
  → `ExportedChatImage` / `ExportedChatMessage` / `ExportedReasoningStep` → 4 个 `MarkdownBlock`）。
- **工作区备份**：`data/sync/WorkspaceBackup.kt`（`modes.json` 的写/读、`legacyOwnerMode` 兜底）、
  `workspace/src/main/java/me/rerere/workspace/FilePermissions.kt`（权限位唯一实现）、
  `WorkspaceRepository.exportWorkspace/importWorkspace`（调用方）。

---

## 5. 待办

### 5.1 设备核验（CI 绿 ≠ 功能对）

| 项 | 怎么验 | 状态 |
|---|---|---|
| 导出为图片里的工作区图片 | 找一条 AI 用 markdown 引用了工作区图片的会话 → 导出为图片 | ⏳ 待验证（`e02edafd`） |
| 工作区导出 → 导入 → 终端可用 | 导入后进新工作区的终端跑 `env`、`bash -lc "ls /usr/bin"` | ⏳ 待验证（`7e86ce18`） |
| 老 zip 仍可导入 | 用修复前导出的那份 zip 再导一次（走 `legacyOwnerMode` 兜底） | ⏳ 可选 |

上一份交接（`2026-09-19-ui-images-links-and-webview-fixes.md` §5.1）那 5 条设备核验仍未做完，
两件事可以一起过。

### 5.2 悬着的（本轮已知、未做）

- **导出跳过 `.l2s.*` 是同函数里的第二个隐患**（本轮没动）：那个过滤是上游为「文件列表 key
  重复」加的**显示层**过滤（`WorkspaceFileSystem.kt:21`），但 `WorkspaceBackup.addDirectoryToZip`
  用同一条判断把备份内容也跳过了。而 proot 的 `--link2symlink` 就是用 `.l2s.<原名>` **前缀文件**
  仿真链接的（termux/proot `extension/link2symlink/link2symlink.c`：`#define PREFIX ".l2s."`），
  这些条目属于沙箱文件系统本身 —— 跳过等于导入后丢掉沙箱内创建过的链接。
  要不要改成「显示过滤照旧、备份保留」需要用户拍板（本轮先分开，不混进验证）。
- 导出的 markdown 图片仍走 Coil 异步加载 + `BitmapComposer` 的固定 100ms 等待（`Image` part
  有 `preloadExportImages` 预加载，markdown 图片没有）。本轮没动它：用户只报工作区图片丢，
  说明其它 markdown 图片实际能出来（走的是同一条代码路径，只差解析结果），所以竞速不是本次因。
- `/upload` 里的非图片文件仍没有应用内预览；`ChatMessage.kt` 的文档/视频/音频 chip 与
  `WorkspaceTerminalSession.kt:253` 仍是裸 `startActivity`（沿用上一份交接 §5.2）。

---

## 6. 技术约束 / 惯例（沿用，必须遵守）

- **本机无 Android 编译器**：编译/单测结论只从 GitHub Actions 取；先 `git push` 再
  `gh workflow run nightly-build-debug.yml --ref master`；只用 `--json` 判结论（§3.1）。
- 文件删除一律走 `~/.claude/scripts/trash.sh`，禁止 `rm`；禁止命令行清空回收站。
- 仓库里 `.kt` 的行尾是混合的（LF/CRLF 都有），`core.autocrlf=true` 会在提交时归一成 LF ——
  **改文件时保持它原有行尾**，别整文件换行尾制造假 diff。
- 提交信息用中文说清「为什么」；新增门禁脚本要进 nightly（本轮没加脚本）。

---

## 7. 停靠点

**先判 `7e86ce18` 的 CI**（`gh run view 35431237348 --json conclusion`，见 §3.1）→ 绿了让用户过
§5.1 的表。两条里**工作区导入那条最有价值**：它是「整包功能不可用」，一眼就能判对错
（导入后终端能起来就是好的）。

若导入后仍报错，先看报错文案再动手：proot 的「找不到」与「不可执行」是两句不同的话（§2.2），
而 `WorkspaceRepository.importWorkspace` 的异常会经 `WorkspacePage` 的 toast 带 `e.message` 显出来
（`非法 zip 路径` / `非法符号链接路径` 是 `extractTo` 的 `require`）。

回滚锚点：`23ca96c0`（两份修复互不依赖，可分别回滚）。
