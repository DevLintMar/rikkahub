# 交接文档：导出为图片的工作区图片 + 工作区备份/导入的权限位

**最后核对：2026-09-19** —— 仓库 `HEAD = 76287884`（与 origin 同步）。**三次修复的 CI 全绿**
（`e02edafd` run `35430010140`、`7e86ce18` run `35431237348`、`76287884` run `35432962717`，
判定方式见 §3.1）。用户已在设备上验过前两条（§5.1 里已勾），**本轮没有待验证的阻塞项**。

---

## 0. 一句话概况

用户报了 2 件事，各自**一个**独立根因，都是「某条链路上少做了一步」；第 3 条是用户看了本文档
§5.2 之后要求一并修的（同一个跳过判据的另一半）：

1. **导出为图片时，工作区内的图片不显示** —— 导出是独立组合子树，没把会话的 `workspaceId`
   传下去，`file:///workspace/…` 解析不出真实文件（§2.1）。
2. **工作区导出再导入后报错且无法使用** —— zip **不带** Unix 权限位，导入出来的 rootfs
   每个文件都没有可执行位，proot 起壳第一步就失败（§2.2）。

3. **备份把 `.l2s.*` 与嵌套 `tmp` 也滤掉了** —— 导出按**文件名在任意深度**跳过条目，
   于是 proot 的仿真链接后备文件（沙箱文件系统的一部分）与用户自建的 `files/tmp/...`
   静默不进备份（§2.4）。

第 2 条的现场报错是用户给的：`proot error: '/usr/bin/env' is not executable`。

---

## 1. 提交链

| # | 提交 | 内容 |
|---|---|---|
| 1 | `e02edafd` | 导出为图片补 `workspaceId`（工作区图片不再空白） |
| 2 | `7e86ce18` | 工作区备份保留权限位（新增 `FilePermissions.kt`、`WorkspaceBackupTest`） |
| 3 | `76287884` | 备份只跳顶层 `tmp/`，保留 `.l2s.*` 与嵌套 `tmp`（§2.4） |

起点是上一份交接 `2026-09-19-ui-images-links-and-webview-fixes.md` 的 `23ca96c0`。

### 1.1 用户原话 → 根因 → 落点

| # | 用户原话 | 根因 | 落点 |
|---|---|---|---|
| 1 | 「导出功能仍然不显示工作区内图片」 | 导出组合（`ChatExportSheet → exportToImage → ExportedChatImage → ExportedChatMessage`）给 `MarkdownBlock` 传的是默认 `workspaceId = null`；`WorkspaceFileUrlResolver.resolveFile` 在**需要工作区却没给 id** 时返回 null（`WorkspaceFileUrlResolver.kt:60-61`），于是 Coil 拿到解析不出的沙箱 URL | `Export.kt` |
| 补 | 「（§5.2 里那个隐患）这个隐患现在也修复吧」 | 导出的跳过判据按**名字在任意深度**匹配，把 `.l2s.*` 后备文件与 `files/tmp/...` 也滤出了备份 | `WorkspaceBackup.addDirectoryToZip` |
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

### 2.4 备份的跳过判据：只认顶层 `tmp/`（别按名字在任意深度过滤）

原来是这一行：

```kotlin
if (file.name == TEMP_DIR_NAME || file.name.startsWith(".l2s.")) continue
```

它在**任意深度**按名字过滤，于是三类内容一起被丢出备份：

| 被丢掉的 | 实际是什么 |
|---|---|
| `.l2s.data.bin.0002.0002` | proot `--link2symlink` 的**仿真链接后备文件**：指向它的符号链接在导入后全变悬空（文件读不到、只剩断链）。装过 pip/npm 之类在沙箱内建过链接的 rootfs 会中招 |
| `files/tmp/...` | **用户自己**在沙箱里建的 `tmp` 目录 —— 用户数据静默不进备份 |
| `linux/tmp/...` | rootfs 自己的 `/tmp`（`RootfsPatcher.ensureTempDirs` 会在起壳前补建，所以这条危害最小，但同样不该按名字命中） |

依据是 proot 源码：`src/extension/link2symlink/link2symlink.c` 里 `#define PREFIX ".l2s."`，
后备文件就是**同目录下 `.l2s.<原名><NNNN>.<NNNN>` 的普通文件**。

