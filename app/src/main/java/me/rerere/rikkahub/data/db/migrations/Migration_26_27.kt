package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v27：补上上游给 workspaces 加的 shell_compatibility_mode 列（Shell 兼容模式）。
 *
 * 为什么是 v27 而不是 v25：两个分支各自从 24 → 25 加了不同的东西 —— 我们加了
 * message_embeddings 表与记忆的 title/description/is_active，上游给 workspaces 加了这列。
 * 同一版本号下是两个不同构的 schema，所以上游那部分只能顺延到我们之后。
 *
 * 上游自己的 v25 里已经带这列，因此跨包名/外来备份恢复过来的库可能已经有它
 * （fork 支持恢复别人的备份），那时直接 ALTER 会报 duplicate column name —— 先探测再决定。
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (db.hasColumn("workspaces", "shell_compatibility_mode")) return
        db.execSQL("ALTER TABLE workspaces ADD COLUMN shell_compatibility_mode INTEGER NOT NULL DEFAULT 0")
    }
}

