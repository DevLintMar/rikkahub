package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26：记忆三列（fork 自有的活跃记忆/标题/描述）。
 *
 * 这里额外补建 `message_embeddings`：它原本只由 `AutoMigration(24, 25)` 的**编译期产物**创建，
 * 我们自己的 v24/v25 库升上来必然有；但 fork 支持恢复**别人**的备份，而上游 rikkahub 的 v25
 * 库里没有这张表，也不会经过 24→25 —— 那种库一路走到 v27 后会以
 * `Migration didn't properly handle: message_embeddings(…)` 开库失败。
 * 建表语句与 `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json` 的 `createSql` 逐字一致，
 * 已有该表时是空操作。
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_embeddings` (`message_id` TEXT NOT NULL, " +
                    "`node_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
                    "`model_name` TEXT NOT NULL, `status` INTEGER NOT NULL, `chunk_text` TEXT NOT NULL, " +
                    "`embedding` BLOB, `dimension` INTEGER, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`message_id`))"
            )
            if (!db.hasColumn("memoryentity", "title")) {
                db.execSQL("ALTER TABLE memoryentity ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
            if (!db.hasColumn("memoryentity", "description")) {
                db.execSQL("ALTER TABLE memoryentity ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
            if (!db.hasColumn("memoryentity", "is_active")) {
                db.execSQL("ALTER TABLE memoryentity ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0")
            }
            // 旧记忆回填标题（内容前 40 字符，与运行时兜底推导规则一致）
            db.execSQL("UPDATE memoryentity SET title = substr(trim(content), 1, 40) WHERE title = ''")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
