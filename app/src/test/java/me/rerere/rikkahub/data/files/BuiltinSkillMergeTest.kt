package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltinSkillMergeTest {
    @Test
    fun `user skill with same name does NOT override protected meta skill`() {
        val userSkillCreator = SkillMetadata(
            name = "skill-creator",
            description = "User custom skill creator",
            skillDir = File("/data/skills/skill-creator"),
            builtin = false,
        )

        val builtinSkillCreator = SkillMetadata(
            name = "skill-creator",
            description = "Official builtin meta skill",
            skillDir = File("/data/builtin_skills/skill-creator"),
            builtin = true,
        )

        val merged = mergeWithBuiltinSkills(
            local = listOf(userSkillCreator),
            builtin = listOf(builtinSkillCreator),
        )

        assertEquals(2, merged.size)

        // 1. Builtin meta skill is preserved with official name
        val official = merged.find { it.name == "skill-creator" }
        assertNotNull(official)
        assertTrue(official!!.builtin)
        assertEquals("Official builtin meta skill", official.description)

        // 2. User skill is safely mapped to -custom variant
        val custom = merged.find { it.name == "skill-creator-custom" }
        assertNotNull(custom)
        assertFalse(custom!!.builtin)
        assertTrue(custom.description.startsWith("[Custom]"))
        assertEquals(File("/data/skills/skill-creator"), custom.skillDir)
    }

    @Test
    fun `regular non-meta builtin skill is overridden by user skill`() {
        val userRegular = SkillMetadata(
            name = "custom-template",
            description = "User version",
            skillDir = File("/data/skills/custom-template"),
            builtin = false,
        )

        val builtinRegular = SkillMetadata(
            name = "custom-template",
            description = "Builtin version",
            skillDir = File("/data/builtin_skills/custom-template"),
            builtin = true,
        )

        val merged = mergeWithBuiltinSkills(
            local = listOf(userRegular),
            builtin = listOf(builtinRegular),
        )

        assertEquals(1, merged.size)
        val single = merged.first()
        assertEquals("custom-template", single.name)
        assertFalse(single.builtin)
        assertEquals("User version", single.description)
    }
}
