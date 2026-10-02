package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

/**
 * 内置技能：以 `assets/builtin_skills/<name>/` 随 APK 发布，目录结构与用户技能相同（SKILL.md + 附属文件）。
 *
 * App 安装/更新后解压到 `filesDir/builtin_skills/`，并挂载到 workspace 的 `/builtin_skills`，
 * 这样附属脚本可以在 workspace 中执行。内置技能只读，与用户技能同名时以用户技能为准。
 *
 * 注意：aapt 默认会忽略以 `.` 开头的文件和以 `_` 开头的目录，不要用这类名称。
 */
internal object BuiltinSkills {
    private const val ASSETS_ROOT = "builtin_skills"
    private const val VERSION_FILE = ".version"

    /**
     * 当 App 安装/更新时间变化时，将 assets 中的内置技能完整解压到 [targetDir]，替换旧内容。
     *
     * 用 lastUpdateTime 而非 versionCode 判断，保证开发时每次重新安装都会刷新。
     */
    fun extractIfNeeded(context: Context, targetDir: File) {
        val stamp = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
            .toString()
        val versionFile = targetDir.resolve(VERSION_FILE)
        if (versionFile.exists() && versionFile.readText() == stamp) return

        // 先完整解压到临时目录再替换，避免中途失败留下半套文件
        val staging = targetDir.resolveSibling(".${targetDir.name}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val assets = context.assets
        assets.list(ASSETS_ROOT).orEmpty().forEach { name ->
            assets.copyTree("$ASSETS_ROOT/$name", staging.resolve(name))
        }
        staging.resolve(VERSION_FILE).writeText(stamp)

        targetDir.deleteRecursively()
        if (!staging.renameTo(targetDir)) {
            staging.deleteRecursively()
            error("Failed to move builtin skills into ${targetDir.absolutePath}")
        }
    }

    private fun AssetManager.copyTree(assetPath: String, target: File) {
        val children = list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            target.mkdirs()
            children.forEach { copyTree("$assetPath/$it", target.resolve(it)) }
            return
        }
        // AssetManager 无法区分文件和空目录：list 为空时先按文件打开，打不开则视为空目录
        try {
            open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (_: FileNotFoundException) {
            target.mkdirs()
            return
        }
        // assets 不保留可执行位，带 shebang 的脚本需要手动恢复，才能在 workspace 中直接 ./script 执行
        if (target.startsWithShebang()) {
            target.setExecutable(true, false)
        }
    }

    private fun File.startsWithShebang(): Boolean = inputStream().use { input ->
        input.read() == '#'.code && input.read() == '!'.code
    }
}

/**
 * 系统内置的核心元技能（Meta-Skills），承载系统运行环境分析、技能评估等核心基础设施能力。
 * 内置 Meta-Skill 受到系统保护，绝对不允许被用户目录下的同名技能静默覆盖或消除。
 */
val PROTECTED_META_SKILLS: Set<String> = setOf("skill-creator", "environment-setup")

fun isMetaSkill(name: String): Boolean = name in PROTECTED_META_SKILLS

/**
 * 合并用户技能与内置技能：
 * 1. 系统内置的 Meta-Skill 享有系统级保护，永远保留官方版本，绝不被同名用户技能覆盖；
 * 2. 如果用户在本地创建了与 Meta-Skill 同名的技能，该用户技能保留并重命名为 `<name>-custom`，两全其美；
 * 3. 其他非 Meta-Skill 的普通内置技能如果同名，以用户技能优先。
 */
internal fun mergeWithBuiltinSkills(
    local: List<SkillMetadata>,
    builtin: List<SkillMetadata>,
): List<SkillMetadata> {
    val builtinMetaSkillNames = builtin.filter { isMetaSkill(it.name) }.mapTo(HashSet()) { it.name }

    // 与内置 Meta-Skill 冲突的本地用户技能重命名为 -custom 变体，避免遮蔽官方基础设施
    val resolvedLocal = local.map { skill ->
        if (skill.name in builtinMetaSkillNames) {
            skill.copy(
                name = "${skill.name}-custom",
                description = "[Custom] " + skill.description
            )
        } else {
            skill
        }
    }

    val localNames = resolvedLocal.mapTo(HashSet()) { it.name }
    // 内置 Meta-Skill 永远保留；普通内置技能若与 localNames 冲突则允许覆盖
    val survivingBuiltin = builtin.filter {
        isMetaSkill(it.name) || it.name !in localNames
    }

    return resolvedLocal + survivingBuiltin
}
