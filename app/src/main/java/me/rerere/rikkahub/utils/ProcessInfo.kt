package me.rerere.rikkahub.utils

import android.os.Process

/**
 * 本进程的身份：pid 与启动墙钟时刻。
 *
 * 为什么需要它：子代理的生命周期跨「父生成结束 → 后台任务继续跑 → 终态投递入库」三段，
 * 而登记表（`SubAgentTaskRegistry`）与会话状态都**只活在内存里**。进程换了一茬之后，
 * 一条「还是 started 的工具结果」看起来和「刚发起」没有任何区别，于是中断对账只能靠
 * 「本进程认不认识这个 taskId」来判定——**而这个判定本身无法自证**：本进程刚写下的卡片，
 * 和上一个进程留下的卡片，长得一模一样。
 *
 * 所以把进程身份当数据写下来，让证据落在库里与日志里：
 * - 工具结果里带上发起时的 `process_started_at`（重启后仍在，见 `SubAgentTool`）；
 * - 每一行诊断日志带上 pid 与 `procStart`（应用内「日志」页可直接复制）。
 *
 * 两者一对比，「这条回执说的任务属于哪个进程」就不再需要推测。
 */
object ProcessInfo {
    @Volatile
    var startedAt: Long = 0L
        private set

    val pid: Int get() = Process.myPid()

    /** `Application.onCreate` 里调一次；重复调用是空操作。 */
    fun markStart() {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
    }

    /** 诊断日志用的一行身份描述。 */
    fun describe(): String = "pid=$pid procStart=$startedAt"
}
