package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0")
            // 旧记忆回填标题（内容前 40 字符，与运行时兜底推导规则一致）
            db.execSQL("UPDATE memoryentity SET title = substr(trim(content), 1, 40) WHERE title = ''")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
