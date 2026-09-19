package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * 文件权限位（属主 `rwx` 三位）的读写。
 *
 * **为什么单独抽出来**：权限位属于文件系统元数据，**tar 带、zip 不带**。
 * - 解 tar 装 rootfs（[RootfsInstaller]）时按 tar header 的 mode 设一遍；
 * - 工作区备份走 zip，`ZipEntry` 里根本没有权限位，重新解出来的都是「新建的普通文件」——
 *   连 `/usr/bin/env` 都**没有可执行位**。proot 起壳时会对它做 exec 检查，直接报
 *   `proot error: '/usr/bin/env' is not executable`，整个沙箱不可用
 *   （2026-09-19 用户报的「导出再导入后无法使用」就是这条）。
 *
 * 所以导出侧要把权限位**另存**（见 `WorkspaceBackup.MODES_ENTRY`），导入侧按同一条规则设回去。
 * 两条路径（tar / zip）共用本文件，避免各写一份之后漂移。
 *
 * 只取**属主**那三位、并作用到全部用户类别：应用私有目录里组/其它位没有实际语义
 * （父目录权限已经挡住），而且 proot 是 `--root-id` 跑的，guest 侧看到的是假装出来的权限。
 */

/** tar mode 里的属主位：读 = bit8、写 = bit7、执行 = bit6。 */
private const val OWNER_READ = 0b100_000_000
private const val OWNER_WRITE = 0b010_000_000
private const val OWNER_EXECUTE = 0b001_000_000

/** tar header 的 mode（或 [readOwnerMode] 的返回值）→ 属主三位 `0..7`。 */
fun ownerModeOf(mode: Int): Int = (mode shr 6) and 0b111

/** 读宿主文件的属主三位 `0..7`；POSIX 视图不可用时按 `canRead/canWrite/canExecute` 合成。 */
fun File.readOwnerMode(): Int {
    val permissions = runCatching { Files.getPosixFilePermissions(toPath()) }.getOrNull()
    return if (permissions != null) {
        var mode = 0
        if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0b100
        if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0b010
        if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0b001
        mode
    } else {
        var mode = 0
        if (canRead()) mode = mode or 0b100
        if (canWrite()) mode = mode or 0b010
        if (canExecute()) mode = mode or 0b001
        mode
    }
}

/** 把属主三位 `0..7` 设回文件（作用到全部用户类别，见文件头说明）。 */
fun File.applyOwnerMode(ownerMode: Int) {
    val mode = (ownerMode and 0b111) shl 6
    setReadable(mode and OWNER_READ != 0, false)
    setWritable(mode and OWNER_WRITE != 0, true)
    setExecutable(mode and OWNER_EXECUTE != 0, false)
}