改法：`if (prefix.isEmpty() && file.name == TEMP_DIR_NAME) continue` —— 只跳过**顶层**那一个
（`prefix` 为空即顶层），它才是工作区自己的临时目录（proot 的 `PROOT_TMP_DIR`/`TMPDIR` 指向它）。
这同时让导出与 `extractTo` 的判据一致：**导入侧本来就只认顶层 `tmp/`**（`entry.name == "tmp" ||
startsWith("tmp/")`），两者此前是错位的。

> **边界**：显示层的 `.l2s.` 过滤照旧保留 —— `WorkspaceFileSystem` 的 list/glob/grep 与
> `WorkspaceDocumentsProvider` 都在过掉它，那是「别让用户看见 proot 的内部文件」，与备份内容无关。
> 改的时候别把两边一起动。

新增两条单测：`导出只跳过顶层临时目录`（`.l2s.` 与 `files/tmp/keep.txt` 必须在、顶层 `tmp/scratch.txt`
必须不在）、`l2s 链接在导入后仍能解析到后备文件`（只搬链接会让文件变断链）。

---

## 3. git / CI 状态

- 代码 HEAD = `76287884`，与 origin 同步。
- `e02edafd`：run `35430010140`，`conclusion=success`，`headSha` 对得上，`build` 作业
  **21 步 0 skipped**。
- **`7e86ce18` 也已真绿**：run `35431237348`，`conclusion=success`，`headSha` 对得上，
  `build` 作业 **21 步 0 skipped**；`:app:testDebugUnitTest` 与 `:workspace:testDebugUnitTest`
  都跑了并 `BUILD SUCCESSFUL` —— 新增的 `WorkspaceBackupTest` 第一次执行。
  注意：**run 只上传 `room-schemas` artifact，看不到单测逐条结果**；那三条用例靠
  `assumeTrue` 在非 POSIX 文件系统上跳过，而 CI 是 ubuntu-latest（ext4，POSIX 视图受支持），
  所以可执行位那条断言是**真跑**的，不是被跳过后的假绿。
- **`76287884` 也真绿**：run `35432962717`，`conclusion=success`，`headSha` 对得上，
  `build` 作业 **21 步 0 skipped**；`:app:testDebugUnitTest` 与 `:workspace:testDebugUnitTest`
  都 `BUILD SUCCESSFUL` —— 备份那 5 条单测（3 条权限位/符号链接 + 2 条跳过判据）都在这一版里跑。

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
| 导出为图片里的工作区图片 | 找一条 AI 用 markdown 引用了工作区图片的会话 → 导出为图片 | ✅ **用户 2026-09-19 已验证通过** |
| 工作区导出 → 导入 → 终端可用 | 导入后进新工作区的终端跑 `env`、`bash -lc "ls /usr/bin"` | ✅ **用户 2026-09-19 已验证通过**（导入后正常可用） |
| 备份不再丢 `.l2s.*` 与嵌套 `tmp` | 在沙箱里 `ln -s` 建过一个链接的工作区：导出→导入，看链接还能不能用 / `files/tmp` 里的文件还在不在 | ⏳ 待验证（`76287884`，改动面小、非阻塞） |
| 老 zip 仍可导入 | 用修复前导出的那份 zip 再导一次（走 `legacyOwnerMode` 兜底） | ⏳ 可选 |

上一份交接（`2026-09-19-ui-images-links-and-webview-fixes.md` §5.1）那 5 条设备核验仍未做完，
两件事可以一起过。

### 5.2 悬着的（本轮已知、未做）

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

**三条都已绿、前两条用户已验（见 §3/§5.1）** —— 本轮没有阻塞项。剩下可选的一条：
拿一个有沙箱内链接（`.l2s.*`）的工作区走一遍导出→导入，确认 `76287884` 那条改动在设备上成立。

若导入后仍报错，先看报错文案再动手：proot 的「找不到」与「不可执行」是两句不同的话（§2.2），
而 `WorkspaceRepository.importWorkspace` 的异常会经 `WorkspacePage` 的 toast 带 `e.message` 显出来
（`非法 zip 路径` / `非法符号链接路径` 是 `extractTo` 的 `require`）。

回滚锚点：`23ca96c0`（三份修复互不依赖，可分别回滚）。
