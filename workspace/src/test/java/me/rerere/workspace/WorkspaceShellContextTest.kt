package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁死 [WorkspaceShellContext] 的字段集合与构造器形态。
 *
 * 背景：这个 data class 的字段曾经**都带默认值**，而上游给 fork 加字段时，
 * 只有同步路径被改、fork 独有的流式路径静默漏传 —— `shellCompatibilityMode`
 * 就是这么丢的（AI 的 `workspace_shell` 拿不到开关，不报错、CI 全绿）。
 * 现在构造点已收敛到 `WorkspaceManager.buildShellContext` 一处，这个测试再加一道
 * 不依赖人工 review 的保险：
 *
 * 1. **只能有一个构造器** —— Kotlin 只要有任何字段带默认值就会多生成一个
 *    `$default` 合成构造器，所以这条同时断言「字段一个默认值都没有」；
 * 2. **字段集合必须完全一致** —— 上游加/删/改名字段时直接红，逼人确认新字段
 *    要不要在新旧两条 shell 路径上透传。
 *
 * 用 JDK 反射（字段名一定在 class 里）而不是 kotlin-reflect，免得为一条断言
 * 引入运行时反射库。
 */
class WorkspaceShellContextTest {

    private val expectedFields = setOf(
        "root",
        "command",
        "cwd",
        "filesDir",
        "linuxDir",
        "tempDir",
        "workingDir",
        "timeoutMillis",
        "stdin",
        "bindMounts",
        "shellCompatibilityMode",
        "onLine",
    )

    @Test
    fun `has exactly one constructor, meaning no field carries a default value`() {
        val constructors = WorkspaceShellContext::class.java.declaredConstructors
        assertEquals(
            "WorkspaceShellContext 出现了多个构造器 —— 说明有字段被加回了默认值。" +
                "默认值会让漏传字段的调用点静默编译通过（审计 §2-7）；" +
                "请去掉默认值，并让 WorkspaceManager.buildShellContext 显式传参。" +
                "实际构造器数=${constructors.size}",
            1,
            constructors.size,
        )
    }

    @Test
    fun `field set is locked`() {
        val actual = WorkspaceShellContext::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            "WorkspaceShellContext 的字段集合变了：新增的字段必须在 WorkspaceManager.buildShellContext " +
                "里透传，并确认同步路径（无 onLine）与流式路径都拿到正确的值。" +
                "多出=${actual - expectedFields} 少了=${expectedFields - actual}",
            expectedFields,
            actual,
        )
    }
}
